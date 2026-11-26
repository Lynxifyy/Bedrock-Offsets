#!/usr/bin/env python3
"""Measures what a real library says its load base is, and proves the rows agree.

Written because the environment has no desktop tools: the owner has a tablet,
so every claim about the real `libminecraftpe.so` has to be produced here, by
code in this repository, and be reproducible by anyone.

Two questions, both answered from the bytes rather than from a comment:

1. Which base does the library itself imply? The ELF header names the segments;
   the lowest `PT_LOAD` virtual address is the address the first byte of the
   mapped image gets, so it is the base every relative offset is measured from.
   The lowest allocated *section* is a different, later number (the header area
   sits below it), and using it shifts every RVA.

2. Do the produced rows agree with that base? An auto-generated name is
   `sub_<rva>`, and a class's `vtableOffset` is `vtable - base`. If the rows were
   written against the wrong base, the names are off by exactly the difference,
   which this script detects without trusting either number.

Usage:
    python3 scripts/verify_load_base.py --library libminecraftpe.so --table out/

`--table` accepts a directory of category documents or a single table file.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

PT_LOAD = 1
SHT_NOBITS = 8
SHF_ALLOC = 0x2
ELFCLASS64 = 2
EM_AARCH64 = 183

#: Function rows as the engine writes them.
FUNCTION_ROW = re.compile(
    rb'"address":"0x([0-9a-f]+)","fileOffset":(?:null|"[^"]*"),"size":(\d+),"name":"([^"]*)"'
)
#: Class headers as the engine writes them.
CLASS_ROW = re.compile(
    rb'"vtable":"0x([0-9a-f]+)","fileOffset":(?:null|"[^"]*"),"vtableOffset":"0x([0-9a-f]+)"'
)
#: Bytes carried over between blocks so a row straddling a boundary is seen.
OVERLAP = 512


@dataclass
class Layout:
    """The two candidate bases and the section they were read from."""

    load_base: int | None
    first_load_offset: int | None
    section_base: int | None
    section_base_name: str


def read_layout(path: Path) -> Layout:
    """Reads the ELF program and section headers with no external library."""
    with path.open("rb") as handle:
        header = handle.read(64)
        if header[:4] != b"\x7fELF":
            raise SystemExit(f"{path} is not an ELF file")
        if header[4] != ELFCLASS64:
            raise SystemExit("only 64-bit libraries are supported")
        (machine,) = struct.unpack_from("<H", header, 18)
        if machine != EM_AARCH64:
            raise SystemExit(f"expected AArch64, found machine {machine}")
        (phoff,) = struct.unpack_from("<Q", header, 32)
        (shoff,) = struct.unpack_from("<Q", header, 40)
        (phentsize,) = struct.unpack_from("<H", header, 54)
        (phnum,) = struct.unpack_from("<H", header, 56)
        (shentsize,) = struct.unpack_from("<H", header, 58)
        (shnum,) = struct.unpack_from("<H", header, 60)
        (shstrndx,) = struct.unpack_from("<H", header, 62)

        loads: list[tuple[int, int]] = []
        for index in range(phnum):
            handle.seek(phoff + index * phentsize)
            entry = handle.read(56)
            (kind, _flags, offset, vaddr, _paddr, filesz, _memsz, _align) = (
                struct.unpack_from("<IIQQQQQQ", entry)
            )
            if kind == PT_LOAD:
                loads.append((vaddr, offset))

        sections: list[tuple[int, int, str]] = []
        names = b""
        if shoff and shnum:
            handle.seek(shoff + shstrndx * shentsize)
            strtab = handle.read(shentsize)
            (name_offset,) = struct.unpack_from("<I", strtab, 0)
            (strtab_offset,) = struct.unpack_from("<Q", strtab, 24)
            (strtab_size,) = struct.unpack_from("<Q", strtab, 32)
            handle.seek(strtab_offset)
            names = handle.read(strtab_size)
            for index in range(shnum):
                handle.seek(shoff + index * shentsize)
                entry = handle.read(shentsize)
                (name_index, kind, flags, address) = struct.unpack_from("<IIQQ", entry)
                if kind == SHT_NOBITS or not flags & SHF_ALLOC or address == 0:
                    continue
                end = names.find(b"\0", name_index)
                sections.append((address, index, names[name_index:end].decode()))

    first_section = min(sections) if sections else None
    return Layout(
        load_base=min(loads)[0] if loads else None,
        first_load_offset=min(loads)[1] if loads else None,
        section_base=first_section[0] if first_section else None,
        section_base_name=first_section[2] if first_section else "",
    )


def iter_rows(path: Path):
    """Yields `(address, size, name)` for every function row in a document."""
    pattern = FUNCTION_ROW
    with path.open("rb") as handle:
        carry = b""
        while True:
            block = handle.read(8 << 20)
            if not block:
                break
            data = carry + block
            for match in pattern.finditer(data):
                yield int(match.group(1), 16), int(match.group(2)), match.group(3).decode()
            carry = data[-OVERLAP:]


def iter_classes(path: Path):
    """Yields `(vtable, vtable_offset)` for every class header in a document."""
    pattern = CLASS_ROW
    with path.open("rb") as handle:
        carry = b""
        while True:
            block = handle.read(8 << 20)
            if not block:
                break
            data = carry + block
            for match in pattern.finditer(data):
                yield int(match.group(1), 16), int(match.group(2), 16)
            carry = data[-OVERLAP:]


def documents(table: Path) -> list[Path]:
    """Returns every JSON document to read: one file or a directory's files."""
    if table.is_dir():
        return sorted(table.glob("*.json"))
    return [table]


