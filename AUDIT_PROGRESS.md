# Audit progress

Legend: **read** = read end to end in this session, **targeted** = read the
parts the work touched plus every call site, **not read** = still unread. I do
not mark a file read that I only grepped.

## Rust engine — `app/src/main/rust/offset_core`

| File | Status | Notes |
| --- | --- | --- |
| `Cargo.toml` | read | no `panic = "abort"`; `lto = "fat"`, `strip = true` |
| `build.rs` | read | NDK target wiring |
| `src/lib.rs` | read | module list |
| `src/internal.rs` | targeted | Part 2 pipeline; summary, write loop, `unwrap_or(0)` at :710, slot truncation at :964–1055, `insert_slot_names` at :1255 (**fixed**: a slot that holds data is no longer named) |
| `src/shards.rs` | targeted | `ShardWriter`, size read-back after close |
| `src/jni_bridge.rs` | targeted | `File::create`, JSON keys, summary serialisation |
| `src/jni.rs` | targeted | report parsing and progress |
| `src/jninatives.rs` | targeted | `RegisterNatives` table |
| `src/categories.rs` | read | **fixed** namespace prefix boundary + tests |
| `src/elf.rs` | targeted | PT_LOAD / section mapping, VA→file offset |
| `src/sections.rs` | targeted | section enumeration, `.bss`, **fixed**: `image_base` came from the first allocated section (`.gnu.hash`, 0x2463f0) instead of the first `PT_LOAD` (0) |
| `src/rtti.rs` | targeted | vtable resolution, secondary vtables, slots |
| `src/symbols.rs` | targeted | `.dynsym`/`.symtab`, hash tables |
| `src/patterns.rs` | targeted | pattern parse, wildcards, 4 MB chunks |
| `src/scanner/{mod,chunk,matcher,verifier}.rs` | targeted | chunk boundary handling |
| `src/verify.rs` | targeted | symbol cross-check |
| `src/prologue.rs` | targeted | prologue classification |
| `src/progress.rs` | read | monotonic clamp |
| `src/memory.rs` | read | mmap + `madvise` releaser |
| `src/fd.rs` | read | fd ownership |
| `src/zip_extract.rs` | targeted | APK entry extraction |
| `src/error.rs` | read | `CoreError` → `EngineErrorKind` |
| `src/android.rs` | read | `catch_unwind` wrapper |
| `tests/internal_pipeline_test.rs` | read | end-to-end Part 2 |
| `tests/host_shard_measure.rs` | read | real-library measurement |
| `tests/host_pattern_measure.rs` | read | pattern inventory |
| other `tests/*.rs` | not read | not touched by this work |

## Kotlin — `app/src/main/java/com/chimerateam/offsetextractor`

| File | Status | Notes |
| --- | --- | --- |
| `ui/viewmodel/MainViewModel.kt` | targeted | `requireInternalOutput`, retry, Part 1 preservation |
| `ui/state/InternalFailure.kt` | read | reason + retryable mapping |
| `ui/state/ScannerUiState.kt` | read | `internalRetryable` |
| `ui/screens/ScannerScreen.kt` | targeted | failure card |
| `ui/screens/ResultsScreen.kt` | targeted | **fixed** index construction |
| `data/export/InternalIndex.kt` | read | **rewritten** to stream category documents |
| `data/export/InternalTable.kt` | read | `problem()` validation |
| `data/export/InternalTableSplitter.kt` | read | split + totals |
| `data/export/OffsetZip.kt` | targeted | manifest counts, coverage fields |
| `data/export/OffsetExporter.kt` | targeted | text export |
| `data/export/Categories.kt` | read | **fixed** namespace boundary |
| `data/export/OffsetRows.kt` | read | `OffsetRow`, `OffsetCategory` |
| `data/export/CategoryRulesJson.kt` | targeted | rule import/export |
| `data/native/InternalModels.kt` | read | `InternalSummary`, `ShardPart`, `InternalCoverage` |
| `data/native/JniReport.kt` | targeted | Gson field names vs Rust keys |
| `data/native/EnginePayloads.kt` | targeted | payload shapes |
| `data/native/NativeLib.kt` | targeted | `external fun` list |
| `data/storage/StorageManager.kt` | targeted | preflight + eviction |
| `export/ExportService.kt` | targeted | export job wiring |
| `data/cache/ScanCache.kt`, `ResultCache.kt` | targeted | cache keys |
| other UI files | not read | not touched by this work |

## Assets, scripts, build

| File | Status | Notes |
| --- | --- | --- |
| `assets/categories.json` | read | **changed**: `ecs`, `scripting`, `OreUI`, third-party list |
| `assets/patterns.json` | targeted | 91 patterns + 2 internal (measured) |
| `scripts/build_rust.py` | read | **fixed** skip-or-fail |
| `scripts/tests/test_build_rust.py` | read | **fixed** test contract + two cases |
| `app/build.gradle.kts` | targeted | `versionName = "1.0.12"`, jniLibs staging |
| `.github/workflows/android.yml` | read | installs `cargo-ndk` + NDK, so the gate is safe |
| `README.md` | read | **fixed** stale `v1.0.6` claim |
| `scripts/derive_patterns.py`, `generate_patterns.py` | targeted | run + diff check |
| `AUDIT_REPORT.md` | written | findings |

