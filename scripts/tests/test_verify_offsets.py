"""Tests for the offset comparison script."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import verify_offsets  # noqa: E402


def document(build_id: str, offsets: dict[str, str]) -> dict:
    """Build a minimal offset document for a test."""
    return {
        "schemaVersion": 1,
        "source": {"buildId": build_id},
        "offsets": offsets,
    }


class DiffTests(unittest.TestCase):
    """The diff algorithm, exercised directly."""

    def test_identical_maps_have_no_differences(self) -> None:
        before = {"pattern:a": "0x10", "symbol:b": "0x20"}
        self.assertEqual([], verify_offsets.diff_offsets(before, dict(before)))

    def test_a_changed_offset_is_reported(self) -> None:
        differences = verify_offsets.diff_offsets({"pattern:a": "0x10"}, {"pattern:a": "0x30"})
        self.assertEqual(1, len(differences))
        self.assertEqual("changed", differences[0].kind)
        self.assertEqual("0x10", differences[0].before)
        self.assertEqual("0x30", differences[0].after)

    def test_added_and_removed_offsets_are_classified(self) -> None:
        differences = verify_offsets.diff_offsets(
            {"gone": "0x1"},
            {"fresh": "0x2"},
        )
        kinds = {difference.key: difference.kind for difference in differences}
        self.assertEqual({"gone": "removed", "fresh": "added"}, kinds)

    def test_differences_are_sorted_by_key(self) -> None:
        differences = verify_offsets.diff_offsets({}, {"z": "0x1", "a": "0x2", "m": "0x3"})
        self.assertEqual(["a", "m", "z"], [difference.key for difference in differences])

    def test_summary_counts_each_kind(self) -> None:
        differences = verify_offsets.diff_offsets(
            {"changed": "0x1", "removed": "0x1"},
            {"changed": "0x2", "added": "0x3"},
        )
        self.assertEqual(
            {"added": 1, "removed": 1, "changed": 1},
            verify_offsets.summarise(differences),
        )

    def test_a_report_names_both_builds_and_every_difference(self) -> None:
        before = document("aaaa", {"pattern:a": "0x10"})
        after = document("bbbb", {"pattern:a": "0x20"})
        differences = verify_offsets.diff_offsets(before["offsets"], after["offsets"])

        report = verify_offsets.format_report(before, after, differences)

        self.assertIn("aaaa -> bbbb", report)
        self.assertIn("~ pattern:a: 0x10 -> 0x20", report)
        self.assertTrue(report.endswith("\n"))

    def test_a_matching_report_says_so(self) -> None:
        document_a = document("aaaa", {"pattern:a": "0x10"})
        report = verify_offsets.format_report(document_a, document_a, [])
        self.assertIn("every offset matches.", report)


class CliTests(unittest.TestCase):
    """The command-line contract, including exit codes."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def write(self, name: str, payload: dict) -> Path:
        path = self.root / name
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_matching_documents_exit_zero(self) -> None:
        before = self.write("before.json", document("aaaa", {"a": "0x1"}))
        after = self.write("after.json", document("aaaa", {"a": "0x1"}))
        self.assertEqual(0, verify_offsets.main([str(before), str(after), "--quiet"]))

    def test_a_difference_exits_one(self) -> None:
        before = self.write("before.json", document("aaaa", {"a": "0x1"}))
        after = self.write("after.json", document("bbbb", {"a": "0x2"}))
        self.assertEqual(1, verify_offsets.main([str(before), str(after)]))

    def test_a_missing_file_exits_two(self) -> None:
        present = self.write("present.json", document("aaaa", {}))
        missing = self.root / "absent.json"
        self.assertEqual(2, verify_offsets.main([str(missing), str(present)]))

    def test_a_document_without_offsets_exits_two(self) -> None:
        broken = self.write("broken.json", {"schemaVersion": 1})
        present = self.write("present.json", document("aaaa", {}))
        self.assertEqual(2, verify_offsets.main([str(broken), str(present)]))


if __name__ == "__main__":
    unittest.main()
