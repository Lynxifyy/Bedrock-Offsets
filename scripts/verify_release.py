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
    * the manifest declares INTERNET, which the APK catalogue needs.

Exit codes:
    0  every check passed.
    1  a check failed.
    2  the APK could not be read.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
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
    "Lcom/chimerateam/offsetextractor/data/native/InternalSummary;",
    "Lcom/chimerateam/offsetextractor/data/native/InternalFunction;",
    "Lcom/chimerateam/offsetextractor/data/native/VtableSlot;",
    "Lcom/chimerateam/offsetextractor/data/native/ClassInfo;",
    "Lcom/chimerateam/offsetextractor/data/native/InternalAnalysisPayload;",
    "Lcom/chimerateam/offsetextractor/data/native/InternalPatternSpec;",
    "Lcom/chimerateam/offsetextractor/data/native/StringAnchorSpec;",
    # data/export -- the export job and the category rules. The JNI document is
    # written with JsonWriter, so it has no model class to protect any more.
    "Lcom/chimerateam/offsetextractor/data/export/PersistedExportJob;",
    "Lcom/chimerateam/offsetextractor/data/export/ExportProgress;",
    "Lcom/chimerateam/offsetextractor/data/export/CategoryRule;",
    # data/cache -- the cached scan summary.
    "Lcom/chimerateam/offsetextractor/data/cache/ResultCache$Envelope;",
    # data/patterns -- the bundled signature pack.
    "Lcom/chimerateam/offsetextractor/data/patterns/SignaturePack$PackDocument;",
    "Lcom/chimerateam/offsetextractor/data/patterns/SignaturePack$Entry;",
    "Lcom/chimerateam/offsetextractor/data/patterns/SignaturePack$InternalEntry;",
    "Lcom/chimerateam/offsetextractor/data/patterns/SignaturePack$AnchorEntry;",
    # ui/state -- the cached summary's own models.
    "Lcom/chimerateam/offsetextractor/ui/state/ScanResultSummary;",
    "Lcom/chimerateam/offsetextractor/ui/state/ScannerUiState;",
    "Lcom/chimerateam/offsetextractor/ui/state/BatchProgress;",
    "Lcom/chimerateam/offsetextractor/ui/state/BatchSummary;",
)


# Characters allowed in an offsets ZIP entry name. The exporter never uses `+`,
# so a plus in an entry is a naming regression rather than a style choice.
ZIP_ENTRY_PATTERN = re.compile(r"^[A-Za-z0-9._\-/]+$")


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
        name="network access",
        passed=present,
        detail="declared" if present else "the manifest does not request INTERNET",
    )


def find_offsets_zip(root: Path) -> Path:
    """Locate a ``BEDROCK_OFFSETS_*.zip`` under ``root``.

    Args:
        root: directory to search.

    Returns:
        The newest matching archive.

    Raises:
        ReleaseCheckError: if no archive exists.
    """
    candidates = sorted(
        root.rglob("BEDROCK_OFFSETS_*.zip"),
        key=lambda path: path.stat().st_mtime,
    )
    if not candidates:
        raise ReleaseCheckError(f"no BEDROCK_OFFSETS_*.zip found under {root}")
    return candidates[-1]


def run_zip_checks(archive_path: Path) -> list[CheckResult]:
    """Verify a downloaded offsets ZIP against its own manifest.

    Args:
        archive_path: the archive to inspect.

    Returns:
        One result per check, in report order.

    Raises:
        ReleaseCheckError: if the archive has no manifest.
    """
    results: list[CheckResult] = []
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        if "manifest.json" not in names:
            raise ReleaseCheckError(f"{archive_path} has no manifest.json")
        manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        version = str(manifest.get("version", ""))
        files = manifest.get("files", [])
        listed = {item.get("path") for item in files if isinstance(item, dict)}

        results.append(
            CheckResult(
                name="zip manifest count",
                passed=int(manifest.get("entryCount", -1)) == len(files),
                detail=f"{len(files)} files, entryCount={manifest.get('entryCount')}",
            )
        )

        unsafe = [name for name in names if not ZIP_ENTRY_PATTERN.match(name)]
        results.append(
            CheckResult(
                name="zip entry names",
                passed=not unsafe,
                detail="clean" if not unsafe else f"unsafe: {', '.join(unsafe[:5])}",
            )
        )

        required = [
            "README.txt",
            f"JNI/JNI_OFFSETS_{version}.json",
            f"InternalCPP/INTERNALCPP_INDEX_{version}.json",
        ]
        missing = [name for name in required if name not in names]
        results.append(
            CheckResult(
                name="zip versioned entries",
                passed=not missing,
                detail="present" if not missing else f"missing {', '.join(missing)}",
            )
        )

        results.append(_coverage_check(manifest))
        results.append(_completeness_check(manifest, archive, names))

        unlisted = [name for name in names if name != "manifest.json" and name not in listed]
        results.append(
            CheckResult(
                name="zip manifest coverage",
                passed=not unlisted,
                detail="every entry is listed"
                if not unlisted
                else f"unlisted: {', '.join(unlisted[:5])}",
            )
        )

        size_bad: list[str] = []
        hash_bad: list[str] = []
        crc_bad: list[str] = []
        for item in files:
            if not isinstance(item, dict):
                continue
            name = item.get("path")
            if not name or name not in names:
                continue
            digest = hashlib.sha256()
            total = 0
            try:
                with archive.open(name) as handle:
                    while True:
                        chunk = handle.read(64 * 1024)
                        if not chunk:
                            break
                        digest.update(chunk)
                        total += len(chunk)
            except zipfile.BadZipFile:
                crc_bad.append(name)
                continue
            if total != int(item.get("size", -1)):
                size_bad.append(name)
            if digest.hexdigest() != item.get("sha256"):
                hash_bad.append(name)
        results.append(
            CheckResult(
                name="zip CRCs",
                passed=not crc_bad,
                detail="clean" if not crc_bad else f"bad CRC: {', '.join(crc_bad[:5])}",
            )
        )
        results.append(
            CheckResult(
                name="zip sizes",
                passed=not size_bad,
                detail="match" if not size_bad else f"mismatch: {', '.join(size_bad[:5])}",
            )
        )
        results.append(
            CheckResult(
                name="zip digests",
                passed=not hash_bad,
                detail="match" if not hash_bad else f"mismatch: {', '.join(hash_bad[:5])}",
            )
        )
    return results


