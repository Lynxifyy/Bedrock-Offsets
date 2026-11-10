"""Tests for the changelog generator.

The tests build a real git repository in a temporary directory and run real git
commands against it, so the parsing and the grouping are exercised against git's
own output rather than a fixture that happens to look like it.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import changelog  # noqa: E402


def git(root: Path, *arguments: str) -> str:
    """Run a git command in ``root`` and return its stdout."""
    environment = dict(os.environ)
    environment.update(
        {
            "GIT_AUTHOR_NAME": "Test",
            "GIT_AUTHOR_EMAIL": "test@example.com",
            "GIT_COMMITTER_NAME": "Test",
            "GIT_COMMITTER_EMAIL": "test@example.com",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_SYSTEM": os.devnull,
        }
    )
    completed = subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
        env=environment,
    )
    return completed.stdout


class RepositoryTestCase(unittest.TestCase):
    """Base case that gives each test a throwaway repository."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        git(self.root, "init", "--initial-branch=main")
        git(self.root, "config", "commit.gpgsign", "false")

    def tearDown(self) -> None:
        self.directory.cleanup()

    def commit(self, message: str, *, file: str = "file.txt", content: str | None = None) -> str:
        """Create a commit with ``message`` and return its full SHA."""
        target = self.root / file
        target.write_text(content if content is not None else f"{message}\n")
        git(self.root, "add", file)
        git(self.root, "commit", "-m", message)
        return git(self.root, "rev-parse", "HEAD").strip()


class ParsingTests(unittest.TestCase):
    """The conventional-commit parser."""

    def test_a_feature_subject_is_split_into_type_and_text(self) -> None:
        parsed = changelog.parse_subject("feat: scan a queue of APKs one at a time")
        self.assertEqual("feat", parsed.type)
        self.assertEqual("scan a queue of APKs one at a time", parsed.description)
        self.assertIsNone(parsed.scope)

    def test_a_scope_is_captured(self) -> None:
        parsed = changelog.parse_subject("fix(cache): shred before unlinking")
        self.assertEqual("fix", parsed.type)
        self.assertEqual("cache", parsed.scope)
        self.assertEqual("shred before unlinking", parsed.description)

    def test_a_bang_marks_a_breaking_change(self) -> None:
        parsed = changelog.parse_subject("feat(api)!: drop the v1 document")
        self.assertTrue(parsed.breaking)
        self.assertEqual("drop the v1 document", parsed.description)

    def test_an_unconventional_subject_is_kept_as_other(self) -> None:
        parsed = changelog.parse_subject("Add greeting to Readme.md")
        self.assertEqual("other", parsed.type)
        self.assertEqual("Add greeting to Readme.md", parsed.description)
        self.assertFalse(parsed.breaking)

    def test_a_revert_is_its_own_type(self) -> None:
        parsed = changelog.parse_subject('Revert "feat: add a thing"')
        self.assertEqual("revert", parsed.type)


class GroupingTests(RepositoryTestCase):
    """Section assignment and ordering."""

    def test_commits_land_in_the_section_for_their_type(self) -> None:
        self.commit("feat: a feature")
        self.commit("fix: a fix")
        self.commit("perf: a speed-up")
        self.commit("docs: some prose")
        self.commit("build: some wiring")
        self.commit("chore: some housekeeping")

        entries = changelog.collect(self.root, "HEAD")
        sections = changelog.group(entries)

        self.assertEqual(
            ["Features", "Fixes", "Performance", "Documentation", "Build", "Other"],
            [section.title for section in sections],
        )

    def test_a_breaking_change_is_listed_under_breaking_changes(self) -> None:
        self.commit("feat!: change the document shape")

        sections = changelog.group(changelog.collect(self.root, "HEAD"))

        self.assertEqual("Breaking changes", sections[0].title)
        self.assertEqual(1, len(sections[0].entries))
        # The same commit is not repeated in Features.
        self.assertNotIn("Features", [section.title for section in sections])

    def test_a_breaking_change_footer_is_detected(self) -> None:
        self.commit("feat: change the shape\n\nBREAKING CHANGE: the offsets map moved")

        sections = changelog.group(changelog.collect(self.root, "HEAD"))

        self.assertEqual("Breaking changes", sections[0].title)

    def test_an_empty_type_section_is_omitted(self) -> None:
        self.commit("fix: only a fix")

        titles = [section.title for section in changelog.group(changelog.collect(self.root, "HEAD"))]

        self.assertEqual(["Fixes"], titles)

    def test_merges_are_skipped(self) -> None:
        self.commit("feat: a feature")
        git(self.root, "checkout", "-b", "side")
        self.commit("fix: a fix on the side", file="side.txt")
        git(self.root, "checkout", "main")
        self.commit("feat: another feature", file="other.txt")
        git(self.root, "merge", "--no-ff", "-m", "Merge branch 'side'", "side")

        entries = changelog.collect(self.root, "HEAD")

        self.assertNotIn("Merge branch 'side'", [entry.subject for entry in entries])

    def test_a_revision_range_is_honoured(self) -> None:
        self.commit("feat: first")
        marker = self.commit("feat: second")
        self.commit("fix: third")

        entries = changelog.collect(self.root, f"{marker}..HEAD")

        self.assertEqual(["fix: third"], [entry.subject for entry in entries])