## Done in phase E-lite (26.60 real-library audit)

- `fix(internal): stop writing offset 0 for rows that have no bytes in the file`
  — 814 rows carried `fileOffset: "0x0"`, which is the ELF header.
- `fix(internal): give every class record a real file offset` — `vtableOffset` is
  image-base-relative, not a file offset; the class record now carries both.
- `fix(internal): never name a vtable slot that holds data` — 91,622 of the
  883,574 "functions" were not code (79,366 in `.rodata`, marked `verified`).
  The table now holds 791,952 rows, exactly one per FDE.
- `fix(sections): take the load base from PT_LOAD, not from the first section` —
  every `sub_<hex>` name and every `vtableOffset` was shifted by 0x2463f0.
- `feat(caramel): build and validate the 26.60 offset pack` — 35 requested items,
  13 found, 22 missing with the attempts recorded; schema, validator, ground
  truth checker, two documents.

### Measurements, phase E-lite

```
cargo test --release --test host_shard_measure -- --ignored --nocapture
coverage: functions=791952 fdes=791952 missing=0 slots=865399
          truncated=false withoutFileOffset=0
classes=6386  peak resident: 1079 MB

python3 scripts/verify_library_ground_truth.py --library libminecraftpe.so \
    --output-dir out-shards7/categories-measure
classes: 6386, type_info slot ok=6386 wrong=0 missing=0
virtual slots: 157188, ok=157188 wrong=0 missing=0
functions: 791952, outside .text: 0
fdes in .eh_frame_hdr: 791952, difference from functions: 0

python3 scripts/caramel_pack.py ... --out caramel/offsets_26.60.json
caramel/offsets_26.60.json: 35 items, 13 found, 0 partial, 22 missing
python3 scripts/validate_caramel_pack.py caramel/offsets_26.60.json --library libminecraftpe.so
caramel/offsets_26.60.json: ok, 35 items (13 found, 0 partial, 22 missing)
```

### Done in phase E-lite, step 1 (base/version consistency)

- `feat(engine): version the engine output and key the cache on base and engine`
  — `engine_version()` = crate version + `ENGINE_SCHEMA_VERSION` (`"0.1.0+2"`),
  carried in `SectionMap`, `InternalSummary`, `manifest.json` and the cache key;
  `InternalTable.require` refuses a table written by another engine or base;
  category documents moved to `schemaVersion: 2`.
- `scripts/verify_load_base.py` — reads the ELF headers with no external tools
  and recovers the base every row was written against. On the real 26.60
  library and today's table: 293,871 `sub_` names and 6,386 `vtableOffset`
  values all agree with base 0, none with 0x2463f0.
- Fresh end-to-end run of the current engine on the real library
  (`real_library_stats`, 95 s, 1074 MB peak, 717,633,086-byte table):
  `functions=791952 fdes=791952 missing=0`, `rows=809692`, `slots=865399`.

### Done in phase E-lite, step 2 (the string-anchor pass, and the section-12 numbers)

- `test(internal): cover the string-anchor pass` — `tests/string_anchor_test.rs`,
  3 cases: a planted `ADRP`+`ADD` pair names its function with
  `nameSource: "string"`; an absent string is counted in `notFound`; two
  functions computing the same address both get the name and `status:
  "ambiguous"`. The fixture grew `build_library_with_references`,
  `build_library_with_two_anchor_references` and `plant_anchor_reference`
  (`tests/support/mod.rs`).
- `docs(CARAMEL_26.60_SURFACE): measure per-class naming and the anchor route`
  — new section 7 (per-gameplay-class RTTI/slot-name/real-name table, the anchor
  occurrence and refsite census) and a correction of the claim that the six
  component names "belong to a different Bedrock build": they are in the
  binary as `entt::internal::pretty_function() [Type = …]` and as
  `entt::basic_storage<…>` mangled names, they simply have no vtable and so can
  never be RTTI classes.
- `feat(scripts): measure_surface.py` + `scripts/tests/test_measure_surface.py`
  (5 tests, hand-built ELF fixtures) — the script that produces every number in
  section 7 of that document now has its own tests. Its per-class
  "functions named after a slot" counter was wrong (it counted every function
  whose name started with `Class::`, including real names); fixed and covered.
- `AUDIT_REPORT.md` finding C4 records the starved anchor pass.

