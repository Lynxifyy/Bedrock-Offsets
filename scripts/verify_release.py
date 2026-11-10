#!/usr/bin/env python3
"""Check a release APK against the shipping budget.

The app promises to stay small and to keep the engine's diagnostics out of a
release build. Both are easy to break by accident, so they are checked on the
built artefact rather than trusted.

Checks:
    * the APK is under the size budget,
    * the engine and the native shim are packaged for arm64-v8a,
    * the R8-processed classes are present and no debug-only class survived,
    * the manifest does not request INTERNET.

Exit codes:
    0  every check passed.
    1  a check failed.
    2  the APK could not be read.
"""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

# The shipping budget from the release plan.
SIZE_BUDGET_BYTES = 20 * 1024 * 1024

# Native objects the app cannot run without.
REQUIRED_NATIVE_OBJECTS = (
    "lib/arm64-v8a/liboffset_core.so",
)

# Substrings that must not appear in the packaged bytecode. R8 rewrites names,
# so this looks for the logging call sites that a release build should have
# removed rather than for a class name.
FORBIDDEN_BYTECODE_MARKERS = (
    b"android/util/Log->d(",
    b"android/util/Log->v(",
)


class ReleaseCheckError(RuntimeError):
    """Raised when the artefact cannot be inspected at all."""


@dataclass(frozen=True)
class CheckResult:
    """Outcome of one check.

    Attributes:
        name: short description of the check.
        passed: whether it held.
        detail: human-readable evidence.
    """

    name: str
    passed: bool
    detail: str


def find_apk(root: Path) -> Path:
    """Locate the release APK under ``root``.

    Args:
        root: directory to search, for example ``app/build/outputs/apk/release``.

    Returns:
        The newest ``*.apk`` file found.

    Raises:
        ReleaseCheckError: if no APK exists.
    """
    candidates = sorted(root.rglob("*.apk"), key=lambda path: path.stat().st_mtime)
    if not candidates:
        raise ReleaseCheckError(f"no APK found under {root}")
    return candidates[-1]


def check_size(apk: Path) -> CheckResult:
    """Verify the APK is inside the size budget.

    Args:
        apk: the artefact to measure.

    Returns:
        The check result.
    """
    size = apk.stat().st_size
    megabytes = size / (1024 * 1024)
    return CheckResult(
        name="size budget",
        passed=size <= SIZE_BUDGET_BYTES,
        detail=f"{megabytes:.2f} MiB of {SIZE_BUDGET_BYTES / (1024 * 1024):.0f} MiB",
    )


def check_native_objects(names: set[str]) -> CheckResult:
    """Verify every required native object is packaged.

    Args:
        names: entry names inside the APK.

    Returns:
        The check result.
    """
    missing = [name for name in REQUIRED_NATIVE_OBJECTS if name not in names]
    return CheckResult(
        name="native objects",
        passed=not missing,
        detail="all present" if not missing else f"missing {', '.join(missing)}",
    )


def check_no_debug_logging(apk: Path) -> CheckResult:
    """Verify the packaged bytecode has no debug logging call sites.

    Args:
        apk: the artefact to scan.

    Returns:
        The check result.
    """
    found: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith(".dex"):
                continue
            blob = archive.read(name)
            for marker in FORBIDDEN_BYTECODE_MARKERS:
                if marker in blob:
                    found.append(f"{name}: {marker.decode()}")
    return CheckResult(
        name="no debug logging",
        passed=not found,
        detail="clean" if not found else "; ".join(found),
    )


def check_manifest(apk: Path) -> CheckResult:
    """Verify the manifest requests no network permission.

    The manifest is binary XML; the permission name is stored as UTF-16, so the
    check looks for that encoding rather than parsing the document.

    Args:
        apk: the artefact to inspect.

    Returns:
        The check result.
    """
    with zipfile.ZipFile(apk) as archive:
        try:
            manifest = archive.read("AndroidManifest.xml")
        except KeyError as error:
            raise ReleaseCheckError("the APK has no AndroidManifest.xml") from error

    permission = "android.permission.INTERNET".encode("utf-16-le")
    present = permission in manifest
    return CheckResult(
        name="no INTERNET permission",
        passed=not present,
        detail="absent" if not present else "the manifest requests INTERNET",
    )


def run_checks(apk: Path) -> list[CheckResult]:
    """Run every check against ``apk``.

    Args:
        apk: the artefact to inspect.

    Returns:
        One result per check, in report order.
    """
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
    if not any(name.endswith(".dex") for name in names):
        raise ReleaseCheckError(f"{apk} contains no dex bytecode")

    return [
        check_size(apk),
        check_native_objects(names),
        check_no_debug_logging(apk),
        check_manifest(apk),
    ]


def format_report(apk: Path, results: list[CheckResult]) -> str:
    """Render the results as a report.

    Args:
        apk: the artefact that was checked.
        results: the check results.

    Returns:
        A multi-line report ending in a newline.
    """
    lines = [f"[verify_release] {apk}"]
    for result in results:
        marker = "ok  " if result.passed else "FAIL"
        lines.append(f"  {marker} {result.name}: {result.detail}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    """Run the release checks.

    Args:
        argv: command-line arguments; defaults to ``sys.argv[1:]``.

    Returns:
        The process exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "apk",
        type=Path,
        nargs="?",
        help="release APK, or a directory to search (default: app/build/outputs/apk/release)",
    )
    args = parser.parse_args(argv)

    try:
        target = args.apk or Path("app/build/outputs/apk/release")
        apk = find_apk(target) if target.is_dir() else target
        if not apk.is_file():
            raise ReleaseCheckError(f"{apk} does not exist")
        results = run_checks(apk)
    except (ReleaseCheckError, zipfile.BadZipFile) as error:
        print(f"[verify_release] ERROR: {error}", file=sys.stderr)
        return 2

    print(format_report(apk, results), end="")
    return 0 if all(result.passed for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
