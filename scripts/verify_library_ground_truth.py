"""Cross-checks the engine's RTTI output against the library's own relocations.

The 26.60 library is linked with `RELA` relocations, so the bytes of a vtable are
zero in the file and the real pointers live in `.rela.dyn` as addends. That makes
the relocation table an independent source of truth for two claims the engine
makes:

* the vtable of a class starts at `vtable`, and `vtable + 8` holds a pointer to
  its `type_info`;
* the slot at `vtable + 16 + 8 * slot` holds the address the engine reports.

It also counts the FDEs in `.eh_frame_hdr`, which is what `functions` should
equal.

Usage::

    python3 scripts/verify_library_ground_truth.py \\
        --library libminecraftpe.so --output-dir shard-out/categories
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
SHT_NOBITS = 8
SHF_ALLOC = 0x2
SHF_EXECINSTR = 0x4
R_AARCH64_RELATIVE = 1027


def _u64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


class Library:
    """Just enough ELF to answer the questions below."""

    def __init__(self, path: Path) -> None:
        self.data = path.read_bytes()
        if self.data[:4] != ELF_MAGIC:
            raise SystemExit(f"{path} is not an ELF file")
        if self.data[4] != 2:
            raise SystemExit("only 64-bit ELF is supported")
        self.sections = self._sections()
        self.relocations = self._relocations()

    def _sections(self) -> list[dict]:
        data = self.data
        shoff = _u64(data, 0x28)
        shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
        headers = [
            struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shentsize)
            for index in range(shnum)
        ]
        names = headers[shstrndx]
        table = data[names[4] : names[4] + names[5]]

        def name(offset: int) -> str:
            end = table.find(b"\0", offset)
            return table[offset:end].decode()

        return [
            {
                "name": name(header[0]),
                "type": header[1],
                "flags": header[2],
                "address": header[3],
                "offset": header[4],
                "size": header[5],
            }
            for header in headers
        ]

    def section(self, name: str) -> dict:
        for section in self.sections:
            if section["name"] == name:
                return section
        raise SystemExit(f"no section {name}")

    def _relocations(self) -> dict[int, int]:
        rela = self.section(".rela.dyn")
        count = rela["size"] // 24
        out: dict[int, int] = {}
        for index in range(count):
            offset, info, addend = struct.unpack_from(
                "<QQq", self.data, rela["offset"] + index * 24
            )
            if info & 0xFFFFFFFF == R_AARCH64_RELATIVE:
                out[offset] = addend & 0xFFFFFFFFFFFFFFFF
        return out

    def fde_count(self) -> int:
        """The FDE count from `.eh_frame_hdr`, parsed independently."""
        header = self.section(".eh_frame_hdr")
        raw = self.data[header["offset"] : header["offset"] + header["size"]]
        count = struct.unpack_from("<I", raw, 8)[0]
        return count

    def executable(self, address: int) -> bool:
        return any(
            section["flags"] & SHF_EXECINSTR
            and section["type"] != SHT_NOBITS
            and section["address"] <= address < section["address"] + section["size"]
            for section in self.sections
            if section["flags"] & SHF_ALLOC
        )


def check(library: Library, output_dir: Path) -> int:
    """Prints the checks and returns the number of failures."""
    classes = 0
    type_ok = type_bad = type_missing = 0
    slots = 0
    slot_ok = slot_bad = slot_missing = 0
    functions = 0
    non_code = 0
    for path in sorted(output_dir.glob("*.json")):
        if path.name == "classmap.json":
            continue
        document = json.loads(path.read_text(encoding="utf-8"))
        for name, record in document.get("classes", {}).items():
            classes += 1
            vtable = int(record["vtable"], 16)
            expected = int(record["typeInfo"], 16)
            found = library.relocations.get(vtable + 8)
            if found is None:
                type_missing += 1
                print(f"  {name}: no relocation at vtable+8 ({vtable + 8:#x})")
            elif found == expected:
                type_ok += 1
            else:
                type_bad += 1
                print(f"  {name}: type_info {expected:#x} != addend {found:#x}")
            for slot in record.get("slots", []):
                address = int(slot["address"], 16)
                if slot["kind"] != "virtual" or address == 0:
                    continue
                slots += 1
                found = library.relocations.get(vtable + 16 + 8 * slot["slot"])
                if found is None:
                    slot_missing += 1
                    print(f"  {name} slot {slot['slot']}: no relocation")
                elif found == address:
                    slot_ok += 1
                else:
                    slot_bad += 1
                    print(f"  {name} slot {slot['slot']}: {address:#x} != {found:#x}")
        for function in document.get("functions", []):
            functions += 1
            if not library.executable(int(function["address"], 16)):
                non_code += 1
                print(f"  {function['name']}: {function['address']} is not code")

    fdes = library.fde_count()
    print(f"classes: {classes}, type_info slot ok={type_ok} wrong={type_bad} missing={type_missing}")
    print(f"virtual slots: {slots}, ok={slot_ok} wrong={slot_bad} missing={slot_missing}")
    print(f"functions: {functions}, outside .text: {non_code}")
    print(f"fdes in .eh_frame_hdr: {fdes}, difference from functions: {functions - fdes}")
    return type_bad + type_missing + slot_bad + slot_missing + non_code + abs(functions - fdes)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    failures = check(Library(args.library), args.output_dir)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
