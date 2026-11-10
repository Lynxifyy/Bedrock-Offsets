#!/usr/bin/env python3
"""Cross-compile the Rust engine for Android and stage the shared object.

The engine lives in ``app/src/main/rust/offset_core`` and is built with
``cargo ndk`` for ``arm64-v8a``. The resulting ``liboffset_core.so`` is copied
into ``app/src/main/jniLibs/arm64-v8a`` so the Android Gradle plugin packages
it without any extra wiring.

Exit codes:
    0  the library was built and staged, or the Rust toolchain is absent so the
       step was skipped with a warning.
    1  the build ran but failed, or the produced file is not a valid
       AArch64 ELF shared object.
"""

from __future__ import annotations

import argparse
import os
import shutil
import struct
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CRATE_DIR = REPO_ROOT / "app" / "src" / "main" / "rust" / "offset_core"
JNI_LIB_DIR = REPO_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
LIB_NAME = "liboffset_core.so"
TARGET = "arm64-v8a"
RUST_TARGET_TRIPLE = "aarch64-linux-android"
EM_AARCH64 = 183


class BuildError(RuntimeError):
    """Raised when the build cannot produce a usable library."""


def find_cargo() -> str | None:
    """Locate the cargo executable.

    Honours ``CARGO``, then ``PATH``, then the conventional per-user install.
    """
    explicit = os.environ.get("CARGO")
    if explicit and Path(explicit).is_file():
        return explicit

    on_path = shutil.which("cargo")
    if on_path:
        return on_path

    home = Path.home()
    for candidate in (home / ".cargo" / "bin" / "cargo", Path("/workspace/tools/cargo/bin/cargo")):
        if candidate.is_file():
            return str(candidate)
    return None


def verify_aarch64_elf(path: Path) -> None:
    """Verify that ``path`` is an ELF64 AArch64 shared object.

    Reads only the 20-byte ELF header, never the whole file.

    Raises:
        BuildError: if the file is missing, truncated or of the wrong
            architecture.
    """
    if not path.is_file():
        raise BuildError(f"{path} was not produced by the build")

    with path.open("rb") as handle:
        header = handle.read(20)

    if len(header) < 20:
        raise BuildError(f"{path} is too small to be an ELF file")
    if header[:4] != b"\x7fELF":
        raise BuildError(f"{path} does not start with the ELF magic number")

    elf_class, data = header[4], header[5]
    if elf_class != 2:
        raise BuildError(f"{path} is not a 64-bit ELF (class={elf_class})")

    endian = "<" if data == 1 else ">"
    (machine,) = struct.unpack(f"{endian}H", header[18:20])
    if machine != EM_AARCH64:
        raise BuildError(f"{path} targets machine {machine}, expected AArch64 ({EM_AARCH64})")


def run_cargo_build(cargo: str, *, offline: bool) -> None:
    """Cross-compile the crate for ``arm64-v8a``.

    Raises:
        BuildError: if cargo is missing, cargo-ndk is not installed, or the
            compile fails.
    """
    command = [cargo, "ndk", "-t", TARGET, "build", "--release"]
    if offline:
        command.append("--offline")

    print(f"[build_rust] {' '.join(command)} (cwd={CRATE_DIR})", flush=True)
    completed = subprocess.run(command, cwd=CRATE_DIR, check=False)
    if completed.returncode == 0:
        return

    # cargo-ndk is a separate subcommand; a bare cargo reports it as an
    # unknown subcommand, which is worth an explicit hint.
    probe = subprocess.run(
        [cargo, "ndk", "--version"],
        cwd=CRATE_DIR,
        check=False,
        capture_output=True,
        text=True,
    )
    if probe.returncode != 0:
        raise BuildError(
            "cargo-ndk is not installed. Install it with:\n"
            "    cargo install cargo-ndk --locked\n"
            "and ensure the Android NDK is available via ANDROID_NDK_HOME or the SDK."
        )
    raise BuildError(f"cargo ndk build failed with exit code {completed.returncode}")


def stage_library() -> Path:
    """Copy the built object into ``jniLibs``.

    Returns:
        The destination path.
    """
    built = CRATE_DIR / "target" / RUST_TARGET_TRIPLE / "release" / LIB_NAME
    verify_aarch64_elf(built)
    JNI_LIB_DIR.mkdir(parents=True, exist_ok=True)
    destination = JNI_LIB_DIR / LIB_NAME
    shutil.copy2(built, destination)
    print(f"[build_rust] staged {destination} ({destination.stat().st_size} bytes)")
    return destination


def main() -> int:
    """Run the build, reporting failures with a non-zero exit code."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--offline",
        action="store_true",
        help="pass --offline to cargo, using only the local registry cache",
    )
    parser.add_argument(
        "--check-only",
        action="store_true",
        help="verify an already staged library without rebuilding",
    )
    args = parser.parse_args()

    try:
        if args.check_only:
            verify_aarch64_elf(JNI_LIB_DIR / LIB_NAME)
            print(f"[build_rust] {JNI_LIB_DIR / LIB_NAME} is a valid AArch64 ELF")
            return 0

        cargo = find_cargo()
        if cargo is None:
            print(
                "[build_rust] cargo not found; skipping the Rust engine build. "
                "The app still runs and reports the engine as unavailable.",
                file=sys.stderr,
            )
            return 0

        run_cargo_build(cargo, offline=args.offline)
        stage_library()
        return 0
    except BuildError as error:
        print(f"[build_rust] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