```console
$ python3 scripts/measure_surface.py --library libminecraftpe.so \
    --table out-current/.ndjson --json analysis/surface-26.60.json
dynsym            91,693 entries (79,316 functions, 11,354 objects)
symtab            stripped
functions         791,952   (symbol 75,720, jni 95, vtable 422,283, auto 293,854)
real method name  75,817    class+slot name 422,281
classes           6,386     vtable slots 865,399 in 62,306 groups
access_chain  rtti 9/11 real method names 0/11   (same for every other group)
$ python3 -m unittest discover -s scripts/tests -t .
Ran 131 tests in 0.426s
OK
$ cd app/src/main/rust/offset_core && cargo fmt --all --check && \
  cargo clippy --all-targets -- -D warnings && cargo test --all-targets
17 suites, all ok (162 + 11 + 10 + 4 + 4 + 7 + 1 + 15 + 4 + 4 + 3 + 3 + 4 + 7 + 7 ... 0 failed)
$ ./gradlew --offline :app:testDebugUnitTest :app:ktlintCheck
BUILD SUCCESSFUL in 1s
```

Anchor census on 26.60 (occurrences in `.rodata` / code reference sites):
`ActorOwnerComponent` 271/133, `StateVectorComponent` 316/164,
`AABBShapeComponent` 125/69, `ActorTypeComponent` 12/8, `EntityRegistry` 8/0,
`ActorComponentFactory` 38/0, `const char *entt::internal::pretty_function()
[Type = ActorOwnerComponent]` 1/0.

### Not done in phase E-lite

- Slot-meaning derivation (getter shape, member offsets, string anchors): the
  only route to `Level::getRuntimeActorList` on a stripped build. Designed in
  `docs/CARAMEL_26.60_OFFSETS.md` section 4, not implemented.
- `mangled` per vtable group (finding D5): 1,406 `entt::basic_storage`
  instantiations currently share one name.
- Nothing in this phase was run on a device.

## Done in the first session

- Part 2 no longer discards a finished pass (A1), Part 1 survives a Part 2
  failure (A2), a missing engine fails the build (A3).
- The internal search reads the category documents (C1) — the 883k functions are
  reachable from the UI again.
- Namespace prefixes stop at a boundary (C2); `ecs`/`scripting` buckets added
  (C3).
- Coverage numbers are exported (A4/B1/B3 counts in the manifest).
- README version claim fixed (E1).

## Final gate run

Re-run at the end of phase E-lite, with the real output:

```
$ cargo fmt --all --check
FMT_CLEAN
$ cargo clippy --all-targets -- -D warnings
Finished `dev` profile [unoptimized + debuginfo] target(s) in 1.43s   (no warnings)
$ cargo test --all-targets
160 + 11 + 10 + 3 + 4 + 7 + 1 + 14 + 4 + 4 + 3 + 4 + 7 passed, 0 failed, 3 ignored
$ python3 -m unittest discover -s scripts/tests -t .
Ran 126 tests in 0.424s
OK
$ python3 scripts/generate_patterns.py --check patterns.txt app/src/main/assets/patterns.json
[generate_patterns] app/src/main/assets/patterns.json is up to date
$ ./gradlew --offline :app:testDebugUnitTest :app:testReleaseUnitTest :app:ktlintCheck :app:lintDebug
BUILD SUCCESSFUL in 35s
  testDebugUnitTest    317 tests, 0 failed, 0 skipped   (UP-TO-DATE: no Kotlin changed in this phase)
  testReleaseUnitTest  268 tests, 0 failed, 0 skipped
  ktlintCheck          UP-TO-DATE
  lintDebug            executed
```

`scripts/verify_library_ground_truth.py` and the two caramel scripts are not part
of the gate; they need the real library and are run by hand:

```
$ python3 scripts/verify_library_ground_truth.py --library libminecraftpe.so \
    --output-dir out-shards7/categories-measure
classes: 6386, type_info slot ok=6386 wrong=0 missing=0
virtual slots: 157188, ok=157188 wrong=0 missing=0
functions: 791952, outside .text: 0
fdes in .eh_frame_hdr: 791952, difference from functions: 0
$ python3 scripts/validate_caramel_pack.py caramel/offsets_26.60.json --library libminecraftpe.so
caramel/offsets_26.60.json: ok, 35 items (13 found, 0 partial, 22 missing)
```

Measurement on the real library (`host_shard_measure`, ignored by default):

```
cargo test --release --test host_shard_measure -- --ignored --nocapture
rows=901314 classes=6386 functions=883574 symbols=11354
tableBytes=743730101 onDisk=743730101
coverage: fdes=791952 missing=0 slots=865399 withoutFileOffset=814
peak resident: 1110 MB
```

## Not done

- Signature derivation, `vtable_indices`, the key-target pack, struct field
  offsets, version diffing (features 1–10). The caramel *pack* itself now exists
  for the access chain and the ECS surface (`caramel/offsets_26.60.json`), but
  the accessor functions inside it are `missing`.
- Per-group mangled names (D5).
- Priorities and the exact place to add each one are in `AUDIT_REPORT.md`.
- No device run: no phone or emulator in this container, and no NDK, so
  `assembleDebug` cannot produce the native library here.
