"""Tests for scripts/verify_version.py."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.verify_version import main, normalise_tag, read_version_name


def write_build_file(directory: Path, body: str) -> Path:
    path = directory / "build.gradle.kts"
    path.write_text(body, encoding="utf-8")
    return path


class ReadVersionNameTest(unittest.TestCase):
    def test_reads_the_declared_version(self) -> None:
        with TemporaryDirectory() as tmp:
            path = write_build_file(
                Path(tmp),
                'android {\n    defaultConfig {\n        versionName = "2.3.4"\n    }\n}\n',
            )
            self.assertEqual(read_version_name(path), "2.3.4")

    def test_rejects_a_file_without_a_version(self) -> None:
        with TemporaryDirectory() as tmp:
            path = write_build_file(Path(tmp), "android {\n}\n")
            with self.assertRaises(ValueError):
                read_version_name(path)

    def test_rejects_a_missing_file(self) -> None:
        with self.assertRaises(ValueError):
            read_version_name(Path("/nonexistent/build.gradle.kts"))

    def test_does_not_match_a_comment(self) -> None:
        with TemporaryDirectory() as tmp:
            path = write_build_file(
                Path(tmp),
                '// versionName = "9.9.9"\n        versionName = "1.0.0"\n',
            )
            self.assertEqual(read_version_name(path), "1.0.0")


class NormaliseTagTest(unittest.TestCase):
    def test_strips_the_leading_v(self) -> None:
        self.assertEqual(normalise_tag("v1.0.0"), "1.0.0")

    def test_leaves_a_bare_version_alone(self) -> None:
        self.assertEqual(normalise_tag("1.0.0"), "1.0.0")


class MainTest(unittest.TestCase):
    def run_main(self, tag: str, body: str) -> int:
        with TemporaryDirectory() as tmp:
            path = write_build_file(Path(tmp), body)
            return main(["--tag", tag, "--build-file", str(path)])

    def test_agreeing_versions_pass(self) -> None:
        code = self.run_main("v1.0.0", '        versionName = "1.0.0"\n')
        self.assertEqual(code, 0)

    def test_a_mismatched_tag_fails(self) -> None:
        code = self.run_main("v1.1.0", '        versionName = "1.0.0"\n')
        self.assertEqual(code, 1)

    def test_an_unreadable_version_fails(self) -> None:
        code = self.run_main("v1.0.0", "android {\n}\n")
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
