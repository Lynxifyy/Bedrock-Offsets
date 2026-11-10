#!/usr/bin/env python3
"""Generate a changelog from the commits between two revisions.

Subjects are read as conventional commits and grouped into sections, with
breaking changes pulled to the top. A subject that does not follow the
convention is not dropped: it lands in an "Other" section, because a changelog
that silently omits commits is worse than one with an untidy entry.

Exit codes:
    0  the report was produced (to stdout or ``--output``).
    2  git could not answer, for example an unknown revision or a directory
       that is not a repository.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

# Section titles by commit type, in the order they are rendered.
SECTION_TITLES: tuple[tuple[str, str], ...] = (
    ("feat", "Features"),
    ("fix", "Fixes"),
    ("perf", "Performance"),
    ("refactor", "Refactoring"),
    ("docs", "Documentation"),
    ("test", "Tests"),
    ("build", "Build"),
    ("ci", "Continuous integration"),
    ("style", "Style"),
    ("chore", "Other"),
    ("revert", "Reverts"),
    ("other", "Other"),
)

# The breaking-change section is always rendered first.
BREAKING_TITLE = "Breaking changes"

# `type(scope)!: description` and `type: description`. The description may be
# empty, which is still a valid subject.
SUBJECT_PATTERN = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]*)\))?(?P<breaking>!)?:\s*(?P<description>.*)$"
)

# A `BREAKING CHANGE:` or `BREAKING-CHANGE:` trailer in the commit body.
BREAKING_FOOTER_PATTERN = re.compile(r"^BREAKING[ -]CHANGE:", re.MULTILINE)

# Field and record separators for `git log`. A NUL byte cannot appear in a
# commit subject or body, so the record separator is unambiguous.
FIELD_SEPARATOR = "\x1f"
RECORD_SEPARATOR = "\x1e"


class ChangelogError(RuntimeError):
    """Raised when git cannot answer."""


@dataclass(frozen=True)
class ParsedSubject:
    """A commit subject split into its conventional parts.

    Attributes:
        type: the commit type, or ``other`` when the subject does not follow
            the convention.
        scope: the parenthesised scope, or ``None``.
        description: the subject text after the type and scope.
        breaking: whether the subject marks a breaking change with ``!``.
    """

    type: str
    scope: str | None
    description: str
    breaking: bool


@dataclass(frozen=True)
class Entry:
    """One commit, ready to render.

    Attributes:
        subject: the full commit subject.
        sha: the full commit hash.
        body: the commit body, used only to look for a breaking-change trailer.
        parsed: the split subject.
    """

    subject: str
    sha: str
    body: str
    parsed: ParsedSubject

    @property
    def breaking(self) -> bool:
        """Whether this commit breaks compatibility, by ``!`` or by trailer."""
        return self.parsed.breaking or bool(BREAKING_FOOTER_PATTERN.search(self.body))

    @property
    def short_sha(self) -> str:
        """The first seven characters of the hash."""
        return self.sha[:7]


@dataclass(frozen=True)
class Section:
    """A titled group of entries.

    Attributes:
        title: the section heading.
        entries: the commits in the section, in git order.
    """

    title: str
    entries: tuple[Entry, ...]


def parse_subject(subject: str) -> ParsedSubject:
    """Split a commit subject into its conventional parts.

    Args:
        subject: the first line of a commit message.

    Returns:
        The parsed subject. A subject that does not match the convention is
        returned with type ``other`` and the whole subject as its description.
    """
    match = SUBJECT_PATTERN.match(subject.strip())
    if match is None:
        lowered = subject.strip().lower()
        if lowered.startswith("revert "):
            return ParsedSubject(
                type="revert",
                scope=None,
                description=subject.strip(),
                breaking=False,
            )
        return ParsedSubject(
            type="other",
            scope=None,
            description=subject.strip(),
            breaking=False,
        )

    kind = match.group("type").lower()
    if kind not in {name for name, _ in SECTION_TITLES}:
        kind = "other"
    return ParsedSubject(
        type=kind,
        scope=match.group("scope") or None,
        description=match.group("description").strip(),
        breaking=match.group("breaking") is not None,
    )


def git_log(repo: Path, revision: str) -> list[tuple[str, str, str]]:
    """Read commits from ``repo`` for ``revision``.

    Merge commits are excluded: they carry no change of their own.

    Args:
        repo: the repository to read.
        revision: a revision range such as ``v1.0.0..HEAD``, or a single
            revision such as ``HEAD``.

    Returns:
        One ``(sha, subject, body)`` tuple per commit, newest first.

    Raises:
        ChangelogError: if git fails, for example on an unknown revision or a
            directory that is not a repository.
    """
    pretty = FIELD_SEPARATOR.join(["%H", "%s", "%b"]) + RECORD_SEPARATOR
    completed = subprocess.run(
        [
            "git",
            "--no-pager",
            "log",
            "--no-merges",
            f"--pretty=format:{pretty}",
            revision,
        ],
        cwd=repo,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or "git log failed"
        raise ChangelogError(detail)

    commits: list[tuple[str, str, str]] = []
    for record in completed.stdout.split(RECORD_SEPARATOR):
        record = record.strip("\n")
        if not record:
            continue
        fields = record.split(FIELD_SEPARATOR)
        if len(fields) < 3:
            continue
        sha, subject, body = fields[0], fields[1], fields[2]
        commits.append((sha.strip(), subject.strip(), body.strip()))
    return commits


def collect(repo: Path, revision: str) -> list[Entry]:
    """Read and parse the commits for ``revision``.

    Args:
        repo: the repository to read.
        revision: the revision range to describe.

    Returns:
        The parsed entries, newest first.

    Raises:
        ChangelogError: if git cannot answer.
    """
    return [
        Entry(subject=subject, sha=sha, body=body, parsed=parse_subject(subject))
        for sha, subject, body in git_log(repo, revision)
    ]


def group(entries: list[Entry]) -> list[Section]:
    """Group ``entries`` into ordered sections.

    A breaking change is listed only under "Breaking changes"; the same commit
    is not repeated in its type's section.

    Args:
        entries: the parsed entries, newest first.

    Returns:
        The non-empty sections, with breaking changes first.
    """
    breaking = [entry for entry in entries if entry.breaking]
    rest = [entry for entry in entries if not entry.breaking]

    sections: list[Section] = []
    if breaking:
        sections.append(Section(BREAKING_TITLE, tuple(breaking)))

    for kind, title in SECTION_TITLES:
        matching = tuple(entry for entry in rest if entry.parsed.type == kind)
        if matching:
            sections.append(Section(title, matching))
    return sections


def render(sections: list[Section], *, version: str, repo: str) -> str:
    """Render ``sections`` as markdown.

    Args:
        sections: the sections to render.
        version: the version heading, for example ``v1.0.0``.
        repo: the ``owner/name`` slug used to build commit links.

    Returns:
        A markdown document ending in a newline.
    """
    lines = [f"## {version}", ""]
    if not sections:
        lines.extend(["No changes recorded.", ""])
        return "\n".join(lines)

    for section in sections:
        lines.append(f"### {section.title}")
        lines.append("")
        for entry in section.entries:
            description = entry.parsed.description or entry.subject
            if entry.parsed.scope:
                description = f"`{entry.parsed.scope}`: {description}"
            link = f"https://github.com/{repo}/commit/{entry.sha}"
            lines.append(f"- {description} ([{entry.short_sha}]({link}))")
        lines.append("")
    return "\n".join(lines)


def origin_slug(repo: Path) -> str | None:
    """Read the ``owner/name`` slug from the repository's ``origin`` remote.

    Args:
        repo: the repository to inspect.

    Returns:
        The slug, or ``None`` when there is no ``origin`` remote or its URL is
        not a GitHub URL.
    """
    completed = subprocess.run(
        ["git", "config", "--get", "remote.origin.url"],
        cwd=repo,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        return None

    url = completed.stdout.strip()
    match = re.search(r"github\.com[:/](?P<slug>[^/]+/[^/]+?)(?:\.git)?$", url)
    return match.group("slug") if match else None


def main(argv: list[str] | None = None) -> int:
    """Generate the changelog.

    Args:
        argv: command-line arguments; defaults to ``sys.argv[1:]``.

    Returns:
        The process exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."), help="repository to read")
    parser.add_argument("--version", default="Unreleased", help="version heading")
    parser.add_argument(
        "--revision",
        default="HEAD",
        help="revision range, for example v0.9.0..v1.0.0 (default: HEAD)",
    )
    parser.add_argument(
        "--repository-slug",
        default=None,
        help="owner/name for commit links (default: read from origin)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="write here instead of stdout",
    )
    args = parser.parse_args(argv)

    try:
        slug = args.repository_slug or origin_slug(args.repo) or "owner/repository"
        sections = group(collect(args.repo, args.revision))
    except ChangelogError as error:
        print(f"[changelog] ERROR: {error}", file=sys.stderr)
        return 2

    report = render(sections, version=args.version, repo=slug)
    if args.output is not None:
        args.output.write_text(report)
        print(f"[changelog] wrote {args.output}")
    else:
        print(report, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
