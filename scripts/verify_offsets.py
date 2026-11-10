#!/usr/bin/env python3
"""Compare two exported offset documents and report what changed.

The tool reads two JSON files produced by the app (``offsets_<BuildID>.json``)
and prints a diff of the ``offsets`` maps, so a signature pack can be checked
against a new Minecraft build before it ships.

Exit codes:
    0  the two documents agree on every offset.
    1  at least one offset was added, removed or changed.
    2  a document could not be read or does not look like an offset export.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path


class VerificationError(RuntimeError):
    """Raised when an input document cannot be used."""


@dataclass(frozen=True)
class Difference:
    """One offset that differs between the two documents.

    Attributes:
        key: offset key, for example ``pattern:frame_push_x29_x30``.
        before: value in the older document, or ``None`` when absent.
        after: value in the newer document, or ``None`` when absent.
    """

    key: str
    before: str | None
    after: str | None

    @property
    def kind(self) -> str:
        """Classify the difference as ``added``, ``removed`` or ``changed``."""
        if self.before is None:
            return "added"
        if self.after is None:
            return "removed"
        return "changed"


def load_document(path: Path) -> dict:
    """Read an offset export.

    Args:
        path: file to read.

    Returns:
        The parsed document.

    Raises:
        VerificationError: if the file is missing, is not JSON, or has no
            ``offsets`` object.
    """
    if not path.is_file():
        raise VerificationError(f"{path} does not exist")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise VerificationError(f"{path} is not valid JSON: {error}") from error
    if not isinstance(document, dict) or not isinstance(document.get("offsets"), dict):
        raise VerificationError(f"{path} has no 'offsets' object")
    return document


def diff_offsets(before: dict, after: dict) -> list[Difference]:
    """Compute the differences between two ``offsets`` maps.

    Args:
        before: older ``offsets`` map.
        after: newer ``offsets`` map.

    Returns:
        Differences sorted by key, so the output is stable.
    """
    differences: list[Difference] = []
    for key in sorted(set(before) | set(after)):
        old = before.get(key)
        new = after.get(key)
        if old != new:
            differences.append(Difference(key=key, before=old, after=new))
    return differences


def summarise(differences: list[Difference]) -> dict[str, int]:
    """Count differences by kind.

    Args:
        differences: the diff to count.

    Returns:
        A mapping of kind to count.
    """
    counts = {"added": 0, "removed": 0, "changed": 0}
    for difference in differences:
        counts[difference.kind] += 1
    return counts


def format_report(before: dict, after: dict, differences: list[Difference]) -> str:
    """Render a human-readable diff.

    Args:
        before: older document, used for the identity header.
        after: newer document, used for the identity header.
        differences: the diff to render.

    Returns:
        A multi-line report ending in a newline.
    """
    before_id = before.get("source", {}).get("buildId", "unknown")
    after_id = after.get("source", {}).get("buildId", "unknown")
    lines = [f"build {before_id} -> {after_id}"]

    if not differences:
        lines.append("every offset matches.")
        return "\n".join(lines) + "\n"

    counts = summarise(differences)
    lines.append(
        f"{len(differences)} differences: "
        f"{counts['added']} added, {counts['removed']} removed, {counts['changed']} changed"
    )
    for difference in differences:
        if difference.kind == "added":
            lines.append(f"  + {difference.key} = {difference.after}")
        elif difference.kind == "removed":
            lines.append(f"  - {difference.key} (was {difference.before})")
        else:
            lines.append(f"  ~ {difference.key}: {difference.before} -> {difference.after}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    """Run the comparison and report differences.

    Args:
        argv: command-line arguments; defaults to ``sys.argv[1:]``.

    Returns:
        The process exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path, help="older offsets JSON")
    parser.add_argument("after", type=Path, help="newer offsets JSON")
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="print nothing on success",
    )
    args = parser.parse_args(argv)

    try:
        before = load_document(args.before)
        after = load_document(args.after)
    except VerificationError as error:
        print(f"[verify_offsets] ERROR: {error}", file=sys.stderr)
        return 2

    differences = diff_offsets(before["offsets"], after["offsets"])
    if not differences:
        if not args.quiet:
            print("[verify_offsets] every offset matches.")
        return 0

    print(format_report(before, after, differences), end="")
    return 1


if __name__ == "__main__":
    sys.exit(main())