class RenderingTests(unittest.TestCase):
    """The markdown writer."""

    def test_the_report_has_a_heading_per_section(self) -> None:
        sections = [
            changelog.Section(
                "Features",
                (
                    changelog.Entry(
                        subject="feat: a feature",
                        sha="abcdef1234567890",
                        body="",
                        parsed=changelog.parse_subject("feat: a feature"),
                    ),
                ),
            )
        ]

        text = changelog.render(sections, version="v1.0.0", repo="Lynxifyy/Bedrock-Offsets")

        self.assertIn("## v1.0.0", text)
        self.assertIn("### Features", text)
        self.assertIn("a feature", text)

    def test_the_short_sha_and_a_link_are_included(self) -> None:
        sections = [
            changelog.Section(
                "Fixes",
                (
                    changelog.Entry(
                        subject="fix: a fix",
                        sha="abcdef1234567890",
                        body="",
                        parsed=changelog.parse_subject("fix: a fix"),
                    ),
                ),
            )
        ]

        text = changelog.render(sections, version="v1.0.0", repo="Lynxifyy/Bedrock-Offsets")

        self.assertIn("abcdef1", text)
        self.assertIn("https://github.com/Lynxifyy/Bedrock-Offsets/commit/abcdef1234567890", text)

    def test_a_scope_is_shown_in_backticks(self) -> None:
        sections = [
            changelog.Section(
                "Fixes",
                (
                    changelog.Entry(
                        subject="fix(cache): shred before unlinking",
                        sha="abcdef1234567890",
                        body="",
                        parsed=changelog.parse_subject("fix(cache): shred before unlinking"),
                    ),
                ),
            )
        ]

        text = changelog.render(sections, version="v1.0.0", repo="Lynxifyy/Bedrock-Offsets")

        self.assertIn("`cache`: shred before unlinking", text)

    def test_no_sections_renders_a_placeholder(self) -> None:
        text = changelog.render([], version="v1.0.0", repo="Lynxifyy/Bedrock-Offsets")

        self.assertIn("## v1.0.0", text)
        self.assertIn("No changes recorded.", text)


class CliTests(RepositoryTestCase):
    """The command-line contract."""

    def test_the_report_is_written_to_the_output_file(self) -> None:
        self.commit("feat: a feature")
        output = self.root / "CHANGELOG.md"

        code = changelog.main(
            [
                "--repo",
                str(self.root),
                "--version",
                "v1.0.0",
                "--repository-slug",
                "Lynxifyy/Bedrock-Offsets",
                "--output",
                str(output),
            ]
        )

        self.assertEqual(0, code)
        self.assertIn("a feature", output.read_text())

    def test_an_unknown_revision_exits_two(self) -> None:
        self.commit("feat: a feature")

        code = changelog.main(["--repo", str(self.root), "--revision", "nope..HEAD"])

        self.assertEqual(2, code)

    def test_a_directory_that_is_not_a_repository_exits_two(self) -> None:
        with tempfile.TemporaryDirectory() as plain:
            code = changelog.main(["--repo", plain])

        self.assertEqual(2, code)

    def test_the_repository_slug_is_read_from_the_origin_remote(self) -> None:
        self.commit("feat: a feature")
        git(self.root, "remote", "add", "origin", "https://github.com/Lynxifyy/Bedrock-Offsets.git")

        slug = changelog.origin_slug(self.root)

        self.assertEqual("Lynxifyy/Bedrock-Offsets", slug)

    def test_a_repository_without_an_origin_has_no_slug(self) -> None:
        self.commit("feat: a feature")

        self.assertIsNone(changelog.origin_slug(self.root))


if __name__ == "__main__":
    unittest.main()
