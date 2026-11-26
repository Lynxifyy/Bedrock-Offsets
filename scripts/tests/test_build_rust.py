"""Tests for scripts/build_rust.py.

Two contracts are pinned here. A machine without the Android cross-compile
toolchain must not silently produce an APK without the engine: it fails unless
the caller sets the opt-out. GitHub's runner ships `cargo` but not `cargo-ndk`,
so that is the case worth pinning.
"""

from __future__ import annotations

import os
import stat
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.build_rust import (
    ALLOW_MISSING_RUST_ENV,
    ToolchainMissing,
    find_cargo,
    run_cargo_build,
    skip_or_fail,
)


def write_fake_cargo(directory: Path, *, supports_ndk: bool) -> Path:
    """Write a fake cargo that either knows the `ndk` subcommand or does not."""
    path = directory / "cargo"
    if supports_ndk:
        body = "#!/bin/sh\nexit 0\n"
    else:
        body = '#!/bin/sh\necho "error: no such command: `ndk`" >&2\nexit 1\n'
    path.write_text(body, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


class FindCargoTest(unittest.TestCase):
    def test_honours_the_cargo_variable(self) -> None:
        with TemporaryDirectory() as tmp:
            fake = write_fake_cargo(Path(tmp), supports_ndk=True)
            previous = os.environ.get("CARGO")
            os.environ["CARGO"] = str(fake)
            try:
                self.assertEqual(find_cargo(), str(fake))
            finally:
                if previous is None:
                    del os.environ["CARGO"]
                else:
                    os.environ["CARGO"] = previous


class RunCargoBuildTest(unittest.TestCase):
    def test_a_missing_cargo_ndk_is_reported_as_a_missing_toolchain(self) -> None:
        with TemporaryDirectory() as tmp:
            fake = write_fake_cargo(Path(tmp), supports_ndk=False)
            with self.assertRaises(ToolchainMissing):
                run_cargo_build(str(fake), offline=False)

    def test_a_successful_build_returns_quietly(self) -> None:
        with TemporaryDirectory() as tmp:
            fake = write_fake_cargo(Path(tmp), supports_ndk=True)
            run_cargo_build(str(fake), offline=False)


class SkipOrFailTest(unittest.TestCase):
    """A missing engine must fail the build, not ship a useless APK."""

    def test_a_missing_engine_fails_the_build(self) -> None:
        previous = os.environ.pop(ALLOW_MISSING_RUST_ENV, None)
        try:
            self.assertEqual(skip_or_fail("cargo not found"), 1)
        finally:
            if previous is not None:
                os.environ[ALLOW_MISSING_RUST_ENV] = previous

    def test_the_opt_out_still_allows_a_kotlin_only_build(self) -> None:
        previous = os.environ.get(ALLOW_MISSING_RUST_ENV)
        os.environ[ALLOW_MISSING_RUST_ENV] = "1"
        try:
            self.assertEqual(skip_or_fail("cargo not found"), 0)
        finally:
            if previous is None:
                del os.environ[ALLOW_MISSING_RUST_ENV]
            else:
                os.environ[ALLOW_MISSING_RUST_ENV] = previous


if __name__ == "__main__":
    unittest.main()
