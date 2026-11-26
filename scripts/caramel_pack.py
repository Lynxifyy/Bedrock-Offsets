"""Builds the CaramelClient offset pack for one Minecraft version.

The pack answers one question per item in ``caramel_items``: did this library
expose it, where is it, and how was it found. An item that could not be
resolved is written as ``status: "missing"`` with the attempts that failed, so a
gap is visible instead of absent.

Usage::

    python3 scripts/caramel_pack.py \\
        --library libminecraftpe.so \\
        --output-dir shard-out/categories \\
        --version 26.60 \\
        --out caramel/offsets_26.60.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import caramel_items  # noqa: E402

SCHEMA_VERSION = 1

RTTI_HOW = (
    "RTTI: Itanium type_info name in .data.rel.ro, vtable located through the "
    ".rela.dyn R_AARCH64_RELATIVE entries that point at its type_info slot"
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def load_index(output_dir: Path) -> tuple[dict, dict, int]:
    """Reads every category document once and keeps only what the pack needs.

    Returns ``(classes, functions, symbol_named)`` where ``functions`` maps a
    name to its record and ``symbol_named`` counts the rows that carry a
    demangled symbol name.
    """
    classes: dict[str, dict] = {}
    functions: dict[str, dict] = {}
    symbol_named = 0
    for path in sorted(output_dir.glob("*.json")):
        if path.name == "classmap.json":
            continue
        document = json.loads(path.read_text(encoding="utf-8"))
        for name, record in document.get("classes", {}).items():
            classes.setdefault(
                name,
                {
                    "vtable": record.get("vtable"),
                    "file_offset": record.get("fileOffset"),
                    "type_info": record.get("typeInfo"),
                    "slot_count": record.get("slotCount", 0),
                    "bases": record.get("bases", []),
                    "category": document.get("category"),
                },
            )
        for record in document.get("functions", []):
            name = record.get("name", "")
            if record.get("nameSource") == "symbol":
                symbol_named += 1
            functions.setdefault(
                name,
                {
                    "address": record.get("address"),
                    "file_offset": record.get("fileOffset"),
                    "status": record.get("status"),
                    "name_source": record.get("nameSource"),
                    "kind": record.get("kind"),
                },
            )
    return classes, functions, symbol_named


def _find_function(functions: dict, spellings: list[str]) -> tuple[str, dict] | None:
    for spelling in spellings:
        if spelling in functions:
            return spelling, functions[spelling]
    for spelling in spellings:
        method = spelling.rsplit("::", 1)[-1]
        for name, record in functions.items():
            if name == method or name.endswith("::" + method):
                return name, record
    return None


def _anchor_hits(library: bytes | None, method: str) -> int | None:
    if library is None:
        return None
    return library.count(method.encode())


def _class_entry(item: dict, classes: dict, anchors: bytes | None) -> dict:
    for spelling in item["spellings"]:
        record = classes.get(spelling)
        if record is None:
            continue
        entry = {
            "name": item["name"],
            "matched": spelling,
            "kind": item["kind"],
            "status": "found" if record["file_offset"] else "partial",
            "confidence": "certain",
            "rva": record["vtable"],
            "file_offset": record["file_offset"],
            "how_found": RTTI_HOW,
            "evidence": (
                f"class name from type_info at {record['type_info']}; "
                f"{record['slot_count']} slots; "
                f"bases: {', '.join(record['bases']) or 'none'}"
            ),
            "why": item["why"],
        }
        if not record["file_offset"]:
            entry["note"] = "the vtable has no bytes in the file, so it has no file offset"
        return entry

    tried = [
        f"RTTI: no class named {spelling} among the {len(classes)} classes the engine resolved"
        for spelling in item["spellings"]
    ]
    for spelling in item["spellings"]:
        hits = _anchor_hits(anchors, spelling)
        if hits is not None:
            tried.append(f"string anchor: {spelling!r} occurs {hits} times in the library")
    return _missing(item, tried)


def _function_entry(
    item: dict, classes: dict, functions: dict, symbol_named: int, anchors: bytes | None
) -> dict:
    found = _find_function(functions, item["spellings"])
    if found is not None:
        name, record = found
        return {
            "name": item["name"],
            "matched": name,
            "kind": item["kind"],
            "status": "found" if record["file_offset"] else "partial",
            "confidence": "certain" if name in item["spellings"] else "likely",
            "rva": record["address"],
            "file_offset": record["file_offset"],
            "how_found": f"symbol: demangled name {name}",
            "evidence": f"row status {record['status']}, kind {record['kind']}",
            "why": item["why"],
        }

    owner = item["spellings"][0].rsplit("::", 1)[0]
    tried = [
        f"symbols: {symbol_named} functions carry a demangled name and none of "
        f"{item['spellings']} match",
    ]
    if owner in classes:
        record = classes[owner]
        tried.append(
            f"vtable slots: {owner} has {record['slot_count']} slots and every one "
            f"is named {owner}::vfunc_N, so the slot number is known but its "
            f"meaning is not"
        )
    else:
        tried.append(f"vtable slots: no RTTI class named {owner}")
    for spelling in item["spellings"]:
        method = spelling.rsplit("::", 1)[-1]
        hits = _anchor_hits(anchors, method)
        if hits is not None:
            tried.append(f"string anchor: {method!r} occurs {hits} times in the library")
    return _missing(item, tried)


def _missing(item: dict, tried: list[str]) -> dict:
    return {
        "name": item["name"],
        "kind": item["kind"],
        "status": "missing",
        "confidence": "none",
        "rva": None,
        "file_offset": None,
        "how_found": None,
        "evidence": None,
        "why": item["why"],
        "tried": tried,
    }


def build(
    library: Path | None,
    output_dir: Path,
    version: str,
    engine_version: str,
    read_anchors: bool = True,
) -> dict:
    """Builds the pack document."""
    classes, functions, symbol_named = load_index(output_dir)
    anchors = library.read_bytes() if (library and read_anchors) else None

    groups: dict[str, list[dict]] = {}
    for group, items in caramel_items.GROUPS.items():
        entries = []
        for item in items:
            if item["kind"] == "class_vtable":
                entry = _class_entry(item, classes, anchors)
            else:
                entry = _function_entry(item, classes, functions, symbol_named, anchors)
            entry["group"] = group
            entries.append(entry)
        groups[group] = entries

    summary = {"total": 0, "found": 0, "partial": 0, "missing": 0, "by_confidence": {}}
    for entries in groups.values():
        for entry in entries:
            summary["total"] += 1
            summary[entry["status"]] += 1
            confidence = entry["confidence"]
            summary["by_confidence"][confidence] = summary["by_confidence"].get(confidence, 0) + 1

    document = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedBy": "scripts/caramel_pack.py",
        "version": version,
        "engineVersion": engine_version,
        "library": {
            "sha256": _sha256(library) if library else "",
            "size": library.stat().st_size if library else 0,
            "arch": "aarch64",
        },
        "groups": groups,
        "summary": summary,
    }
    return document


def write(document: dict, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--engine-version", default="")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--no-anchors",
        action="store_true",
        help="skip the string-anchor scan of the library (slow on a 300 MB file)",
    )
    args = parser.parse_args(argv)

    document = build(
        args.library,
        args.output_dir,
        args.version,
        args.engine_version,
        read_anchors=not args.no_anchors,
    )
    write(document, args.out)
    summary = document["summary"]
    print(
        f"{args.out}: {summary['total']} items, "
        f"{summary['found']} found, {summary['partial']} partial, "
        f"{summary['missing']} missing"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
