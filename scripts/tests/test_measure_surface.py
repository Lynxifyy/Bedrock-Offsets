"""Tests for scripts/measure_surface.py.

The script produces the numbers published in `docs/CARAMEL_26.60_SURFACE.md`,
so a silent regression in it would quietly change a measurement. The fixtures
are built by hand: a minimal ELF64 with a `.dynsym` and optionally a `.symtab`,
and a tiny NDJSON table with one row of each kind.
"""

from __future__ import annotations

import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts import measure_surface

STT_OBJECT = 1
STT_FUNC = 2
STB_GLOBAL = 1


def write_elf(path: Path, dynsym=(), symtab=()) -> Path:
    """Writes a 64-bit little-endian ELF with the requested symbol tables."""
    shstrtab = b"\0.dynsym\0.dynstr\0.symtab\0.shstrtab\0"
    dynstr = bytearray(b"\0")
    names = {}
    for text, _ in list(dynsym) + list(symtab):
        names[text] = len(dynstr)
        dynstr.extend(text.encode() + b"\0")

    def table(entries):
        body = bytearray(b"\0" * 24)
        for index, (text, info) in enumerate(entries, start=1):
            body.extend(struct.pack("<IBBHQQ", names[text], info, 0, index, 0x1000 * index, 8))
        return bytes(body)

    bodies = []
    sections = []

    def add(label: str, kind: int, body: bytes, entsize: int = 0) -> None:
        while len(bodies) and (64 + sum(len(b) for b in bodies)) % 8:
            bodies.append(b"\0")
        offset = 64 + sum(len(b) for b in bodies)
        bodies.append(body)
        sections.append((shstrtab.index(label.encode()), kind, 2, 0, offset, len(body), 0, 0, 8, entsize))

    add(".dynsym", 11, table(dynsym), 24)
    add(".dynstr", 3, bytes(dynstr))
    if symtab:
        add(".symtab", 2, table(symtab), 24)
    add(".shstrtab", 3, shstrtab)

    payload = b"".join(bodies)
    shoff = 64 + len(payload)
    while shoff % 8:
        shoff += 1
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = 2
    header[5] = 1
    struct.pack_into("<Q", header, 0x28, shoff)
    struct.pack_into("<HHH", header, 0x3A, 64, len(sections) + 1, len(sections))
    body = bytes(header) + payload
    body += b"\0" * (shoff - len(body))
    for fields in sections:
        body += struct.pack("<IIQQQQIIQQ", *fields)
    body += b"\0" * 64
    path.write_bytes(body)
    return path


class ReadElfSymbolsTest(unittest.TestCase):
    def test_counts_functions_and_objects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = write_elf(
                Path(directory) / "lib.so",
                dynsym=[
                    ("tick", (STB_GLOBAL << 4) | STT_FUNC),
                    ("state", (STB_GLOBAL << 4) | STT_OBJECT),
                ],
            )

            result = measure_surface.read_elf_symbols(str(library))

        self.assertEqual(result["dynsym"], 3, "null entry plus two symbols")
        self.assertEqual(result["dynsym_functions"], 1)
        self.assertEqual(result["dynsym_objects"], 1)
        self.assertFalse(result["symtab_present"])

    def test_reports_a_present_symtab(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = write_elf(
                Path(directory) / "lib.so",
                dynsym=[("tick", (STB_GLOBAL << 4) | STT_FUNC)],
                symtab=[("internal", (0 << 4) | STT_FUNC)],
            )

            result = measure_surface.read_elf_symbols(str(library))

        self.assertTrue(result["symtab_present"])
        self.assertEqual(result["symtab"], 2)

    def test_rejects_a_file_that_is_not_an_elf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "not-elf.so"
            path.write_bytes(b"not an elf")

            with self.assertRaises(SystemExit):
                measure_surface.read_elf_symbols(str(path))


class MeasureTest(unittest.TestCase):
    def test_counts_sources_and_keeps_slot_names_apart(self) -> None:
        rows = [
            {"type": "meta", "schemaVersion": 2},
            {
                "type": "class",
                "class": {
                    "name": "Player",
                    "slots": [{"name": "Player::vfunc_0"}],
                    "vtableGroups": [
                        {"slots": [{"name": "Player::vfunc_0"}]},
                        {
                            "slots": [
                                {"name": "Player::vfunc_0"},
                                {"name": "Player::vfunc_1"},
                            ]
                        },
                    ],
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "Player::vfunc_0",
                    "nameSource": "vtable",
                    "kind": "function",
                    "status": "verified",
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "Player::vfunc_1",
                    "nameSource": "vtable",
                    "kind": "thunk",
                    "status": "verified",
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "Player::tick",
                    "nameSource": "symbol",
                    "kind": "function",
                    "status": "verified",
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "sub_1000",
                    "nameSource": "auto",
                    "kind": "function",
                    "status": "fde_only",
                },
            },
            {"type": "symbol", "symbol": {"name": "g_state", "kind": "data"}},
        ]

        with tempfile.TemporaryDirectory() as directory:
            library = write_elf(
                Path(directory) / "lib.so",
                dynsym=[("tick", (STB_GLOBAL << 4) | STT_FUNC)],
            )
            table = Path(directory) / "table.ndjson"
            table.write_text(
                "\n".join(json.dumps(row, separators=(",", ":")) for row in rows) + "\n",
                encoding="utf-8",
            )

            result = measure_surface.measure(str(library), str(table))

        counts = result["table"]
        self.assertEqual(counts["functions"], 4)
        self.assertEqual(counts["functions_named_sub"], 1)
        self.assertEqual(counts["functions_named_after_a_vtable_slot"], 2)
        self.assertEqual(counts["functions_with_a_real_name"], 1)
        self.assertEqual(counts["classes"], 1)
        self.assertEqual(counts["vtable_slots"], 3, "all groups count, not only the primary")
        self.assertEqual(counts["vtable_slots_in_primary_group"], 1)
        self.assertEqual(counts["vtable_groups"], 2)
        self.assertEqual(counts["data_symbols"], {"data": 1})
        self.assertEqual(
            result["targets"]["entity_list"]["Player"],
            {
                "rtti_class": True,
                "functions_with_a_real_name": 1,
                "functions_named_after_a_slot": 2,
                "examples": ["Player::tick"],
            },
        )

    def test_a_missing_class_reports_zero_rather_than_a_key_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = write_elf(
                Path(directory) / "lib.so",
                dynsym=[("tick", (STB_GLOBAL << 4) | STT_FUNC)],
            )
            table = Path(directory) / "table.ndjson"
            table.write_text(json.dumps({"type": "meta", "schemaVersion": 2}) + "\n", encoding="utf-8")

            result = measure_surface.measure(str(library), str(table))

        self.assertEqual(result["table"]["classes"], 0)
        self.assertEqual(result["table"]["functions"], 0)
        self.assertFalse(result["targets"]["access_chain"]["Level"]["rtti_class"])


if __name__ == "__main__":
    unittest.main()
