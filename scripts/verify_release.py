#!/usr/bin/env python3
"""Check a release APK against the shipping budget.

The app promises to stay small and to keep the engine's diagnostics out of a
release build. Both are easy to break by accident, so they are checked on the
built artefact rather than trusted.

Checks:
    * the APK is under the size budget,
    * the engine and the native shim are packaged for arm64-v8a,
    * the R8-processed classes are present and no debug-only class survived,
    * every Gson model still has a constructor after shrinking,
    * the manifest does not request INTERNET.

Exit codes:
    0  every check passed.
    1  a check failed.
    2  the APK could not be read.
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

import dex

# The shipping budget from the release plan.
SIZE_BUDGET_BYTES = 20 * 1024 * 1024

# Native objects the app cannot run without.
REQUIRED_NATIVE_OBJECTS = (
    "lib/arm64-v8a/liboffset_core.so",
)

# The callback interface the engine calls into from native code. Its method is
# looked up by name at run time, so R8 must leave it alone; this is the class and
# method the check below proves survived.
JNI_CALLBACK_CLASS = "Lcom/chimerateam/offsetextractor/data/native/ProgressCallback;"
JNI_CALLBACK_METHOD = "onProgress"

# Substrings that must not appear in the packaged bytecode. R8 rewrites names,
# so this looks for the logging call sites that a release build should have
# removed rather than for a class name.
FORBIDDEN_BYTECODE_MARKERS = (
    b"android/util/Log->d(",
    b"android/util/Log->v(",
)

# Every class Gson instantiates by reflection, written as a type descriptor.
#
# R8 cannot see those reflective calls, so without a keep rule it strips the
# constructor and marks the class abstract, and the release build then fails on
# the first payload it parses with "Abstract classes can't be instantiated!".
# This list mirrors the packages protected by app/proguard-rules.pro; a class
# that is deserialised but missing here would go unguarded, so keep the two in
# step when a payload is added.
GSON_MODEL_CLASSES = (
    # data/native -- engine payloads and the models they carry.
    "Lcom/chimerateam/offsetextractor/data/native/ExtractionPayload;",
    "Lcom/chimerateam/offsetextractor/data/native/ElfMetadataPayload;",
    "Lcom/chimerateam/offsetextractor/data/native/NativeLibraryMetadata;",
    "Lcom/chimerateam/offsetextractor/data/native/SectionMapPayload;",
    "Lcom/chimerateam/offsetextractor/data/native/SectionInfo;",
    "Lcom/chimerateam/offsetextractor/data/native/SectionMap;",
    "Lcom/chimerateam/offsetextractor/data/native/SymbolReportPayload;",
    "Lcom/chimerateam/offsetextractor/data/native/SymbolReport;",
    "Lcom/chimerateam/offsetextractor/data/native/BuildIdentity;",
    "Lcom/chimerateam/offsetextractor/data/native/SymbolTable;",
    "Lcom/chimerateam/offsetextractor/data/native/ExportedSymbol;",
    "Lcom/chimerateam/offsetextractor/data/native/ScanOutcomePayload;",
    "Lcom/chimerateam/offsetextractor/data/native/ScanOutcome;",
    "Lcom/chimerateam/offsetextractor/data/native/PatternHit;",
    "Lcom/chimerateam/offsetextractor/data/native/PatternSpec;",
    "Lcom/chimerateam/offsetextractor/data/native/VerificationSummary;",
    "Lcom/chimerateam/offsetextractor/data/native/VtableMapPayload;",
    "Lcom/chimerateam/offsetextractor/data/native/VtableMap;",
    "Lcom/chimerateam/offsetextractor/data/native/TypeVTable;",
    # data/export -- the exported offsets document.
    "Lcom/chimerateam/offsetextractor/data/export/OffsetDocument;",
    "Lcom/chimerateam/offsetextractor/data/export/SourceInfo;",
    "Lcom/chimerateam/offsetextractor/data/export/VTableEntry;",
    "Lcom/chimerateam/offsetextractor/data/export/Stats;",
    # data/cache -- the cached scan summary.
    "Lcom/chimerateam/offsetextractor/data/cache/ResultCache$Envelope;",
    # data/patterns -- the bundled signature pack.
    "Lcom/chimerateam/offsetextractor/data/patterns/SignaturePack$PackDocument;",
    "Lcom/chimerateam/offsetextractor/data/patterns/SignaturePack$Entry;",
    # ui/state -- the cached summary's own models.
    "Lcom/chimerateam/offsetextractor/ui/state/ScanResultSummary;",
    "Lcom/chimerateam/offsetextractor/ui/state/ScannerUiState;",
    "Lcom/chimerateam/offsetextractor/ui/state/BatchProgress;",
    "Lcom/chimerateam/offsetextractor/ui/state/BatchSummary;",
    "Lcom/chimerateam/offsetextractor/ui/state/ProgressTracker;",
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


def check_gson_models(apk: Path) -> CheckResult:
    """Verify every Gson model survived shrinking intact.

    A model is intact when it is present, is not abstract, and still declares a
    constructor. R8 removes the constructor and marks the class abstract when it
    cannot see a `new` call site, and Gson then throws when it tries to build
    one, so this is the failure that turns a release build into a crash on a
    device.

    Args:
        apk: the artefact to inspect.

    Returns:
        The check result.
    """
    try:
        tables = _read_dex_tables(apk)
    except dex.DexError as error:
        return CheckResult(name="Gson models", passed=False, detail=str(error))

    missing: list[str] = []
    broken: list[str] = []
    for descriptor in GSON_MODEL_CLASSES:
        entry = _find_class(tables, descriptor)
        if entry is None:
            missing.append(_simple_name(descriptor))
        elif entry.is_abstract or not entry.has_constructor:
            reason = "abstract" if entry.is_abstract else "no constructor"
            broken.append(f"{_simple_name(descriptor)} ({reason})")

    passed = not missing and not broken
    if passed:
        detail = f"{len(GSON_MODEL_CLASSES)} models instantiable"
    else:
        parts = []
        if missing:
            parts.append("shrunk away: " + ", ".join(missing))
        if broken:
            parts.append("not instantiable: " + ", ".join(broken))
        detail = "; ".join(parts)
    return CheckResult(name="Gson models", passed=passed, detail=detail)


def _read_dex_tables(apk: Path) -> list[dex.DexFile]:
    """Parse every ``classes*.dex`` in ``apk``.

    Args:
        apk: the artefact to read.

    Returns:
        One parsed table per dex entry.

    Raises:
        dex.DexError: if any entry is not a readable dex file.
    """
    with zipfile.ZipFile(apk) as archive:
        blobs = [
            archive.read(name)
            for name in sorted(archive.namelist())
            if name.endswith(".dex")
        ]
    if not blobs:
        raise dex.DexError("the APK contains no dex bytecode")
    return [dex.DexFile(blob) for blob in blobs]


def _find_class(tables: list[dex.DexFile], descriptor: str) -> dex.DexClass | None:
    """Return the class with ``descriptor`` from any table, or ``None``.

    Args:
        tables: parsed dex tables, in load order.
        descriptor: type descriptor to look for.

    Returns:
        The matching class definition, or ``None`` when it was shrunk away.
    """
    for table in tables:
        entry = table.find(descriptor)
        if entry is not None:
            return entry
    return None


def _simple_name(descriptor: str) -> str:
    """Return the readable name of a type descriptor.

    Args:
        descriptor: type descriptor, for example ``Lcom/example/Thing;``.

    Returns:
        The dotted name without the surrounding ``L`` and ``;``.
    """
    return descriptor[1:-1].replace("/", ".").replace("$", ".")


def check_jni_callback_method(apk: Path) -> CheckResult:
    """Verify the JNI callback method survived shrinking unrenamed.

    The engine resolves `onProgress` by name from native code. R8 is free to
    rename it, and a renamed method turns every progress update into a swallowed
    `NoSuchMethodError`, so the check reads the class the engine calls.

    Args:
        apk: the artefact to inspect.

    Returns:
        The check result.
    """
    try:
        tables = _read_dex_tables(apk)
    except dex.DexError as error:
        return CheckResult(name="JNI callback method", passed=False, detail=str(error))

    entry = _find_class(tables, JNI_CALLBACK_CLASS)
    if entry is None:
        return CheckResult(
            name="JNI callback method",
            passed=False,
            detail="ProgressCallback was shrunk away",
        )
    if JNI_CALLBACK_METHOD not in entry.method_names:
        return CheckResult(
            name="JNI callback method",
            passed=False,
            detail=f"ProgressCallback has no {JNI_CALLBACK_METHOD} method "
            f"(methods: {', '.join(sorted(entry.method_names)) or 'none'})",
        )
    return CheckResult(
        name="JNI callback method",
        passed=True,
        detail=f"ProgressCallback.{JNI_CALLBACK_METHOD} is present",
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
        check_gson_models(apk),
        check_jni_callback_method(apk),
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
    parser.add_argument(
        "--apk",
        dest="apk_flag",
        type=Path,
        help="release APK to check; takes precedence over the positional argument",
    )
    args = parser.parse_args(argv)

    try:
        target = args.apk_flag or args.apk or Path("app/build/outputs/apk/release")
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
