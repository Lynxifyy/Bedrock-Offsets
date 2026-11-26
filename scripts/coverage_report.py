#!/usr/bin/env python3
"""Measure what an export actually names, from the export itself.

The manifest used to say "791,952 of 791,952 functions", which only counts
enumeration. This script answers the questions a modder asks instead:

  * how many functions carry a real name (not `sub_*`, not `Class::vfunc_N`)
  * how many of those are gameplay code rather than third-party libraries
  * how many functions have a signature, and how many of those are unique
  * how many vtable groups exist against how many class rows
  * which evidence source produced each name

It reads a finished export (the zip the app writes, or an unpacked directory),
streams one category at a time, and writes both a JSON block that belongs in
the manifest and a Markdown table for COVERAGE.md.

Usage:
    python3 scripts/coverage_report.py EXPORT.zip
    python3 scripts/coverage_report.py EXPORT.zip --json coverage.json --markdown COVERAGE.md
    python3 scripts/coverage_report.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import zipfile
from collections import Counter, defaultdict
from typing import Any, Dict, Iterable, Iterator, List, Optional, Tuple

# Library code that no Bedrock mod needs to navigate around. Used to separate
# "gameplay" from "third party" figures instead of averaging them together.
THIRD_PARTY_PREFIXES = (
    "std::", "__gnu_cxx::", "v8::", "cohtml::", "renoir::", "webrtc::", "rtc::",
    "absl::", "icu_", "Xal::", "boost::", "fmt::", "nlohmann::", "entt::",
    "crypto::", "leveldb::", "protobuf", "google::", "grpc::", "json::",
    "moodycamel::", "concurrentqueue", "spdlog::", "base::", "net::",
    "uv_", "libuv", "sqlite", "zlib", "miniz", "stb_", "nanosvg", "imgui",
)

PLACEHOLDER_PREFIX = "sub_"
PLACEHOLDER_MARKER = "::vfunc_"


def is_third_party(name: str) -> bool:
    return any(name.startswith(prefix) or prefix in name for prefix in THIRD_PARTY_PREFIXES)


def classify_name(name: str, source: str) -> str:
    """Return one of `real`, `placeholder`, `auto`."""
    if source == "auto" and name.startswith(PLACEHOLDER_PREFIX):
        return "auto"
    if PLACEHOLDER_MARKER in name:
        return "placeholder"
    if name.startswith(PLACEHOLDER_PREFIX):
        return "auto"
    return "real"


class Totals:
    """Counters for one bucket (a category, gameplay, third party, overall)."""

    def __init__(self) -> None:
        self.functions = 0
        self.real_named = 0
        self.placeholder = 0
        self.auto = 0
        self.signatures = 0
        self.signatures_unique = 0
        self.large_unsigned = 0
        self.classes = 0
        self.vtable_groups = 0
        self.sources: Counter = Counter()
        self.confidence: Counter = Counter()
        self.large_unsigned_bytes = 0

    def add_function(self, record: Dict[str, Any]) -> None:
        name = str(record.get("name") or "")
        source = str(record.get("nameSource") or record.get("name_source") or "auto")
        kind = classify_name(name, source)
        self.functions += 1
        if kind == "real":
            self.real_named += 1
            self.sources[source] += 1
        elif kind == "placeholder":
            self.placeholder += 1
        else:
            self.auto += 1
        confidence = record.get("confidence")
        if confidence:
            self.confidence[str(confidence)] += 1
        signature = record.get("signature")
        if signature:
            self.signatures += 1
            if record.get("signatureUnique") is True:
                self.signatures_unique += 1
        else:
            size = record.get("size") or 0
            if isinstance(size, int) and size > 128:
                self.large_unsigned += 1
                self.large_unsigned_bytes += size

    def add_class(self, record: Dict[str, Any]) -> None:
        self.classes += 1
        groups = record.get("vtableGroups") or []
        if isinstance(groups, list) and groups:
            self.vtable_groups += len(groups)
        elif record.get("vtable") not in (None, "", "0x0"):
            self.vtable_groups += 1

    def merge(self, other: "Totals") -> None:
        self.functions += other.functions
        self.real_named += other.real_named
        self.placeholder += other.placeholder
        self.auto += other.auto
        self.signatures += other.signatures
        self.signatures_unique += other.signatures_unique
        self.large_unsigned += other.large_unsigned
        self.large_unsigned_bytes += other.large_unsigned_bytes
        self.classes += other.classes
        self.vtable_groups += other.vtable_groups
        self.sources.update(other.sources)
        self.confidence.update(other.confidence)

    def as_dict(self) -> Dict[str, Any]:
        def pct(part: int, whole: int) -> Optional[float]:
            return round(100.0 * part / whole, 2) if whole else None

        return {
            "functions": self.functions,
            "realNamed": self.real_named,
            "realNamedPct": pct(self.real_named, self.functions),
            "placeholder": self.placeholder,
            "auto": self.auto,
            "signatures": self.signatures,
            "signaturesUnique": self.signatures_unique,
            "signaturesUniquePct": pct(self.signatures_unique, self.functions),
            "unsignedLargerThan128": self.large_unsigned,
            "classes": self.classes,
            "vtableGroups": self.vtable_groups,
            "nameSources": dict(self.sources.most_common()),
            "confidence": dict(self.confidence.most_common()),
        }


def _category_documents(source: str) -> Iterator[Tuple[str, Dict[str, Any]]]:
    """Yield `(member name, document)` for every category file in an export."""
    if os.path.isdir(source):
        for root, _dirs, files in os.walk(source):
            for name in sorted(files):
                if not name.endswith(".json") or name == "manifest.json":
                    continue
                path = os.path.join(root, name)
                if "InternalCPP" not in path and name != "internal_cpp.json":
                    continue
                try:
                    with open(path, "r", encoding="utf-8", errors="replace") as handle:
                        document = json.load(handle)
                except (json.JSONDecodeError, OSError):
                    continue
                if isinstance(document, dict) and document.get("kind") == "internal_cpp_category":
                    yield path, document
        return

    with zipfile.ZipFile(source) as archive:
        for info in archive.infolist():
            if info.filename == "manifest.json" or not info.filename.endswith(".json"):
                continue
            if "InternalCPP/" not in info.filename or info.file_size > 512 * 1024 * 1024:
                continue
            try:
                with archive.open(info) as handle:
                    document = json.load(handle)
            except (json.JSONDecodeError, zipfile.BadZipFile, OSError):
                continue
            if isinstance(document, dict) and document.get("kind") == "internal_cpp_category":
                yield info.filename, document


def measure(source: str) -> Dict[str, Any]:
    overall = Totals()
    gameplay = Totals()
    third_party = Totals()
    by_category: Dict[str, Dict[str, Any]] = {}
    components: Dict[str, Any] = {"recovered": 0, "source": "absent"}

    for member, document in _category_documents(source):
        category = str(document.get("category") or os.path.basename(member))
        label = str(document.get("label") or category)
        bucket = Totals()
        for record in (document.get("classes") or {}).values():
            if isinstance(record, dict):
                bucket.add_class(record)
        for record in document.get("functions") or []:
            if not isinstance(record, dict):
                continue
            bucket.add_function(record)
            name = str(record.get("name") or "")
            (third_party if is_third_party(name) else gameplay).add_function(record)
        for record in (document.get("classes") or {}).values():
            if isinstance(record, dict) and is_third_party(str(record.get("name") or "")):
                third_party.add_class(record)
            elif isinstance(record, dict):
                gameplay.add_class(record)
        overall.merge(bucket)
        by_category[category] = {"label": label, **bucket.as_dict()}

        registry = document.get("components")
        if isinstance(registry, dict):
            components = {
                "recovered": len(registry),
                "source": "category document",
            }

    return {
        "schema": 1,
        "source": source,
        "overall": overall.as_dict(),
        "gameplay": gameplay.as_dict(),
        "thirdParty": third_party.as_dict(),
        "categories": dict(sorted(by_category.items())),
        "components": components,
    }


def markdown(report: Dict[str, Any]) -> str:
    overall = report["overall"]
    gameplay = report["gameplay"]
    third = report["thirdParty"]

    def cell(value: Optional[float]) -> str:
        return "n/a" if value is None else f"{value:.2f} %"

    lines = [
        "# Coverage",
        "",
        "Measured by `scripts/coverage_report.py` from the export itself, not from",
        "the enumeration count. A name is real when it is neither `sub_<rva>` nor a",
        "`Class::vfunc_N` placeholder.",
        "",
        "| Bucket | Functions | Real names | Placeholder slots | Unnamed | Unique signature |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for label, bucket in (("Overall", overall), ("Gameplay", gameplay), ("Third party", third)):
        lines.append(
            f"| {label} | {bucket['functions']:,} | {bucket['realNamed']:,} "
            f"({cell(bucket['realNamedPct'])}) | {bucket['placeholder']:,} | {bucket['auto']:,} "
            f"| {bucket['signaturesUnique']:,} ({cell(bucket['signaturesUniquePct'])}) |"
        )
    lines += [
        "",
        f"Class rows: {overall['classes']:,} for {overall['vtableGroups']:,} vtable groups. "
        "Each group is its own identity; a row per group means nothing collapsed.",
        "",
        "## By category",
        "",
        "| Category | Functions | Real names | Unique signature |",
        "|---|---:|---:|---:|",
    ]
    for category, bucket in report["categories"].items():
        lines.append(
            f"| {bucket['label']} | {bucket['functions']:,} | "
            f"{bucket['realNamed']:,} ({cell(bucket['realNamedPct'])}) | "
            f"{bucket['signaturesUnique']:,} |"
        )
    sources = overall.get("nameSources") or {}
    if sources:
        lines += ["", "## Evidence sources", "", "| Source | Names |", "|---|---:|"]
        for source, count in sources.items():
            lines.append(f"| {source} | {count:,} |")
    lines.append("")
    return "\n".join(lines)


def _self_test() -> int:
    """Builds a tiny export with known answers and checks the arithmetic."""
    with tempfile.TemporaryDirectory() as directory:
        inner = os.path.join(directory, "InternalCPP")
        os.makedirs(inner)
        document = {
            "schemaVersion": 3,
            "kind": "internal_cpp_category",
            "category": "client_game",
            "label": "Client / game",
            "classes": {
                "Level": {"name": "Level", "vtableGroups": [{"slots": []}, {"slots": []}]},
                "std::vector<int>": {"name": "std::vector<int>", "vtableGroups": [{"slots": []}]},
            },
            "functions": [
                {"name": "Level::tick()", "nameSource": "symbol", "size": 40,
                 "signature": "aa bb", "signatureUnique": True, "confidence": "certain"},
                {"name": "Level::tick()::$_0::operator()", "nameSource": "lambda", "size": 400,
                 "signature": "cc dd", "signatureUnique": True, "confidence": "high"},
                {"name": "Player::vfunc_3", "nameSource": "vtable", "size": 90,
                 "signature": None, "signatureUnique": None},
                {"name": "sub_1234", "nameSource": "auto", "size": 900,
                 "signature": None, "signatureUnique": None},
                {"name": "std::__ndk1::function", "nameSource": "symbol", "size": 20,
                 "signature": "ee ff", "signatureUnique": False},
            ],
        }
        path = os.path.join(inner, "client_game.json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(document, handle)

        report = measure(directory)
        overall = report["overall"]
        assert overall["functions"] == 5, overall
        assert overall["realNamed"] == 3, overall
        assert overall["placeholder"] == 1, overall
        assert overall["auto"] == 1, overall
        assert overall["signaturesUnique"] == 2, overall
        assert overall["unsignedLargerThan128"] == 1, overall
        assert overall["classes"] == 2, overall
        assert overall["vtableGroups"] == 3, overall
        # std::__ndk1::function is third party, so gameplay keeps four functions
        # and three real names.
        assert report["gameplay"]["functions"] == 4, report["gameplay"]
        assert report["thirdParty"]["functions"] == 1, report["thirdParty"]
        assert "| Gameplay | 4 | 2" in markdown(report).replace(",", ""), markdown(report)
    print("coverage_report self-test: ok")
    return 0


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", nargs="?", help="export zip or unpacked directory")
    parser.add_argument("--json", help="write the JSON block here")
    parser.add_argument("--markdown", help="write the Markdown table here")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    if not args.export:
        parser.error("an export path is required unless --self-test is given")
    if not os.path.exists(args.export):
        print(f"no such export: {args.export}", file=sys.stderr)
        return 2

    report = measure(args.export)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)
            handle.write("\n")
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as handle:
            handle.write(markdown(report))
    overall = report["overall"]
    print(
        f"{overall['realNamed']:,} of {overall['functions']:,} functions named "
        f"({overall['realNamedPct']} %), "
        f"{overall['signaturesUnique']:,} unique signatures "
        f"({overall['signaturesUniquePct']} %), "
        f"{overall['classes']:,} class rows for {overall['vtableGroups']:,} groups"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
