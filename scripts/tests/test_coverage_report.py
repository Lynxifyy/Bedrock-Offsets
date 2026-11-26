"""Tests for scripts/coverage_report.py.

The numbers this script prints end up in `COVERAGE.md` and in the manifest, so
a regression would silently misreport what the engine names. The fixture is a
category document with a known answer, written to a directory the same way the
app writes it.
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts import coverage_report

CATEGORY = {
    "kind": "internal_cpp_category",
    "category": "client_game",
    "label": "Client / game",
    "classes": {
        "Level": {"name": "Level", "vtableGroups": [{"slots": []}, {"slots": []}]},
        "Player": {"name": "Player", "vtableGroups": [{"slots": []}]},
    },
    "functions": [
        {"name": "Level::tick()", "nameSource": "symbol", "size": 30,
         "signature": "aa", "signatureUnique": True},
        {"name": "Level::tick()::{lambda()#1}::operator()", "nameSource": "lambda",
         "size": 20, "signature": "bb", "signatureUnique": True},
        {"name": "Player::vfunc_3", "nameSource": "vtable", "size": 200},
        {"name": "v8::internal::Runtime_Abort", "nameSource": "symbol", "size": 12,
         "signature": "cc", "signatureUnique": False},
        {"name": "sub_401000", "nameSource": "auto", "size": 4096},
    ],
}


class CoverageReportTest(unittest.TestCase):
    def test_counts_a_directory_export(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            inner = Path(directory, "InternalCPP")
            inner.mkdir()
            (inner / "client_game.json").write_text(json.dumps(CATEGORY), encoding="utf-8")
            report = coverage_report.measure(directory)

        overall = report["overall"]
        self.assertEqual(overall["functions"], 5)
        self.assertEqual(overall["realNamed"], 3)
        self.assertEqual(overall["placeholder"], 1)
        self.assertEqual(overall["auto"], 1)
        self.assertEqual(overall["signaturesUnique"], 2)
        self.assertEqual(overall["classes"], 2)
        self.assertEqual(overall["vtableGroups"], 3)
        self.assertEqual(overall["unsignedLargerThan128"], 2)
        self.assertEqual(report["gameplay"]["functions"], 4)
        self.assertEqual(report["thirdParty"]["functions"], 1)

    def test_counts_a_zip_export_and_writes_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory, "export.zip")
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr(
                    "InternalCPP/INTERNALCPP_CLIENT_GAME.json", json.dumps(CATEGORY)
                )
                zipped.writestr("manifest.json", json.dumps({"functions": 5}))
            report = coverage_report.measure(str(archive))

        self.assertEqual(report["overall"]["realNamed"], 3)
        text = coverage_report.markdown(report)
        self.assertIn("| Gameplay | 4 | 2 (50.00 %)", text)
        self.assertIn("Class rows: 2 for 3 vtable groups", text)

    def test_a_placeholder_name_is_not_a_real_name(self) -> None:
        self.assertEqual(coverage_report.classify_name("Mob::vfunc_0", "vtable"), "placeholder")
        self.assertEqual(coverage_report.classify_name("sub_1000", "auto"), "auto")
        self.assertEqual(coverage_report.classify_name("Mob::tick()", "symbol"), "real")


if __name__ == "__main__":
    unittest.main()