def _coverage_check(manifest: dict) -> CheckResult:
    """Check that the manifest carries both coverage blocks.

    The archive is only complete if the internal function count was compared
    with the ``.eh_frame`` frames and the JNI union was compared with its
    sources, so both blocks must be present.

    Args:
        manifest: the parsed ``manifest.json``.

    Returns:
        The check result.
    """
    coverage = manifest.get("coverage")
    if not isinstance(coverage, dict):
        return CheckResult("zip coverage", False, "the manifest has no coverage block")
    internal = coverage.get("internal")
    jni = coverage.get("jni")
    if not isinstance(internal, dict) or not isinstance(jni, dict):
        return CheckResult("zip coverage", False, "coverage is missing a half")
    truncated = bool(internal.get("truncated")) or bool(jni.get("truncated"))
    matches = bool(internal.get("functionsMatchFdes", True))
    detail = (
        f"{internal.get('functions')} functions, {internal.get('fdeFunctions')} FDEs, "
        f"{jni.get('union')} JNI offsets, truncated={truncated}"
    )
    return CheckResult("zip coverage", matches and not truncated, detail)


def _count_category_rows(archive: zipfile.ZipFile, name: str) -> int:
    """Count the records inside one category document.

    Args:
        archive: open archive.
        name: entry to read.

    Returns:
        Classes plus functions plus symbols in the entry.

    Raises:
        zipfile.BadZipFile: when the entry cannot be decompressed.
    """
    with archive.open(name) as handle:
        document = json.load(handle)
    if not isinstance(document, dict):
        return 0
    rows = 0
    for key in ("classes", "functions", "symbols"):
        section = document.get(key)
        if isinstance(section, (dict, list)):
            rows += len(section)
    return rows


def _completeness_check(
    manifest: dict, archive: zipfile.ZipFile, names: list[str]
) -> CheckResult:
    """Check that the archive holds every row the manifest promises.

    Re-reads each category document and compares the count with the manifest's
    own number and with the scan's expected row total, so an archive that only
    describes itself cannot pass.

    Args:
        manifest: the parsed ``manifest.json``.
        archive: open archive.
        names: every entry name in the archive.

    Returns:
        The check result.
    """
    completeness = manifest.get("completeness")
    categories = manifest.get("categories")
    if not isinstance(completeness, dict) or not isinstance(categories, list):
        return CheckResult("zip completeness", False, "no completeness block")
    expected = int(completeness.get("expectedRows", -1))
    written = int(completeness.get("writtenRows", -1))
    listed = {item.get("path") for item in manifest.get("files", []) if isinstance(item, dict)}
    total = 0
    bad: list[str] = []
    for item in categories:
        if not isinstance(item, dict):
            continue
        name = item.get("file")
        if not name:
            # Older archives do not record the file name per category; match by
            # the category identifier instead.
            matches = [n for n in names if f"INTERNALCPP_{str(item.get('id', '')).upper()}_" in n]
            name = sorted(matches)[0] if matches else None
        if not name or name not in listed:
            continue
        counted = _count_category_rows(archive, name)
        total += counted
        if counted != int(item.get("entries", -1)):
            bad.append(f"{name}: {counted} rows, manifest says {item.get('entries')}")
    passed = not bad and total == expected == written and bool(completeness.get("complete"))
    detail = f"{total} of {expected} rows re-read"
    if bad:
        detail = "; ".join(bad[:3])
    return CheckResult("zip completeness", passed, detail)


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
        if target.is_dir():
            target = (
                find_offsets_zip(target)
                if any(target.rglob("BEDROCK_OFFSETS_*.zip"))
                else find_apk(target)
            )
        if not target.is_file():
            raise ReleaseCheckError(f"{target} does not exist")
        is_offsets_zip = target.suffix.lower() == ".zip" and zipfile.is_zipfile(target)
        if is_offsets_zip:
            with zipfile.ZipFile(target) as archive:
                if "manifest.json" in archive.namelist():
                    results = run_zip_checks(target)
                    print(format_report(target, results), end="")
                    return 0 if all(result.passed for result in results) else 1
        results = run_checks(target)
    except (ReleaseCheckError, zipfile.BadZipFile) as error:
        print(f"[verify_release] ERROR: {error}", file=sys.stderr)
        return 2

    print(format_report(target, results), end="")
    return 0 if all(result.passed for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
