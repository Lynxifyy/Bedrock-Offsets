# Bedrock Offset Extractor

An Android app that harvests memory offsets from a Minecraft Bedrock
(`libminecraftpe.so`) library without ever loading the whole file into memory
and without ever executing it.

* **Kotlin / Jetpack Compose** for the UI, the storage-access plumbing and the
  scan orchestration.
* **Rust** for the engine: streaming extraction, zero-copy mapping, signature
  scanning, prologue verification and symbol cross-checking.
* **Python** for the build automation around both.

## Build automation scripts

All three scripts are runnable on their own and are exercised by CI.

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

If `cargo` is absent the script warns and exits `0`, so a machine that only
needs the Kotlin code can still build. A build that runs and fails exits `1`.

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

### Running the script tests

```bash
python3 -m unittest discover -s scripts/tests -t . -v
```
