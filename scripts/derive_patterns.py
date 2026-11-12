#!/usr/bin/env python3
"""Derive a signature pack from a real ``libminecraftpe.so``.

The shipped pack has to answer one question per entry: *which function is
this?* A signature named after a real exported symbol can be checked against
the library's own symbol table, so its offset is reported as ``VERIFIED`` and
the confidence score means something. A signature named after an instruction
shape (``frame_push_x29_x30``) matches thousands of places, cannot be verified,
and is what made every offset read ``UNVERIFIED``.

So the pack is derived from the library rather than guessed:

1. Parse ``.dynsym`` / ``.dynstr`` and keep the defined ``Java_*`` entry points,
   which are the functions Bedrock actually exports to the JVM.
2. For each, take the bytes at its address in ``.text`` and grow the signature
   until it occurs exactly once in the section. A unique signature finds one
   address, so a match is a fact rather than a guess.
3. Emit the result in ``patterns.txt`` format for ``generate_patterns.py``.

Exit codes:
    0  the pack was written.
    1  the library could not be parsed or yielded no signatures.
    2  an input or output path could not be used.
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

# A name is an identifier so it can appear in a JSON key and a log line.
NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

# The prefix a JVM-exported native function carries.
JNI_PREFIX = "Java_"

# Signatures are a whole number of instructions; four bytes is one AArch64
# instruction, so lengths step by four.
MIN_SIGNATURE_BYTES = 8
MAX_SIGNATURE_BYTES = 64
SIGNATURE_STEP = 4

# ELF constants used to find the tables without pulling in a dependency.
ELF_MAGIC = b"\x7fELF"
EM_AARCH64 = 0xB7
SHT_SYMTAB = 2
SHT_DYNSYM = 11
STB_GLOBAL = 1
STB_WEAK = 2
SHN_UNDEF = 0

SECTION_HEADER = struct.Struct("<IIQQQQIIQQ")
SYMBOL = struct.Struct("<IBBHQQ")


class DerivationError(ValueError):
    """Raised when the library cannot yield a usable pack."""


@dataclass(frozen=True)
class Entry:
    """One derived signature.

    Attributes:
        name: the exported symbol the signature belongs to.
        pattern: space-separated hex bytes.
        address: virtual address of the symbol, for reporting.
    """

    name: str
    pattern: str
    address: int


def _read_section_headers(blob: bytes) -> tuple[list[dict], bytes]:
    """Reads the ELF section headers and the section-name string table.

    Args:
        blob: the whole file.

    Returns:
        The section headers and the bytes of ``.shstrtab``.

    Raises:
        DerivationError: if the file is not a 64-bit little-endian AArch64 ELF
            with a readable section table.
    """
    if blob[:4] != ELF_MAGIC:
        raise DerivationError("not an ELF file")
    if blob[4] != 2:
        raise DerivationError("not a 64-bit ELF")
    if blob[5] != 1:
        raise DerivationError("not a little-endian ELF")
    (machine,) = struct.unpack_from("<H", blob, 18)
    if machine != EM_AARCH64:
        raise DerivationError(f"not an AArch64 library (e_machine={machine:#x})")

    (shoff,) = struct.unpack_from("<Q", blob, 40)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", blob, 58)
    if shoff == 0 or shnum == 0:
        raise DerivationError("the section table is missing")
    if shentsize < SECTION_HEADER.size:
        raise DerivationError("the section header size is too small")

    headers = []
    for index in range(shnum):
        offset = shoff + index * shentsize
        fields = SECTION_HEADER.unpack_from(blob, offset)
        headers.append(
            {
                "name": fields[0],
                "type": fields[1],
                "flags": fields[2],
                "addr": fields[3],
                "offset": fields[4],
                "size": fields[5],
                "link": fields[6],
                "entsize": fields[9],
            }
        )

    if shstrndx >= len(headers):
        raise DerivationError("the section-name table index is out of range")
    names = headers[shstrndx]
    shstrtab = blob[names["offset"] : names["offset"] + names["size"]]
    return headers, shstrtab


def _section_name(shstrtab: bytes, offset: int) -> str:
    """Reads one NUL-terminated section name."""
    end = shstrtab.find(b"\x00", offset)
    return shstrtab[offset:end].decode("utf-8", "replace")


def _find(headers: list[dict], shstrtab: bytes, wanted: str) -> dict:
    """Finds a section by name.

    Raises:
        DerivationError: when the section is absent.
    """
    for header in headers:
        if _section_name(shstrtab, header["name"]) == wanted:
            return header
    raise DerivationError(f"the library has no {wanted} section")


def _symbol_name(dynstr: bytes, offset: int) -> str:
    """Reads one NUL-terminated symbol name."""
    end = dynstr.find(b"\x00", offset)
    return dynstr[offset:end].decode("utf-8", "replace")


def _count_occurrences(haystack: bytes, needle: bytes) -> int:
    """Counts every occurrence of ``needle``, including overlapping ones.

    ``bytes.count`` skips overlaps, so ``b"AAAA".count(b"AAA")`` is 1 even
    though the sequence occurs at two offsets. The scanner reports overlapping
    matches, so uniqueness has to be measured the same way.

    Args:
        haystack: the bytes to search.
        needle: the non-empty pattern to look for.

    Returns:
        The number of starting offsets where ``needle`` occurs.
    """
    count = 0
    start = haystack.find(needle)
    while start != -1:
        count += 1
        start = haystack.find(needle, start + 1)
    return count


def _shortest_unique_signature(text: bytes, start: int) -> int | None:
    """Finds the shortest instruction-aligned signature unique in ``text``.

    Args:
        text: the ``.text`` bytes.
        start: offset of the function inside ``text``.

    Returns:
        A length in bytes, or ``None`` when no length up to
        ``MAX_SIGNATURE_BYTES`` is unique.
    """
    available = len(text) - start
    limit = min(MAX_SIGNATURE_BYTES, available)
    for length in range(MIN_SIGNATURE_BYTES, limit + 1, SIGNATURE_STEP):
        probe = text[start : start + length]
        if _count_occurrences(text, probe) == 1:
            return length
    return None


def derive_entries(blob: bytes) -> list[Entry]:
    """Derives a signature pack from a library image.

    Args:
        blob: the whole ``.so`` file.

    Returns:
        Entries sorted by name, each with a signature unique in ``.text``.

    Raises:
        DerivationError: if the library cannot be parsed or yields nothing.
    """
    headers, shstrtab = _read_section_headers(blob)

    text = _find(headers, shstrtab, ".text")
    dynsym = _find(headers, shstrtab, ".dynsym")
    if dynsym["type"] not in (SHT_DYNSYM, SHT_SYMTAB):
        raise DerivationError("the dynamic symbol table has an unexpected type")
    dynstr = headers[dynsym["link"]]

    text_bytes = blob[text["offset"] : text["offset"] + text["size"]]
    if not text_bytes:
        raise DerivationError("the .text section is empty")
    strings = blob[dynstr["offset"] : dynstr["offset"] + dynstr["size"]]

    count = dynsym["size"] // SYMBOL.size
    by_name: dict[str, Entry] = {}
    seen_signatures: dict[str, str] = {}
    for index in range(count):
        fields = SYMBOL.unpack_from(blob, dynsym["offset"] + index * SYMBOL.size)
        name_offset, info, _other, shndx, value, _size = fields
        if shndx == SHN_UNDEF:
            continue
        binding = (info >> 4) & 0xF
        if binding not in (STB_GLOBAL, STB_WEAK):
            continue
        name = _symbol_name(strings, name_offset)
        if not name.startswith(JNI_PREFIX) or not NAME_PATTERN.match(name):
            continue

        # `text_bytes` starts at `.text`'s virtual address, so the index of a
        # symbol is its address minus that base. `delta` converts a *file*
        # offset to a virtual address, which is the other direction; using it
        # here is what put every signature 0x6834c80 bytes into the section.
        start = value - text["addr"]
        if start < 0 or start >= len(text_bytes):
            continue
        length = _shortest_unique_signature(text_bytes, start)
        if length is None:
            continue

        pattern = " ".join(f"{byte:02X}" for byte in text_bytes[start : start + length])
        # Two symbols can share a body (an alias); the signature identifies the
        # code, so the alphabetically first name keeps it.
        if pattern in seen_signatures:
            continue
        seen_signatures[pattern] = name
        by_name[name] = Entry(name=name, pattern=pattern, address=value)

    if not by_name:
        raise DerivationError("the library yielded no unique JNI signatures")
    return sorted(by_name.values(), key=lambda entry: entry.name)


def render(entries: list[Entry]) -> str:
    """Renders entries as a ``patterns.txt`` source.

    Args:
        entries: derived signatures.

    Returns:
        A source file ending in a newline.
    """
    lines = [
        "# Signature pack source for scripts/generate_patterns.py.",
        "#",
        "# Derived from a real libminecraftpe.so by scripts/derive_patterns.py,",
        "# not written by hand: every name is an exported JNI entry point and",
        "# every signature is the shortest byte string unique in .text, so a",
        "# match is one address and can be checked against the symbol table.",
        "#",
        "# Regenerate with:  python3 scripts/derive_patterns.py <libminecraftpe.so>",
        "# Then:             python3 scripts/generate_patterns.py patterns.txt",
        "",
    ]
    lines.extend(f"{entry.name} = {entry.pattern}" for entry in entries)
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    """Derive the pack and write the signature source.

    Args:
        argv: command-line arguments; defaults to ``sys.argv[1:]``.

    Returns:
        The process exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library", type=Path, help="path to libminecraftpe.so")
    parser.add_argument(
        "output",
        type=Path,
        nargs="?",
        default=Path("patterns.txt"),
        help="destination signature source (default: patterns.txt)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the output is up to date instead of writing it",
    )
    args = parser.parse_args(argv)

    try:
        blob = args.library.read_bytes()
    except OSError as error:
        print(f"[derive_patterns] ERROR: cannot read {args.library}: {error}", file=sys.stderr)
        return 2

    try:
        rendered = render(derive_entries(blob))
    except DerivationError as error:
        print(f"[derive_patterns] ERROR: {error}", file=sys.stderr)
        return 1

    if args.check:
        try:
            existing = args.output.read_text(encoding="utf-8")
        except OSError as error:
            print(f"[derive_patterns] ERROR: cannot read {args.output}: {error}", file=sys.stderr)
            return 2
        if existing != rendered:
            print(
                f"[derive_patterns] ERROR: {args.output} is out of date; "
                "rerun without --check",
                file=sys.stderr,
            )
            return 1
        print(f"[derive_patterns] {args.output} is up to date")
        return 0

    try:
        args.output.write_text(rendered, encoding="utf-8")
    except OSError as error:
        print(f"[derive_patterns] ERROR: cannot write {args.output}: {error}", file=sys.stderr)
        return 2

    print(f"[derive_patterns] wrote {len(derive_entries(blob))} signatures to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
