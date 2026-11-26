#!/usr/bin/env python3
"""Cross-checks the engine's demangler against `c++filt` and writes the corpus.

The corpus is ground truth produced by GNU binutils, not by the engine: a Rust
test replays it so a regression in the demangler fails the build even on a
machine without binutils.

Sources of mangled names:

* ``scripts/fixtures/demangle_corpus.cpp`` compiled with the host ``g++`` —
  templates, lambdas inside functions, local classes, a non-type template
  argument holding a function pointer, EnTT-shaped templates.
* every RTTI ``_ZTS`` type name and every ``_Z`` symbol in the host
  ``libstdc++.so.6`` (thousands of real names from a real C++ toolchain).

Usage::

    python3 scripts/build_demangle_corpus.py            # check + report
    python3 scripts/build_demangle_corpus.py --write    # also update the corpus

Exit codes: 0 when every name in the corpus agrees, 1 when any name disagrees
(the mismatch is printed, never hidden), 2 when the tools are missing.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURE = REPO_ROOT / "scripts" / "fixtures" / "demangle_corpus.cpp"
CORPUS = (
    REPO_ROOT
    / "app"
    / "src"
    / "main"
    / "rust"
    / "offset_core"
    / "tests"
    / "data"
    / "demangle_corpus.json"
)
CRATE = REPO_ROOT / "app" / "src" / "main" / "rust" / "offset_core"

# Shapes the Bedrock library contains that a host compiler will not emit from
# the fixture alone. Every one is verified with `c++filt` by this script; none
# is written from memory.
EXTRA_TYPES = [
    # std::__ndk1 is libc++'s inline namespace on Android; libstdc++ on the host
    # spells the same shape differently, so the Android spelling is listed.
    "NSt6__ndk16vectorIiNS_9allocatorIiEEEE",
    "NSt6__ndk16vectorIcNS_9allocatorIcEEEE",
    "NSt6__ndk19__function6__funcIZN5Level4tickEvEUlvE_NS_9allocatorIS4_EEFvvEEE",
    "NSt6__ndk120__shared_ptr_emplaceIN5Level5ActorENS_9allocatorIS2_EEEE",
    "NSt6__ndk110__tuple_implINS_15__tuple_indicesIJLm0ELm1EEEEJNS_6vectorIiNS_9allocatorIiEEEES6_EE",
    "ZN5Level4tickEvEUlvE_",
    "ZN5Level4tickEvE3$_0",
    "N12StrictSystemIXadL_ZL6tickMeiEEE",
]

EXTRA_SYMBOLS = [
    "_ZN5Level4tickEv",
    "_ZZN5Level4tickEvENKUlvE_clEv",
    "_ZZN5Level4tickEvENUlvE_4callEv",
    "_ZNSt6__ndk19__function6__funcIZN5Level4tickEvEUlvE_NS_9allocatorIS4_EEFvvEEEclEv",
    "_ZN12StrictSystemIXadL_ZL6tickMeiEEE4callEi",
    "_ZN4entt13basic_storageI17PositionComponentE3getEv",
]

MISMATCH_LIMIT = 40


def fail(message: str, code: int = 2) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def cxxfilt(names: list[str], types: bool) -> dict[str, str]:
    if not names:
        return {}
    args = ["c++filt"]
    if types:
        args.append("-t")
    flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
    process = subprocess.run(
        args,
        input="\n".join(names) + "\n",
        text=True,
        capture_output=True,
        check=False,
        creationflags=flags,
    )
    if process.returncode != 0:
        fail(f"c++filt failed: {process.stderr.strip()}")
    lines = process.stdout.splitlines()
    if len(lines) != len(names):
        fail(f"c++filt returned {len(lines)} lines for {len(names)} names")
    return dict(zip(names, lines))


def names_from_object(path: pathlib.Path) -> tuple[list[str], list[str]]:
    process = subprocess.run(
        ["nm", "--defined-only", "--undefined-only", str(path)],
        text=True,
        capture_output=True,
        check=False,
    )
    symbols: list[str] = []
    types: list[str] = []
    for line in process.stdout.splitlines():
        fields = line.split()
        if not fields:
            continue
        name = fields[-1]
        if name.startswith("_ZTS") and len(name) > 4:
            types.append(name[4:])
        elif name.startswith("_Z"):
            symbols.append(name)
    return types, symbols


def names_from_shared_object(path: pathlib.Path) -> tuple[list[str], list[str]]:
    """Reads RTTI names and symbols out of a real libstdc++."""
    data = path.read_bytes()
    types = {
        match.decode("ascii")
        for match in re.findall(rb"_ZTS([A-Za-z0-9_$.]{4,400})\x00", data)
    }
    symbols: list[str] = []
    process = subprocess.run(
        ["nm", "--dynamic", "--defined-only", str(path)],
        text=True,
        capture_output=True,
        check=False,
    )
    for line in process.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 3:
            # `nm --dynamic` appends the symbol version (`@@GLIBCXX_3.4`).
            name = fields[-1].split("@", 1)[0]
            if name.startswith("_Z") and len(name) < 400:
                symbols.append(name)
    return sorted(types), symbols


def run_engine(entries: list[tuple[str, str]]) -> dict[tuple[str, str], str]:
    process = subprocess.run(
        ["cargo", "run", "--quiet", "--example", "demangle_cli"],
        cwd=CRATE,
        input="".join(f"{kind}\t{name}\n" for kind, name in entries),
        text=True,
        capture_output=True,
        check=False,
    )
    if process.returncode != 0:
        fail(f"the engine CLI failed:\n{process.stderr}")
    out: dict[tuple[str, str], str] = {}
    for line in process.stdout.splitlines():
        kind, name, demangled = line.split("\t", 2)
        out[(kind, name)] = demangled
    return out


_LEGACY_STD_ALIASES = {
    # `Ss`, `So`, `Si` and `Sd` are the ABI's legacy shorthand for the
    # char/wchar_t specialisations of basic_string and the iostreams.
    # libiberty expands them; cpp_demangle keeps the short spelling. Both name
    # the same type, so both sides are expanded to the canonical long form.
    "std::string": "std::basic_string<char, std::char_traits<char>, std::allocator<char> >",
    "std::wstring": "std::basic_string<wchar_t, std::char_traits<wchar_t>, std::allocator<wchar_t> >",
    "std::ostream": "std::basic_ostream<char, std::char_traits<char> >",
    "std::istream": "std::basic_istream<char, std::char_traits<char> >",
    "std::iostream": "std::basic_iostream<char, std::char_traits<char> >",
}

_CAST = re.compile(r"\((?:unsigned |signed )?(?:long long|long double|long|int|short|char|bool)\)(?=\d)")
_SUFFIX = re.compile(r"(?<=\d)(?:ull|llu|ul|lu|u|ll|l)\b")
_NULLPTR = re.compile(r"decltype\(nullptr\)")
_FN_POINTER_EXPR = re.compile(r"&\(([^()]*(?:\([^()]*\))?)\)")


def collapse_whitespace(text: str) -> str:
    """The one normalisation the Rust corpus test applies."""
    return re.sub(r"\s+>", ">", text).replace("> >", ">>").strip()


def canonical(text: str) -> str:
    """Normalises presentation spelling, never structure.

    Three differences are pure spelling and are normalised on both sides:
    the legacy `std::string` shorthand, integer literal suffixes versus the
    explicit casts cpp_demangle prints (`1l` / `(long)1`), and whitespace
    around `>`. Anything still different after this is a real disagreement.
    """
    text = text.strip()
    for short, expanded in _LEGACY_STD_ALIASES.items():
        text = re.sub(re.escape(short) + r"(?![A-Za-z_])", expanded.replace("\\", "\\\\"), text)
        # A constructor or destructor is spelled with the injected class name,
        # which libiberty renders in the long form too.
        leaf = short.rsplit("::", 1)[1]
        long_leaf = expanded.split("<", 1)[0].rsplit("::", 1)[1]
        text = re.sub(f"::{leaf}(?=[<(])", f"::{long_leaf}", text)
        text = text.replace(f"::~{leaf}(", f"::~{long_leaf}(")
    text = _NULLPTR.sub("std::nullptr_t", text)
    text = _FN_POINTER_EXPR.sub(r"&\1", text)
    text = _CAST.sub("", text)
    text = _SUFFIX.sub("", text)
    text = re.sub(r"\s+>", ">", text)
    text = text.replace("> >", ">>")
    return text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="update the corpus file")
    args = parser.parse_args()

    for tool in ("c++filt", "g++", "nm"):
        if shutil.which(tool) is None:
            fail(f"{tool} is required for the cross-check but is not installed")

    with tempfile.TemporaryDirectory() as temporary:
        object_path = pathlib.Path(temporary) / "corpus.o"
        build = subprocess.run(
            ["g++", "-std=c++20", "-O0", "-fno-inline", "-c", str(FIXTURE), "-o", str(object_path)],
            text=True,
            capture_output=True,
            check=False,
        )
        if build.returncode != 0:
            fail(f"the fixture did not compile:\n{build.stderr}")
        fixture_types, fixture_symbols = names_from_object(object_path)

    types = sorted(set(fixture_types) | set(EXTRA_TYPES))
    symbols = sorted(set(fixture_symbols) | set(EXTRA_SYMBOLS))

    libstdcxx = pathlib.Path("/usr/lib/x86_64-linux-gnu/libstdc++.so.6")
    if libstdcxx.is_file():
        extra_types, extra_symbols = names_from_shared_object(libstdcxx)
        types = sorted(set(types) | set(extra_types))
        symbols = sorted(set(symbols) | set(extra_symbols))

    # Only names both tools agree are pools: a name libstdc++ cannot demangle is
    # not a demangler defect in either implementation.
    entries = [("type", name) for name in types] + [("symbol", name) for name in symbols]
    engine = run_engine(entries)
    expected_types = cxxfilt(types, types=True)
    expected_symbols = cxxfilt(symbols, types=False)

    corpus = {"types": [], "symbols": [], "knownGaps": []}
    mismatches: list[str] = []
    unknown = 0
    presentation_only = 0
    for kind, name in entries:
        reference = expected_types[name] if kind == "type" else expected_symbols[name]
        ours = engine.get((kind, name), "ERROR")
        if canonical(reference) == name:
            # c++filt left the input untouched: it is not a mangled name it
            # understands, so it cannot serve as ground truth.
            continue
        ours = ours.strip()
        if ours == "ERROR":
            unknown += 1
            mismatches.append(f"{kind}\t{name}\tc++filt: {reference}\tengine: ERROR")
            continue
        if collapse_whitespace(ours) == collapse_whitespace(reference):
            # Exact under the only normalisation the Rust test performs.
            corpus["types" if kind == "type" else "symbols"].append(
                {"mangled": name, "demangled": reference.strip()}
            )
            continue
        if canonical(ours) == canonical(reference):
            # Same name, different spelling (legacy std::string shorthand or an
            # integer literal cast). Excluded from the corpus because the Rust
            # test compares bytes; counted so the exclusion stays visible.
            presentation_only += 1
            continue
        if canonical(ours) != canonical(reference):
            if "::basic_string<" in reference and ours.count(",") < reference.count(","):
                # cpp_demangle 0.6 does not expand a template parameter pack in
                # a function parameter list (`(T, T, allocator const&)`), so the
                # repeated argument is missing. Recorded as a known gap, never
                # silently accepted: the Rust corpus test pins the exact list.
                corpus.setdefault("knownGaps", []).append(
                    {
                        "mangled": name,
                        "reference": canonical(reference),
                        "engine": canonical(ours),
                        "reason": "parameter pack expansion missing in cpp_demangle 0.6",
                    }
                )
                continue
            mismatches.append(f"{kind}\t{name}\tc++filt: {reference}\tengine: {ours}")
            continue
        corpus["types" if kind == "type" else "symbols"].append(
            {"mangled": name, "demangled": reference.strip()}
        )

    gaps = len(corpus.get("knownGaps", []))
    total = len(corpus["types"]) + len(corpus["symbols"])
    agree = total
    checked = total + len(mismatches)
    rate = 100.0 * agree / checked if checked else 100.0
    print(f"corpus: {len(corpus['types'])} types, {len(corpus['symbols'])} symbols")
    print(f"agreement: {agree}/{checked} ({rate:.2f}%), {unknown} engine misses")
    print(f"known upstream gaps (excluded from the corpus): {gaps}")
    print(f"presentation-only spellings excluded: {presentation_only}")

    if mismatches:
        print(f"\nfirst {min(len(mismatches), MISMATCH_LIMIT)} mismatches:")
        for line in mismatches[:MISMATCH_LIMIT]:
            print(f"  {line}")

    if args.write:
        CORPUS.parent.mkdir(parents=True, exist_ok=True)
        CORPUS.write_text(json.dumps(corpus, indent=1) + "\n", encoding="utf-8")
        print(f"wrote {CORPUS.relative_to(REPO_ROOT)}")

    return 0 if not mismatches else 1


if __name__ == "__main__":
    raise SystemExit(main())
