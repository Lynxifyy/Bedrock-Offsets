# Bedrock Offset Extractor

An Android app that harvests memory offsets from a Minecraft Bedrock
`libminecraftpe.so` without ever loading the whole file into memory and without
ever executing it.

* **Kotlin / Jetpack Compose** for the UI, the storage-access plumbing and the
  scan orchestration.
* **Rust** for the engine: streaming extraction, zero-copy mapping, signature
  scanning, prologue verification and symbol cross-checking.
* **Python** for the build automation around both.

The app has no `INTERNET` permission. Nothing you scan leaves the device.

## Download

The latest release APK is on the
[releases page](https://github.com/Lynxifyy/Bedrock-Offsets/releases/latest):
`bedrock-offset-extractor-v1.0.3.apk`, signed and built for `arm64-v8a`.

Install it on the device with `adb install`, or open the file on the device and
allow installation from that source. The APK is signed with the debug key until
the repository has release-signing secrets, so an Android build that blocks
debug-signed packages will refuse it; see [Releasing](#releases).

## How it works

```
 SAF picker --fd--> Rust engine (mmap, read-only) --JSON--> Kotlin ViewModel --> Compose UI
 (Kotlin)           (offset_core)                     (Gson)     (StateFlow)        (stateless)
```

The APK is never copied into a `ByteArray`. Extraction streams through a fixed
64 KB buffer, the library is mapped read-only, and the scan walks 4 MB chunks in
parallel. Peak resident memory stays flat as the input grows, which is what lets
the app handle a 2 GB APK on a tablet. [`ARCHITECTURE.md`](ARCHITECTURE.md)
explains each stage and where the boundaries are.

## Building

```bash
./gradlew assembleDebug     # the Rust engine is built first by preBuild
./gradlew test              # Kotlin unit tests
./gradlew lintDebug         # Android lint, warnings are errors
```

The Rust engine is cross-compiled for `arm64-v8a` and staged in
`app/src/main/jniLibs/`, where the Android Gradle plugin packages it. A machine
without the Rust toolchain still builds and tests the Kotlin code: the staging
script warns and exits `0` when `cargo` or `cargo-ndk` is absent.

To build the engine alone:

```bash
python3 scripts/build_rust.py
```

To set up a bare Debian or Ubuntu host:

```bash
bash scripts/setup_toolchain.sh
```

## Output

A scan writes one JSON document, named `offsets_<BuildID>.json` by default. It
carries the identity of the library (SHA-256, build ID) so a loader can refuse
offsets from the wrong build, and a flat `offsets` map of name to address.
[`docs/OUTPUT_FORMAT.md`](docs/OUTPUT_FORMAT.md) documents every field and the
versioning rules.

A minimal loader reads only `offsets`:

```json
{
  "offsets": {
    "vtable:ClientInstance": "0x2c4a100",
    "pattern:frame_push_x29_x30": "0x1a2b3c",
    "symbol:Java_com_mojang_nativeTick": "0x1d0a4c8"
  }
}
```

The literal value `"-"` means the signature was expected but not found in this
build. Treat it as a failure, not as address zero.

## Adding a signature

Signatures live in `patterns.txt`, one per line, as `name = bytes`. The name is
the exported JNI symbol the signature belongs to, and the bytes are the shortest
byte string at that symbol that occurs exactly once in `.text`:

```
Java_com_mojang_minecraftpe_MainActivity_nativeResize = 6D 10 00 94 A8 1A 40 F9
Java_com_mojang_minecraftpe_MainActivity_isEduMode = E0 03 1F 2A C0 03 5F D6
```

Edit that file, then regenerate the pack the app loads:

```bash
python3 scripts/generate_patterns.py patterns.txt
```

Entries are sorted by name, so a regeneration produces a stable diff. CI runs
the same command with `--check` and fails if the committed pack is stale, which
is what stops `patterns.txt` and `patterns.json` from drifting apart.

### Why the names matter

A signature's name is not a label, it is a claim. The engine looks the name up
in the library's own `.dynsym` and compares the two addresses:

* they agree — the offset is `VERIFIED` and counts towards the confidence score;
* they disagree — the offset is `CONFLICT` and is logged as a warning;
* the name is not exported — the offset is `UNVERIFIED`, because nothing
  independent confirms it.

A pack of instruction-shape names (`frame_push_x29_x30`) matches thousands of
places, none of which can be checked, so every offset reads `UNVERIFIED` and the
confidence score is `0`. That is why the pack is derived from exported symbols
rather than written by hand.

## Deriving a signature pack

`scripts/derive_patterns.py` builds `patterns.txt` from a real
`libminecraftpe.so`, so the pack tracks the build instead of a guess:

```bash
python3 scripts/derive_patterns.py libminecraftpe.so patterns.txt
python3 scripts/derive_patterns.py libminecraftpe.so patterns.txt --check
```

It parses `.dynsym` for defined `Java_*` symbols and grows each signature from
the symbol's bytes until it is unique in `.text`. The result is a pack whose
every entry resolves to one address, which the engine then confirms against the
symbol table. `--check` verifies the committed `patterns.txt` is up to date.

Exit codes: `0` on success, `1` for a library that yields no signatures (or a
stale pack under `--check`), `2` for an unusable path.

## Build automation scripts

All five scripts are runnable on their own and are exercised by CI.

### `scripts/build_rust.py`

Cross-compiles the Rust engine for `arm64-v8a` and stages the shared object in
`app/src/main/jniLibs/arm64-v8a`, where the Android Gradle plugin picks it up.
It is wired into the Gradle `preBuild` task, so a normal `./gradlew assembleDebug`
builds the engine first.

```bash
python3 scripts/build_rust.py            # cross-compile and stage
python3 scripts/build_rust.py --offline  # use only the local registry cache
python3 scripts/build_rust.py --check-only  # validate the staged library
```

If `cargo` or `cargo-ndk` is absent the script warns and exits `0`, so a machine
that only needs the Kotlin code can still build. A build that runs and fails
exits `1`; a missing toolchain is not a failure, because nothing was attempted.

### `scripts/verify_offsets.py`

Compares two exported offset documents and prints what changed. Use it to check
a signature pack against a new Minecraft build before shipping it.

```bash
python3 scripts/verify_offsets.py offsets_old.json offsets_new.json
```

Exit codes: `0` when every offset matches, `1` when something changed, `2` when
an input cannot be read.

### `scripts/generate_patterns.py`

Builds `app/src/main/assets/patterns.json` from a readable source file
(`patterns.txt`), so signatures can be reviewed and extended without editing
JSON by hand. `??` matches any byte; entries are sorted by name so a
regeneration produces a stable diff.

```bash
python3 scripts/generate_patterns.py patterns.txt   # regenerate the pack
python3 scripts/generate_patterns.py patterns.txt --check  # fail if stale
```

Exit codes: `0` on success, `1` for a malformed entry or a stale pack under
`--check`, `2` for an unusable path.

### `scripts/verify_version.py`

Checks that a release tag and the `versionName` in `app/build.gradle.kts` say
the same thing. A tag and the version inside the APK are two statements about
one release, and nothing else notices when they drift.

```bash
python3 scripts/verify_version.py --tag v1.0.0
```

Exit codes: `0` when they agree, `1` when they disagree or the version cannot be
read, `2` for a bad argument. The release workflow runs it before assembling.

### `scripts/verify_release.py`

Checks the built release APK against the shipping budget: the size cap, the
packaged arm64-v8a engine, the absence of `Log.d`/`Log.v` call sites in the
bytecode, that every Gson model survived shrinking with a constructor, and the
absence of an `INTERNET` permission. The Gson check catches the release-only
failure where R8 strips a model's constructor and Gson then throws
"Abstract classes can't be instantiated!"; see
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md). CI runs it after
`assembleRelease`.

```bash
python3 scripts/verify_release.py                     # newest APK under app/build
python3 scripts/verify_release.py path/to/app.apk
```

Exit codes: `0` when every check passes, `1` when one fails, `2` when the APK
cannot be read.

### `scripts/changelog.py`

Builds a changelog from the commits between two revisions. Subjects are read as
conventional commits and grouped into sections, with breaking changes pulled to
the top; a subject that does not follow the convention lands in "Other" rather
than being dropped. The release workflow uses it to write the notes for a tag.

```bash
python3 scripts/changelog.py --version v1.0.0 --revision v0.9.0..v1.0.0
python3 scripts/changelog.py --version v1.0.0 --output CHANGELOG.md
```

The repository slug for commit links is read from the `origin` remote, or given
with `--repository-slug`. Exit codes: `0` on success, `2` when git cannot answer.

### Release signing

`assembleRelease` produces an unsigned APK unless signing material is supplied.
Either add a `keystore.properties` at the repository root (it is git-ignored) or
set `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD`. Without them the build warns and falls back to the debug
key so the APK is still installable.

The release workflow reads the keystore from the `SIGNING_KEYSTORE_BASE64`
secret, decodes it into the runner's temporary directory, and then runs
`apksigner verify` to prove the result really is signed. Without that secret the
build still publishes, with a warning in the run summary.

## Releases

Pushing a `v*` tag runs [`.github/workflows/release.yml`](.github/workflows/release.yml):
it cross-compiles the engine, assembles the release APK, verifies it against the
shipping budget, generates the notes with `scripts/changelog.py` and attaches the
APK to a GitHub Release.

```bash
git tag -a v1.0.0 -m "Bedrock Offset Extractor v1.0.0"
git push origin v1.0.0
```

### Running the script tests

```bash
python3 -m unittest discover -s scripts/tests -t . -v
```

## Supported versions

The engine is build-agnostic: it reads whatever `libminecraftpe.so` is in the
APK and reports offsets for that build. Support for a specific Minecraft Bedrock
version therefore comes from the signature pack, not from the app.

| Bedrock version | Signature pack | Notes |
|---|---|---|
| Any `arm64-v8a` build | Parsed and scanned | Offsets are reported for the build you scanned. |
| Any 32-bit or x86 build | Rejected | "Only arm64-v8a is supported." |
| Java Edition | Rejected | No `libminecraftpe.so` to read. |

A shipped pack cannot match every future build. When offsets come back `"-"`,
re-derive the signature from the new library and regenerate the pack; the
`stats` block in the output tells you whether the pack matched at all or was
rejected by the prologue check.

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) -- the pipeline, the JNI boundary and the
  memory budget.
- [`docs/OUTPUT_FORMAT.md`](docs/OUTPUT_FORMAT.md) -- the JSON a loader reads.
- [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) -- JNI crashes, empty
  scans and build problems.
- [`docs/QA_CHECKLIST.md`](docs/QA_CHECKLIST.md) -- what CI proves and the
  device pass it cannot replace.
- [`SECURITY.md`](SECURITY.md) -- the threat model and how to report a problem.

## License

Apache 2.0. See [`LICENSE`](LICENSE).