def measure(root: Path) -> dict[str, object]:
    """Counts the rows and checks them against both candidate bases."""
    totals = {
        "functions": 0,
        "auto_named": 0,
        "auto_names_matching_load_base": 0,
        "auto_names_matching_section_base": 0,
        "auto_names_matching_neither": 0,
        "classes": 0,
        "vtable_offsets_matching_load_base": 0,
        "vtable_offsets_matching_section_base": 0,
        "vtable_offsets_matching_neither": 0,
    }
    for path in documents(root):
        for address, _size, name in iter_rows(path):
            totals["functions"] += 1
            if not name.startswith("sub_"):
                continue
            try:
                rva = int(name[4:], 16)
            except ValueError:
                continue
            totals["auto_named"] += 1
            # `sub_<rva>` carries the address minus the base, so the base the
            # writer used is recoverable from any single row.
            base = address - rva
            if BASE["load"] is not None and base == BASE["load"]:
                totals["auto_names_matching_load_base"] += 1
            elif BASE["section"] is not None and base == BASE["section"]:
                totals["auto_names_matching_section_base"] += 1
            else:
                totals["auto_names_matching_neither"] += 1
        for vtable, vtable_offset in iter_classes(path):
            totals["classes"] += 1
            if BASE["load"] is not None and vtable_offset == vtable - BASE["load"]:
                totals["vtable_offsets_matching_load_base"] += 1
            elif BASE["section"] is not None and vtable_offset == vtable - BASE["section"]:
                totals["vtable_offsets_matching_section_base"] += 1
            else:
                totals["vtable_offsets_matching_neither"] += 1
    return totals


BASE: dict[str, int | None] = {"load": None, "section": None}


def main(argv: list[str]) -> int:
    """Runs the measurement and prints a report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--table", type=Path, action="append", default=[])
    parser.add_argument("--json", type=Path, default=None, help="also write the counts here")
    arguments = parser.parse_args(argv)

    layout = read_layout(arguments.library)
    BASE["load"] = layout.load_base
    BASE["section"] = layout.section_base

    print(f"library            {arguments.library} ({arguments.library.stat().st_size:,} bytes)")
    print(
        "first PT_LOAD      "
        + (
            f"vaddr=0x{layout.load_base:x} p_offset=0x{layout.first_load_offset:x}"
            if layout.load_base is not None
            else "absent (not a loadable library)"
        )
    )
    print(
        "first section      "
        + (
            f"addr=0x{layout.section_base:x} ({layout.section_base_name})"
            if layout.section_base is not None
            else "none"
        )
    )
    if layout.load_base is not None and layout.section_base is not None:
        shift = layout.section_base - layout.load_base
        print(f"the two disagree   by 0x{shift:x} ({shift:,} bytes)")
        print(
            "the load base is the correct one: it is where the kernel maps byte 0,\n"
            "and `PT_LOAD p_offset=0` places the ELF header there."
        )

    result: dict[str, object] = {
        "library": str(arguments.library),
        "size": arguments.library.stat().st_size,
        "load_base": layout.load_base,
        "first_load_offset": layout.first_load_offset,
        "section_base": layout.section_base,
        "section_base_name": layout.section_base_name,
    }
    for table in arguments.table:
        totals = measure(table)
        print(f"\ntable              {table}")
        for key, value in totals.items():
            print(f"  {key:38} {value:,}")
        result[str(table)] = totals

    if arguments.json:
        arguments.json.parent.mkdir(parents=True, exist_ok=True)
        arguments.json.write_text(json.dumps(result, indent=2) + "\n")
        print(f"\nwritten            {arguments.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
