"""Tests for the release-artefact verifier."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import verify_release  # noqa: E402


def build_apk(
    root: Path,
    *,
    name: str = "app-release.apk",
    dex: bytes = b"\x03\x00\x00\x00",
    natives: tuple[str, ...] = ("lib/arm64-v8a/liboffset_core.so",),
    manifest_permission: bool = False,
    filler: bytes = b"",
) -> Path:
    """Write a minimal APK with the requested contents."""
    path = root / name
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", dex)
        archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
        for native in natives:
            archive.writestr(native, b"\x7fELF")
        if filler:
            archive.writestr("assets/filler.bin", filler)
    if manifest_permission:
        # Rebuild with the permission string stored as UTF-16, which is how the
        # binary manifest holds it.
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", dex)
            archive.writestr(
                "AndroidManifest.xml",
                "android.permission.INTERNET".encode("utf-16-le"),
            )
            for native in natives:
                archive.writestr(native, b"\x7fELF")
            if filler:
                archive.writestr("assets/filler.bin", filler)
    return path


class CheckTests(unittest.TestCase):
    """Each check, exercised against a synthetic artefact."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_a_small_apk_passes_the_size_budget(self) -> None:
        apk = build_apk(self.root)
        self.assertTrue(verify_release.check_size(apk).passed)

    def test_an_oversized_apk_fails_the_size_budget(self) -> None:
        apk = build_apk(
            self.root,
            filler=b"\x00" * (verify_release.SIZE_BUDGET_BYTES + 1024),
        )
        result = verify_release.check_size(apk)
        self.assertFalse(result.passed)
        self.assertIn("MiB", result.detail)

    def test_a_packaged_engine_passes_the_native_check(self) -> None:
        apk = build_apk(self.root)
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
        self.assertTrue(verify_release.check_native_objects(names).passed)

    def test_a_missing_engine_fails_the_native_check(self) -> None:
        apk = build_apk(self.root, natives=())
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
        result = verify_release.check_native_objects(names)
        self.assertFalse(result.passed)
        self.assertIn("liboffset_core.so", result.detail)

    def test_a_debug_log_call_site_fails_the_logging_check(self) -> None:
        apk = build_apk(self.root, dex=b"...android/util/Log->d(...")
        result = verify_release.check_no_debug_logging(apk)
        self.assertFalse(result.passed)
        self.assertIn("android/util/Log->d(", result.detail)

    def test_clean_bytecode_passes_the_logging_check(self) -> None:
        apk = build_apk(self.root, dex=b"...android/util/Log->i(...")
        self.assertTrue(verify_release.check_no_debug_logging(apk).passed)

    def test_a_manifest_without_internet_passes(self) -> None:
        apk = build_apk(self.root)
        self.assertTrue(verify_release.check_manifest(apk).passed)

    def test_a_manifest_with_internet_fails(self) -> None:
        apk = build_apk(self.root, manifest_permission=True)
        result = verify_release.check_manifest(apk)
        self.assertFalse(result.passed)
        self.assertIn("INTERNET", result.detail)

    def test_an_apk_without_a_manifest_is_an_error(self) -> None:
        apk = self.root / "no-manifest.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", b"\x03")
        with self.assertRaises(verify_release.ReleaseCheckError):
            verify_release.check_manifest(apk)


class CliTests(unittest.TestCase):
    """The command-line contract."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_a_clean_apk_exits_zero(self) -> None:
        apk = build_apk(self.root)
        self.assertEqual(0, verify_release.main([str(apk)]))

    def test_an_apk_with_a_missing_engine_exits_one(self) -> None:
        apk = build_apk(self.root, natives=())
        self.assertEqual(1, verify_release.main([str(apk)]))

    def test_a_missing_apk_exits_two(self) -> None:
        missing = self.root / "absent.apk"
        self.assertEqual(2, verify_release.main([str(missing)]))

    def test_a_directory_is_searched_for_the_newest_apk(self) -> None:
        older = build_apk(self.root, name="old.apk")
        newer = build_apk(self.root, name="new.apk")
        # Timestamps are set explicitly: two files written in the same tick
        # would otherwise have the same modification time.
        os.utime(older, (1_000_000, 1_000_000))
        os.utime(newer, (2_000_000, 2_000_000))

        self.assertEqual(newer, verify_release.find_apk(self.root))

    def test_a_directory_without_an_apk_is_an_error(self) -> None:
        with self.assertRaises(verify_release.ReleaseCheckError):
            verify_release.find_apk(self.root)

    def test_a_non_zip_file_exits_two(self) -> None:
        broken = self.root / "broken.apk"
        broken.write_bytes(b"not a zip")
        self.assertEqual(2, verify_release.main([str(broken)]))


if __name__ == "__main__":
    unittest.main()
