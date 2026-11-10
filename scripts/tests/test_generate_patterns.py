"""Tests for the signature-pack generator."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import generate_patterns  # noqa: E402


class ParseTests(unittest.TestCase):
    """Parsing and normalising the signature source."""

    def test_comments_and_blank_lines_are_ignored(self) -> None:
        entries = generate_patterns.parse_source(
            "# a comment\n\nframe = FD 7B BF A9\n"
        )
        self.assertEqual(1, len(entries))
        self.assertEqual("frame", entries[0].name)

    def test_bytes_are_upper_cased_and_wildcards_kept(self) -> None:
        entries = generate_patterns.parse_source("mixed = fd 7b ?? a9\n")
        self.assertEqual("FD 7B ?? A9", entries[0].pattern)

    def test_entries_are_sorted_by_name(self) -> None:
        entries = generate_patterns.parse_source("zebra = 01\nalpha = 02\n")
        self.assertEqual(["alpha", "zebra"], [entry.name for entry in entries])

    def test_a_duplicate_name_is_rejected(self) -> None:
        with self.assertRaises(generate_patterns.PatternError):
            generate_patterns.parse_source("dup = 01\ndup = 02\n")

    def test_a_missing_equals_is_rejected(self) -> None:
        with self.assertRaises(generate_patterns.PatternError):
            generate_patterns.parse_source("no equals here\n")

    def test_an_invalid_byte_token_is_rejected(self) -> None:
        with self.assertRaises(generate_patterns.PatternError):
            generate_patterns.parse_source("bad = GG 01\n")

    def test_a_single_hex_digit_is_rejected(self) -> None:
        with self.assertRaises(generate_patterns.PatternError):
            generate_patterns.parse_source("bad = A 01\n")

    def test_an_invalid_name_is_rejected(self) -> None:
        with self.assertRaises(generate_patterns.PatternError):
            generate_patterns.parse_source("1bad = 01\n")

    def test_an_over_long_pattern_is_rejected(self) -> None:
        tokens = " ".join(["01"] * (generate_patterns.MAX_PATTERN_BYTES + 1))
        with self.assertRaises(generate_patterns.PatternError):
            generate_patterns.parse_source(f"long = {tokens}\n")

    def test_an_empty_source_is_rejected(self) -> None:
        with self.assertRaises(generate_patterns.PatternError):
            generate_patterns.parse_source("# only a comment\n")

    def test_render_matches_the_shape_the_app_reads(self) -> None:
        entries = generate_patterns.parse_source("frame = FD 7B BF A9\n")
        document = json.loads(generate_patterns.render(entries))
        self.assertEqual(
            {"patterns": [{"name": "frame", "pattern": "FD 7B BF A9"}]},
            document,
        )


class CliTests(unittest.TestCase):
    """Writing the pack and checking it is current."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_generating_writes_a_pack_that_passes_check(self) -> None:
        source = self.root / "patterns.txt"
        source.write_text("frame = FD 7B BF A9\n", encoding="utf-8")
        output = self.root / "patterns.json"

        self.assertEqual(0, generate_patterns.main([str(source), str(output)]))
        self.assertEqual(0, generate_patterns.main([str(source), str(output), "--check"]))

    def test_a_stale_pack_fails_the_check(self) -> None:
        source = self.root / "patterns.txt"
        source.write_text("frame = FD 7B BF A9\n", encoding="utf-8")
        output = self.root / "patterns.json"
        output.write_text("{\"patterns\":[]}", encoding="utf-8")

        self.assertEqual(1, generate_patterns.main([str(source), str(output), "--check"]))

    def test_a_malformed_source_exits_one(self) -> None:
        source = self.root / "patterns.txt"
        source.write_text("broken\n", encoding="utf-8")
        output = self.root / "patterns.json"

        self.assertEqual(1, generate_patterns.main([str(source), str(output)]))

    def test_a_missing_source_exits_two(self) -> None:
        missing = self.root / "absent.txt"
        output = self.root / "patterns.json"
        self.assertEqual(2, generate_patterns.main([str(missing), str(output)]))


if __name__ == "__main__":
    unittest.main()
