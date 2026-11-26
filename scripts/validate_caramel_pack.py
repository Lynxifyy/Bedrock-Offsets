"""Validates a CaramelClient offset pack.

The checks are the ones a consumer depends on, so they are implemented here
instead of being delegated to a JSON-Schema library:

* the document matches ``caramel/schema.json``'s required shape;
* every item from ``caramel_items`` is present, so a gap can never be omitted;
* a ``found`` item carries an address and a confidence, a ``missing`` item
  carries neither and lists what was tried;
* no address is ``0x0``: a zero is the ELF header, never a real offset;
* the summary counts match the entries;
* an address is inside the library when ``--library`` is given.

Usage::

    python3 scripts/validate_caramel_pack.py caramel/offsets_26.60.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import caramel_items  # noqa: E402

HEX_PREFIX = "0x"


class PackError(Exception):
    """A pack that a consumer must not load."""


def _hex(value: object, field: str, name: str) -> int:
    if not isinstance(value, str) or not value.startswith(HEX_PREFIX):
        raise PackError(f"{name}: {field} must be a 0x-prefixed string, got {value!r}")
    try:
        return int(value, 16)
    except ValueError as error:
        raise PackError(f"{name}: {field} is not hex: {value!r}") from error


def validate(document: dict, library_size: int | None = None) -> None:
    """Raises [PackError] on the first problem."""
    for key in ("schemaVersion", "generatedBy", "version", "library", "groups", "summary"):
        if key not in document:
            raise PackError(f"missing top-level key {key}")
    if document["schemaVersion"] != 1:
        raise PackError(f"unsupported schemaVersion {document['schemaVersion']}")

    library = document["library"]
    digest = library.get("sha256", "")
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise PackError(f"library.sha256 is not a sha256: {digest!r}")
    size = library.get("size")
    if not isinstance(size, int) or size <= 0:
        raise PackError(f"library.size must be a positive integer, got {size!r}")
    if library_size is not None and size != library_size:
        raise PackError(f"library.size {size} does not match the library ({library_size})")

    groups = document["groups"]
    expected_groups = set(caramel_items.GROUPS)
    if set(groups) != expected_groups:
        raise PackError(f"groups {sorted(groups)} != {sorted(expected_groups)}")

    seen: set[tuple[str, str]] = set()
    counts = {"found": 0, "partial": 0, "missing": 0}
    confidence: dict[str, int] = {}
    for group, entries in groups.items():
        for entry in entries:
            name = entry.get("name", "<unnamed>")
            if entry.get("group") != group:
                raise PackError(f"{name}: group {entry.get('group')!r} != {group!r}")
            key = (group, name)
            if key in seen:
                raise PackError(f"{group}: duplicate item {name}")
            seen.add(key)

            status = entry.get("status")
            if status not in counts:
                raise PackError(f"{name}: unknown status {status!r}")
            counts[status] += 1
            level = entry.get("confidence")
            confidence[level] = confidence.get(level, 0) + 1

            if status == "found":
                _hex(entry.get("rva"), "rva", name)
                if level not in ("certain", "likely", "guess"):
                    raise PackError(f"{name}: found item has confidence {level!r}")
            if status == "missing":
                if entry.get("rva") is not None or entry.get("file_offset") is not None:
                    raise PackError(f"{name}: missing item must not carry an address")
                if level != "none":
                    raise PackError(f"{name}: missing item has confidence {level!r}")
                if not entry.get("tried"):
                    raise PackError(f"{name}: missing item must say what was tried")

            for field in ("rva", "file_offset"):
                value = entry.get(field)
                if value is None:
                    continue
                address = _hex(value, field, name)
                if address == 0:
                    raise PackError(f"{name}: {field} is 0x0, which is not an address")
                if library_size is not None and address >= library_size:
                    raise PackError(f"{name}: {field} {value} is past the end of the library")

    for group, items in caramel_items.GROUPS.items():
        for item in items:
            if (group, item["name"]) not in seen:
                raise PackError(f"{group}: item {item['name']} is not in the pack")

    summary = document["summary"]
    if summary.get("total") != sum(counts.values()):
        raise PackError(f"summary.total {summary.get('total')} != {sum(counts.values())}")
    for status, value in counts.items():
        if summary.get(status) != value:
            raise PackError(f"summary.{status} {summary.get(status)} != {value}")
    if summary.get("by_confidence") != confidence:
        raise PackError(
            f"summary.by_confidence {summary.get('by_confidence')} != {confidence}"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("--library", type=Path, help="check the addresses against this file")
    args = parser.parse_args(argv)

    document = json.loads(args.pack.read_text(encoding="utf-8"))
    size = args.library.stat().st_size if args.library else None
    try:
        validate(document, size)
    except PackError as error:
        print(f"{args.pack}: {error}", file=sys.stderr)
        return 1
    summary = document["summary"]
    print(
        f"{args.pack}: ok, {summary['total']} items "
        f"({summary['found']} found, {summary['partial']} partial, "
        f"{summary['missing']} missing)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
