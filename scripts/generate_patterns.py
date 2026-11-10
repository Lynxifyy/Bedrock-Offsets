#!/usr/bin/env python3
"""Build ``patterns.json`` from a readable list of hex signatures.

The signature pack is what the Rust engine searches for, so it has to be easy
to extend and impossible to ship broken. The input is a small text file, one
signature per entry::

    # Lines starting with '#' and blank lines are ignored.
    frame_push_x29_x30 = FD 7B BF A9
    client_instance_update = 48 8B ?? ?? 89

The wildcard token ``??`` matches any byte. The output is the JSON document the
app reads from ``app/src/main/assets/patterns.json``, with entries sorted by
name so a regeneration produces a stable diff.

Exit codes:
    0  the pack was written.
    1  an entry was malformed.
    2  an input or output path could not be used.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

# A name is an identifier so it can appear in a JSON key and a log line.
NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

# A byte is either two hex digits or the wildcard token.
BYTE_PATTERN = re.compile(r"^(?:[0-9A-Fa-f]{2}|\?\?)$")

WILDCARD = "??"

# The Rust scanner refuses anything longer; fail here with a clearer message.
MAX_PATTERN_BYTES = 512


class PatternError(ValueError):
    """Raised when an entry cannot be turned into a signature."""


@dataclass(frozen=True)
class Entry:
    """One compiled signature.

    Attributes:
        name: identifier of the signature.
        pattern: normalised, space-separated byte string.
    """

    name: str
    pattern: str

    def as_json(self) -> dict[str, str]:
        """Render the entry in the shape the app expects."""
        return {"name": self.name, "pattern": self.pattern}


def normalise_pattern(name: str, raw: str) -> Entry:
    """Validate and normalise one signature.

    Args:
        name: identifier of the signature.
        raw: byte string, for example ``48 8b ??``.

    Returns:
        The normalised entry.

    Raises:
        PatternError: if the name is not an identifier, the byte list is
            empty, contains an unknown token, or is too long.
    """
    if not NAME_PATTERN.match(name):
        raise PatternError(
            f"{name!r} is not a valid name; use letters, digits and underscores"
        )

    tokens = raw.split()
    if not tokens:
        raise PatternError(f"{name} has no bytes")
    if len(tokens) > MAX_PATTERN_BYTES:
        raise PatternError(
            f"{name} has {len(tokens)} bytes, over the {MAX_PATTERN_BYTES} limit"
        )

    normalised: list[str] = []
    for token in tokens:
        if not BYTE_PATTERN.match(token):
            raise PatternError(
                f"{name} has an invalid token {token!r}; expected two hex digits or '??'"
            )
        normalised.append(token.upper() if token != WILDCARD else WILDCARD)

    return Entry(name=name, pattern=" ".join(normalised))


def parse_source(text: str) -> list[Entry]:
    """Parse a signature source file.

    Args:
        text: contents of the source file.

    Returns:
        Entries sorted by name.

    Raises:
        PatternError: if a line is malformed, a name repeats, or no entry is
            present.
    """
    entries: dict[str, Entry] = {}
    for line_number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in stripped:
            raise PatternError(f"line {line_number}: expected 'name = bytes'")
        name, _, raw = stripped.partition("=")
        try:
            entry = normalise_pattern(name.strip(), raw.strip())
        except PatternError as error:
            raise PatternError(f"line {line_number}: {error}") from error
        if entry.name in entries:
            raise PatternError(f"line {line_number}: {entry.name} is defined twice")
        entries[entry.name] = entry

    if not entries:
        raise PatternError("no signatures were defined")
    return sorted(entries.values(), key=lambda entry: entry.name)


def render(entries: list[Entry]) -> str:
    """Render entries as the JSON document the app reads.

    Args:
        entries: compiled signatures.

    Returns:
        Pretty-printed JSON ending in a newline.
    """
    document = {"patterns": [entry.as_json() for entry in entries]}
    return json.dumps(document, indent=2) + "\n"


def main(argv: list[str] | None = None) -> int:
    """Generate the signature pack.

    Args:
        argv: command-line arguments; defaults to ``sys.argv[1:]``.

    Returns:
        The process exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="signature source file")
    parser.add_argument(
        "output",
        type=Path,
        nargs="?",
        default=Path("app/src/main/assets/patterns.json"),
        help="destination JSON (default: app/src/main/assets/patterns.json)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the output is up to date instead of writing it",
    )
    args = parser.parse_args(argv)

    try:
        text = args.source.read_text(encoding="utf-8")
    except OSError as error:
        print(f"[generate_patterns] ERROR: cannot read {args.source}: {error}", file=sys.stderr)
        return 2

    try:
        rendered = render(parse_source(text))
    except PatternError as error:
        print(f"[generate_patterns] ERROR: {error}", file=sys.stderr)
        return 1

    if args.check:
        try:
            existing = args.output.read_text(encoding="utf-8")
        except OSError as error:
            print(
                f"[generate_patterns] ERROR: cannot read {args.output}: {error}",
                file=sys.stderr,
            )
            return 2
        if existing != rendered:
            print(
                f"[generate_patterns] ERROR: {args.output} is out of date; "
                "rerun without --check",
                file=sys.stderr,
            )
            return 1
        print(f"[generate_patterns] {args.output} is up to date")
        return 0

    try:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    except OSError as error:
        print(
            f"[generate_patterns] ERROR: cannot write {args.output}: {error}",
            file=sys.stderr,
        )
        return 2

    print(f"[generate_patterns] wrote {len(json.loads(rendered)['patterns'])} signatures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
