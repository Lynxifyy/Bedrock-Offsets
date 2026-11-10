"""Tests for scripts/build_rust.py.

The script's contract is that a machine without the Android cross-compile
toolchain still builds the Kotlin code: it warns and exits 0. GitHub's runner
ships `cargo` but not `cargo-ndk`, so that is the case worth pinning.
"""

from __future__ import annotations

import os
import stat
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.build_rust import ToolchainMissing, find_cargo, run_cargo_build


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


if __name__ == "__main__":
    unittest.main()
