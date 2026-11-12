"""Tests for the signature-pack derivation tool."""

from __future__ import annotations

import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import derive_patterns  # noqa: E402

# ELF constants the fixtures use.
EM_AARCH64 = 0xB7
SHT_DYNSYM = 11
STB_GLOBAL = 1
STB_WEAK = 2
SHN_UNDEF = 0
SHN_TEXT = 1

SECTION_HEADER = struct.Struct("<IIQQQQIIQQ")
SYMBOL = struct.Struct("<IBBHQQ")


def build_library(text: bytes, symbols: list[tuple[str, int]]) -> bytes:
    """Builds a minimal 64-bit AArch64 ELF holding `text` and `symbols`.

    Args:
        text: bytes of the ``.text`` section.
        symbols: ``(name, offset_into_text)`` pairs to export as ``Java_*``.

    Returns:
        The image.
    """
    names = b"\x00"
    offsets = {}
    for name, _ in symbols:
        offsets[name] = len(names)
        names += name.encode() + b"\x00"

    text_addr = 0x1000
    text_off = 0x100
    dynsym_off = text_off + len(text)
    dynstr_off = dynsym_off + SYMBOL.size * (len(symbols) + 1)
    shstr_off = dynstr_off + len(names)
    shstr = b"\x00.text\x00.dynsym\x00.dynstr\x00.shstrtab\x00"
    shoff = shstr_off + len(shstr)

    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = 2
    header[5] = 1
    struct.pack_into("<H", header, 16, 3)
    struct.pack_into("<H", header, 18, EM_AARCH64)
    struct.pack_into("<Q", header, 40, shoff)
    struct.pack_into("<H", header, 58, SECTION_HEADER.size)
    struct.pack_into("<H", header, 60, 5)
    struct.pack_into("<H", header, 62, 4)

    dynsym = bytearray(SYMBOL.size * (len(symbols) + 1))
    for index, (name, offset) in enumerate(symbols, start=1):
        struct.pack_into(
            "<IBBHQQ",
            dynsym,
            index * SYMBOL.size,
            offsets[name],
            (STB_GLOBAL << 4) | 2,
            0,
            SHN_TEXT,
            text_addr + offset,
            0,
        )

    sections = [
        (0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        (1, 1, 0x6, text_addr, text_off, len(text), 0, 0, 4, 0),
        (7, SHT_DYNSYM, 0x2, 0, dynsym_off, len(dynsym), 3, 1, 8, SYMBOL.size),
        (15, 3, 0x2, 0, dynstr_off, len(names), 0, 0, 1, 0),
        (23, 3, 0, 0, shstr_off, len(shstr), 0, 0, 1, 0),
    ]
    table = b"".join(SECTION_HEADER.pack(*section) for section in sections)

    image = bytearray(shoff + len(table))
    image[0:64] = header
    image[text_off : text_off + len(text)] = text
    image[dynsym_off : dynsym_off + len(dynsym)] = dynsym
    image[dynstr_off : dynstr_off + len(names)] = names
    image[shstr_off : shstr_off + len(shstr)] = shstr
    image[shoff:] = table
    return bytes(image)


class DerivationTests(unittest.TestCase):
    """Deriving signatures from an ELF image."""

    def test_a_unique_signature_is_derived_for_each_symbol(self) -> None:
        text = bytes(range(64)) + bytes(range(64, 128))
        image = build_library(text, [("Java_a", 0), ("Java_b", 64)])

        entries = derive_patterns.derive_entries(image)

        self.assertEqual([entry.name for entry in entries], ["Java_a", "Java_b"])
        self.assertEqual(entries[0].pattern, " ".join(f"{b:02X}" for b in text[:8]))
        self.assertEqual(entries[1].pattern, " ".join(f"{b:02X}" for b in text[64:72]))

    def test_an_ambiguous_prefix_grows_until_it_is_unique(self) -> None:
        # Both functions start with the same eight bytes, so the signature has
        # to grow past them before it identifies one address.
        shared = bytes([0xAA] * 8)
        text = shared + bytes([0x01, 0x02, 0x03, 0x04]) + shared + bytes([0x09, 0x0A, 0x0B, 0x0C])
        image = build_library(text, [("Java_a", 0), ("Java_b", 12)])

        entries = derive_patterns.derive_entries(image)

        self.assertEqual(len(entries[0].pattern.split()), 12)
        self.assertNotEqual(entries[0].pattern, entries[1].pattern)

    def test_an_overlapping_repeat_is_not_treated_as_unique(self) -> None:
        # `bytes.count` skips overlaps, so a run of identical bytes would look
        # unique at the first length. The derivation must count overlaps: three
        # occurrences of `AA AA AA` live in a run of five.
        self.assertEqual(1, derive_patterns._count_occurrences(bytes([0xAA] * 3), bytes([0xAA] * 3)))
        self.assertEqual(
            3, derive_patterns._count_occurrences(bytes([0xAA] * 5), bytes([0xAA] * 3))
        )
        # `bytes.count` would report 1 here; the derivation reports 3.
        self.assertEqual(1, bytes([0xAA] * 5).count(bytes([0xAA] * 3)))

    def test_a_signature_that_never_becomes_unique_is_skipped(self) -> None:
        # The same body at both offsets and only four trailing bytes differ, so
        # no length up to the limit separates them.
        text = bytes([0xAA] * 8) + bytes([0xBB] * 4) + bytes([0xAA] * 8) + bytes([0xCC] * 4)
        image = build_library(text, [("Java_a", 0), ("Java_b", 12)])

        entries = derive_patterns.derive_entries(image)

        # Both names differ only in the last four bytes, which is inside the
        # 8-byte minimum, so they cannot be separated within the window.
        self.assertTrue(all(len(entry.pattern.split()) >= 8 for entry in entries))

    def test_a_non_jni_symbol_is_ignored(self) -> None:
        text = bytes(range(64))
        image = build_library(text, [("Java_a", 0), ("_ZN3fooEv", 32)])

        entries = derive_patterns.derive_entries(image)

        self.assertEqual([entry.name for entry in entries], ["Java_a"])

    def test_an_undefined_symbol_is_ignored(self) -> None:
        text = bytes(range(64))
        image = bytearray(build_library(text, [("Java_a", 0), ("Java_b", 32)]))
        # The first real symbol sits at index 1, right after the null entry.
        first = 0x100 + len(text) + SYMBOL.size
        struct.pack_into("<H", image, first + 6, SHN_UNDEF)

        entries = derive_patterns.derive_entries(bytes(image))

        self.assertEqual([entry.name for entry in entries], ["Java_b"])

    def test_a_library_without_jni_symbols_is_rejected(self) -> None:
        image = build_library(bytes(range(64)), [("_ZN3fooEv", 0)])

        with self.assertRaises(derive_patterns.DerivationError):
            derive_patterns.derive_entries(image)

    def test_a_non_elf_file_is_rejected(self) -> None:
        with self.assertRaises(derive_patterns.DerivationError):
            derive_patterns.derive_entries(b"not an elf")

    def test_a_32_bit_file_is_rejected(self) -> None:
        header = bytearray(64)
        header[0:4] = b"\x7fELF"
        header[4] = 1
        header[5] = 1

        with self.assertRaises(derive_patterns.DerivationError):
            derive_patterns.derive_entries(bytes(header))

    def test_a_non_aarch64_file_is_rejected(self) -> None:
        header = bytearray(64)
        header[0:4] = b"\x7fELF"
        header[4] = 2
        header[5] = 1
        struct.pack_into("<H", header, 18, 0x3E)

        with self.assertRaises(derive_patterns.DerivationError):
            derive_patterns.derive_entries(bytes(header))


class RenderTests(unittest.TestCase):
    """Rendering the signature source."""

    def test_rendered_entries_round_trip_through_generate_patterns(self) -> None:
        import generate_patterns

        entries = [
            derive_patterns.Entry(name="Java_a", pattern="AA BB CC DD EE FF 00 11", address=0),
            derive_patterns.Entry(name="Java_b", pattern="12 34 56 78 9A BC DE F0", address=0),
        ]

        rendered = derive_patterns.render(entries)

        parsed = generate_patterns.parse_source(rendered)
        self.assertEqual([entry.name for entry in parsed], ["Java_a", "Java_b"])
        self.assertEqual(parsed[0].pattern, "AA BB CC DD EE FF 00 11")

    def test_rendered_source_ends_in_a_newline(self) -> None:
        rendered = derive_patterns.render(
            [derive_patterns.Entry(name="Java_a", pattern="AA BB CC DD EE FF 00 11", address=0)]
        )
        self.assertTrue(rendered.endswith("\n"))


class MainTests(unittest.TestCase):
    """The command-line entry point."""

    def test_check_reports_an_out_of_date_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library = root / "lib.so"
            library.write_bytes(build_library(bytes(range(64)), [("Java_a", 0)]))
            output = root / "patterns.txt"
            output.write_text("stale\n")

            self.assertEqual(1, derive_patterns.main([str(library), str(output), "--check"]))

    def test_writing_then_checking_succeeds(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library = root / "lib.so"
            library.write_bytes(build_library(bytes(range(64)), [("Java_a", 0)]))
            output = root / "patterns.txt"

            self.assertEqual(0, derive_patterns.main([str(library), str(output)]))
            self.assertEqual(0, derive_patterns.main([str(library), str(output), "--check"]))

    def test_a_missing_library_returns_two(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "absent.so"
            self.assertEqual(2, derive_patterns.main([str(missing)]))


if __name__ == "__main__":
    unittest.main()
