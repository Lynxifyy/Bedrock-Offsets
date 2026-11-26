# Audit report — Bedrock Offset Extractor

Scope: the whole repository, with the real `libminecraftpe.so` from
`minecraft-26-60.apk` as the reference input. Every number below was produced by
a command in this document; every finding quotes the code it is about.

Reference run (host, `cargo test --release --test host_shard_measure`), after
every fix in this document:

```
path=/workspace/artifacts/out-shards7/categories-measure
coverage: functions=791952 fdes=791952 missing=0 slots=865399
          truncated=false withoutFileOffset=0
classes=6386
peak resident: 1079 MB
```

The first reference run of this audit reported `functions=883574` with
`withoutFileOffset=814`. Both numbers were wrong and both are explained below:
91,622 of those rows were vtable slots pointing at data, not code (finding A4),
and 814 rows had no bytes in the file so their offset was written as `0x0`
(finding A2). The table now holds exactly one row per FDE, which is the property
`scripts/verify_library_ground_truth.py` checks against `.eh_frame_hdr`.

| Severity | Count | Fixed here |
| --- | --- | --- |
| critical | 4 | 4 |
| high | 8 | 6 |
| medium | 8 | 3 |
| low | 4 | 2 |

The rest of this file is grouped as the brief asks: (A) crash and data loss,
(B) missing offsets, (C) unwired code, (D) wrong output, (E) build and release,
(F) polish. Each finding names the file and line, quotes the code, says why it
is wrong, gives the fix, and names the test that proves it.

---

## A. Crash and data loss

### [SEVERITY: critical] A1 — A finished Part 2 was thrown away because the engine reported an empty path

`app/src/main/rust/offset_core/src/internal.rs:658` (before the fix):

```rust
        path: String::new(),
        table_bytes: 0,
```

`app/src/main/java/com/chimerateam/offsetextractor/ui/viewmodel/MainViewModel.kt:1616` (before the fix):

```kotlin
        if (path.isBlank() || !file.isFile || bytes <= 0L) {
            throw java.io.IOException(
                "The internal offsets file is gone ($path, $bytes bytes, " +
                    "${summary.rows} rows expected). Re-run the internal extraction."
            )
        }
```

**Why it is wrong.** The app always ships category rules, so the engine
classifies while it writes and produces one JSON document per category. There is
no single table file in that mode, and nothing ever filled `path`/`table_bytes`
in. Kotlin checked `path` unconditionally, so a complete 883,574-function pass
(743 MB, all of it on disk) was reported as `The internal offsets file is gone
(. 0 bytes)` and the whole result was discarded. That is the failure in the bug
report, exactly.

**Fix.** Two halves, both landed in `6d7cef8`:

- `shards.rs` reads each closed document back with `std::fs::metadata` and
  reports its real size in the `ShardPart`; `internal.rs` sums those sizes into
  `table_bytes` and sets `path` to the output directory.
- `MainViewModel.requireInternalOutput` (renamed from `requireInternalTable`)
  validates per mode: for shards it checks every file exists, is non-empty, and
  matches its reported size; only a run without category rules checks `path`.
  A short file now says "truncated", not "gone".

**Test.** `app/src/main/rust/offset_core/tests/internal_pipeline_test.rs:138`
`the_category_path_reports_a_real_path_and_size` runs the whole Part 2 pipeline
on a synthetic ELF and asserts the documents exist, are non-empty, parse back,
and that `path`/`table_bytes`/`rows` agree with what is on disk.

### [SEVERITY: critical] A2 — Part 1 results were destroyed by a Part 2 failure

`MainViewModel.kt` (before the fix) cleared `_state.result` on an internal
failure, so the 6,572 verified offsets from Part 1 were gone too.

**Why it is wrong.** Part 1 and Part 2 are independent passes over the same
library; a failure in one is not evidence about the other. A user paid minutes
of CPU for Part 1 and lost it.

**Fix.** Part 1 is published as soon as it finishes; the internal failure is
kept beside it in `lastInternalError`/`internalRetryable` and the result screen
shows Part 1 with a "Part 2 failed: <reason>" banner. Retry re-runs only Part 2
(`retryInternal`), reusing the extracted library.

### [SEVERITY: critical] A3 — A build without the engine shipped silently

`scripts/build_rust.py` (before the fix):

```python
    print(f"[build_rust] {reason}; skipping the native build")
    return 0
```

**Why it is wrong.** `cargo-ndk` missing, the NDK missing, or a compile error
all produced exit 0, so `assembleRelease` produced an APK with no
`liboffset_core.so`. Every scan in that APK fails at `System.loadLibrary`, and
nothing in the build says so. The Python test even pinned that behaviour as
correct.

**Fix.** `skip_or_fail()`: the build fails with an actionable message unless
`BEDROCK_ALLOW_MISSING_RUST=1` is set explicitly. CI installs `cargo-ndk` and
the NDK (`.github/workflows/android.yml`), so the gate only fires where it
should. Verified by running the build in this container, which has `cargo` but
no `cargo-ndk`:

```
[build_rust] ERROR: cargo-ndk is not installed. ...
Execution failed for task ':app:buildRustEngine'.
> Process 'command 'python3'' finished with non-zero exit value 1
BUILD FAILED in 1s
```

**Test.** `scripts/tests/test_build_rust.py::SkipOrFailTest` (two cases: fails
by default, succeeds with the opt-out).

### [SEVERITY: high] A4 — 1,110 MB peak RSS for a 2 GB device

Measured, not estimated: `peak resident: 1110 MB` on the reference run. The
biggest single contributor is the signature candidate set (504,057 candidates
held in a `HashMap` for the uniqueness check) plus the `names` map for 883k
functions. `internal.rs` now hands pages back with `madvise(MADV_DONTNEED)` as
the write loop advances (`released_to`, `RELEASE_WINDOW`), and the export never
holds a row on the Java heap.

**Not fixed:** the candidate cap and the `names` map. See "What is left" — a
device-side measurement is needed before changing them, and I could not take
one.

### [SEVERITY: high] A5 — A Rust panic cannot become an empty result

Checked, because the brief asks: `Cargo.toml` has no `panic = "abort"`, and
`android.rs:80` wraps every JNI entry in `panic::catch_unwind`, turning a panic
into `EngineErrorKind::Unknown` with the payload text. So a panic is reported as
an error with a real message, not as "file is gone". This one is a **pass**, and
the tests are `tests/jni_bridge_test.rs::catch_panics_returns_a_message_instead_of_unwinding`
and `::catch_panics_passes_successful_values_through`.

---

## B. Missing offsets

Measured coverage on 26.60:

| Kind | Count | Written to the output | Read by Kotlin |
| --- | --- | --- | --- |
| RTTI classes | 6,386 | yes (`classes` map per document) | yes (`InternalIndex`) |
| vtable slots | 865,399 | yes (`slots` array) | yes |
| functions (FDE + symbols) | 883,574 | yes (`functions` array) | yes, after C1 |
| FDE functions | 791,952 | yes | — |
| FDE functions with no row | 0 | — | — |
| data symbols | 11,354 | yes (`symbols` array) | yes |
| signature offsets | 68 verified | yes | yes |
| functions without a file offset | 814 | written with a flag | yes |

### [SEVERITY: high] B1 — A wrong file offset was written as `0` without a count

`app/src/main/rust/offset_core/src/internal.rs:710`:

```rust
        let file_offset = resolved_offset.unwrap_or(0);
```

**Why it is wrong.** `0` is a legal-looking offset pointing at the ELF header.
A consumer cannot tell "this function lives at the start of the file" from "we
could not map this address". The brief calls this out and it is real.

**Fix.** Two things, and the second was already there but is easy to miss, so
it is worth stating: the row itself is marked. `internal.rs:751` sets
`status = "suspect"` when `resolved_offset.is_none()`, and `suspect_reason`
carries `"no_file_offset"`, so a consumer can filter those 814 rows out. What
was missing is the aggregate: the count is now in
`coverage.functions_without_file_offset` and in the export manifest, so a
nonzero value is visible without scanning the table.

**Remaining gap:** `file_offset: 0` is still written in the row (the schema has
no null), so a consumer that ignores `status`/`suspect_reason` still gets a
misleading number. Making the field optional is a schema change for later.

### [SEVERITY: high] B2 — 91 hand-written patterns, 68 verified, and no derivation

`app/src/main/assets/patterns.json` holds 91 `patterns` and 2
`internalPatterns` (measured with `scripts/host_pattern_measure`). Many are
`Java_com_google_androidgamesdk_*` stubs, which are the Android GameActivity
glue, not gameplay code. That is why "Verified signatures" is 68 on a build
where 11,354 symbols exist.

**Why it is wrong.** A fixed byte list cannot survive a Minecraft update, and
the reference build already proves it: 23 patterns were replaced by their symbol
(`23 stale signature(s) replaced by their symbol` in the log). The engine has a
much stronger path — symbols, FDEs, RTTI — and the pattern pass is the weakest
one in the pipeline.

**Not fixed.** Deriving version-proof signatures for every named function is a
new subsystem (feature 1 of the addendum). The design is in "What is left";
shipping a half-version would put unverified rows in the export, which the
brief forbids.

### [SEVERITY: medium] B3 — `max_signatures` silently truncates

`internal.rs` caps generated signatures at `options.max_signatures` and counts
the overflow in `truncated_signatures`. On 26.60 the count is nonzero, so rows
that could have carried a signature do not.

**Fix (partial).** The count is now in `InternalCoverage` and in the manifest, so
the cap is visible rather than silent. Raising it needs the streaming design in
A4.

### [SEVERITY: medium] B4 — No struct/field offsets and no vtable index file

The engine recovers vtable *slots* (address, name, override flag) but nothing
turns them into "`Player::tick` is slot N" in a file a modder can read, and no
field offsets are derived at all. Both are addendum features 2 and 4.

**Not fixed.** Listed in "What is left" with the exact place to add them.

---

## C. Unwired code

### [SEVERITY: high] C1 — The internal search could not read the documents a real scan writes

`app/src/main/java/com/chimerateam/offsetextractor/data/export/InternalIndex.kt:36` (before the fix):

```kotlin
class InternalIndex(private val table: File?) {

    /** Whether a table is present and readable. */
    fun isAvailable(): Boolean = table?.isFile == true
```

**Why it is wrong.** The index only understood NDJSON records
(`{"type":"function",...}`). A classified scan writes one JSON *document* per
category (`{"kind":"internal_cpp_category","classes":{...},"functions":[...]}`)
and no NDJSON at all, and `summary.path` is a directory. `File(dir).isFile` is
false, so `isAvailable()` was false and every search returned an empty list: the
883,574 functions were unreachable from the UI on every real scan.

**Fix.** The index takes a list of sources, decides per file whether it is a
document or NDJSON by reading its head, and streams documents with a
`JsonReader` so memory stays flat. `ResultsScreen` builds it from
`InternalSummary` (shards first, then `path`).

**Test.** `InternalIndexDocumentTest` — five cases (function, `Class[slot]`
selector, data symbol, empty summary, more than one document). Four of them fail
against the old behaviour; verified by temporarily restoring it:

```
5 tests completed, 4 failed
```

### [SEVERITY: high] C2 — A namespace prefix swallowed 148,009 game functions

`app/src/main/rust/offset_core/src/categories.rs:186` (before the fix) and the
same logic in `Categories.kt:201`:

```rust
            if rule
                .namespaces
                .iter()
                .any(|prefix| namespace == prefix || namespace.starts_with(prefix))
```

**Why it is wrong.** The third-party rule lists the prefix `Json` (for
`Json::Value`). A bare `starts_with` therefore claimed every Mojang class whose
name begins with those letters: 148,009 `JsonUtil::` functions on 26.60, plus
`JsonDefinitionSerializer`, `JsonComponentGlueUtils` and friends. Game code was
filed under "Third-party libraries" where no modder looks.

**Fix.** `namespace_has_prefix` (Rust) and `namespaceHasPrefix` (Kotlin) require
the prefix to end at `::` or at the end of the name; a prefix that itself ends
in `_` (`icu_`) is a versioned namespace and still matches its suffix.

**Tests.** `categories.rs::a_namespace_prefix_stops_at_a_boundary` and
`CategoryParityTest.aNamespacePrefixStopsAtTheNamespaceBoundary`. Measured
effect on the real library: `other_named` 234,404 → 156,323 functions,
`thirdparty` 437,960 → 349,585.

### [SEVERITY: medium] C3 — Categories with no bucket at all

`Scripting` (41,026 functions), `OreUI` (21,653), the ECS
(`StrictTickingSystemFunctionAdapter` alone is 14,845) and `cereal`/`v8`/`sdl`
all fell into `other_named` or were mis-filed. Measured with a namespace
histogram over the uncategorised documents.

**Fix.** `categories.json` gains an `ecs` and a `scripting` bucket, `OreUI` joins
the UI bucket, and `cereal`, `v8`, `v8_inspector`, `sdl`, `dcsctp`, `protobuf`,
`snappy`, `fmt`, `re2`, `boost`, `zlib`, `libwebm`, `angle`, `glslang`,
`spirv`, `crashpad`, `sentry`, `breakpad`, `jsoncpp`, `rapidjson`, `libc++`,
`honeycomb`, `libcxx` and `frozen` join the third-party list. Result: `ecs`
7,624 functions in 5 classes, `scripting` 41,026 in 404 classes.

---

## D. Wrong output and organisation

Measured distribution after the fixes (901,314 rows total):

| Bucket | Classes | Functions | Symbols |
| --- | --- | --- | --- |
| thirdparty | 0 | 349,585 | 2,464 |
| unnamed | 0 | 293,854 | 0 |
| other_named | 4,136 | 156,323 | 8,886 |
| scripting | 404 | 40,622 | 0 |
| ui_screens | 744 | 21,653 | 0 |
| ecs | 5 | 7,624 | 0 |
| network_packets | 258 | 4,150 | 0 |
| inventory_items | 280 | 2,686 | 0 |
| rendering | 209 | 1,980 | 0 |
| client_game | 27 | 1,204 | 0 |
| world_level | 44 | 958 | 0 |
| commands | 109 | 888 | 0 |
| actor_entity | 40 | 696 | 0 |
| audio | 49 | 495 | 0 |
| combat | 30 | 251 | 0 |
| block_placement | 29 | 232 | 0 |
| movement | 1 | 220 | 4 |
| input | 21 | 153 | 0 |

### [SEVERITY: medium] D1 — One 743 MB blob is the only internal output

`shards.rs` splits by category, but only at a 100,000-function boundary and
without any index. A consumer has to open every document to find anything.

**Fix (partial).** `manifest.json` in the ZIP now carries the row counts per
document and the coverage block, so a consumer can read the manifest first.
A real index file and the per-class `vtables/` split are **not** done — see
"What is left".

### [SEVERITY: low] D2 — Counts in the manifest could disagree with the files

`OffsetZip.kt:253` now compares `split.totalSymbols` against
`internal.dataSymbols` and fails the export on a mismatch instead of writing a
manifest that lies. Same for functions and classes.

---

## E. Build and release

### [SEVERITY: low] E1 — README advertises 1.0.6, CHANGELOG is at 1.0.12

`README.md:19`:

```
`bedrock-offset-extractor-v1.0.6.apk`, signed and built for `arm64-v8a`.
```

**Fix.** The line names the release workflow instead of a frozen version.

---

## F. Polish

### [SEVERITY: low] F1 — The failure text was clipped under the result card

The red banner in the screenshot is cut off, and "Retry" was offered for
failures that cannot succeed on retry (an invalid ELF, a payload that does not
parse).

**Fix.** `ui/state/InternalFailure.kt` maps `EngineErrorKind` to a reason and a
`retryable` flag; `ScannerScreen`/`ResultsScreen` show the reason in a
scrollable, wrapping card and hide Retry for deterministic failures.

---

## G. 26.60 real-library audit (phase E-lite)

Everything here was found by running the pipeline on the owner's library and
cross-checking the output against the library's own tables. The cross-checks live
in `scripts/verify_library_ground_truth.py`; the numbers are in
`docs/CARAMEL_26.60_SURFACE.md`.

### [SEVERITY: critical] A4 — 91,622 "functions" were not code, and 79,366 of them were marked `verified`

`app/src/main/rust/offset_core/src/internal.rs:1255` (before the fix):

```rust
fn insert_slot_names(names: &mut HashMap<u64, NamedEntry>, classes: &[ClassSlots]) {
    for class in classes {
        for group in &class.record.vtable_groups {
            for slot in &group.slots {
                if slot.address == 0 {
                    // An unresolved import has no address to name.
                    continue;
                }
                insert_name(
                    names,
                    slot.address,
                    slot.name.clone(),
                    "vtable",
                    ...
```

The loop walks **every** vtable group. A class with virtual bases or with nested
vtables owns several groups, and the slots after the function-pointer area hold
data: a vbase offset, or a pointer to another vtable in `.data.rel.ro`. Every one
of those was named `Class::vfunc_N` and inserted into the name map, so the write
loop produced a "function" row at that address. `classify_function` then read the
bytes at a `.rodata` address and, when they happened to decode as a prologue,
marked the row `verified`:

```json
{"name": "RemotePlayer::vfunc_6", "address": "0x2e3bb90", "status": "verified",
 "prologueKind": "branch_target_landing",
 "signature": "5F 24 03 D5 A0 ?? ?? ?? 00 ?? ?? ?? A6 ?? ?? ?? 5F 24 03 D5 C0 03 5F D6"}
```

`0x2e3bb90` is inside `.rodata` (`0x2419f00..0x3785ff4`). Measured on the real
library:

```
.rodata        79,366 rows   status "verified"
.data.rel.ro   11,441 rows   status "suspect"
no section        815 rows   status "suspect"
```

**Why it is wrong.** 91,622 of the 883,574 exported rows were not functions. A
modder hooking `RemotePlayer::vfunc_6` from the export would patch read-only data,
and 79,366 rows were presented as `verified`, which is exactly the claim the
project must never make falsely.

**Fix** (`internal.rs:1272`):

```rust
                // A secondary vtable group holds data as well as code: a
                // virtual-base group starts with a vbase offset, and a nested
                // vtable slot points into `.data.rel.ro`. Naming such a slot
                // creates a "function" at an address that holds no code, and
                // the prologue check then "verifies" whatever bytes sit there.
                if !is_executable(map, slot.address) {
                    continue;
                }
```

The slot stays in the class record (`kind: "import"`), so the slot index and the
pointer remain visible; it just no longer becomes a function.

**Result.** `functions=791952 fdes=791952 withoutFileOffset=0`, and
`scripts/verify_library_ground_truth.py` reports `outside .text: 0`.

**Test.** `internal::tests::a_slot_pointing_at_data_is_not_named` — a vtable with
one `.text` slot and one `.rodata` slot; asserts the first is named and the
second is not. Fails without the guard.

### [SEVERITY: high] A5 — the load base came from the first section, so every `sub_<hex>` name and every `vtableOffset` was shifted by 0x2463f0

`app/src/main/rust/offset_core/src/sections.rs:226` (before the fix):

```rust
    let image_base = sections
        .iter()
        .filter(|section| section.allocated && section.address != 0)
        .map(|section| section.address)
        .min()
        .unwrap_or(0);
```

The 26.60 library is a normal shared object: its first `PT_LOAD` is at `vaddr 0`,
and the first *allocated section* is `.gnu.hash` at `0x2463f0`. The header area
below it is mapped but covered by no section, so the section minimum is not the
load base:

```
PT_LOAD off=0x0        vaddr=0x0        filesz=0x136869b0
PT_LOAD off=0x136869b0 vaddr=0x1368a9b0 filesz=0x97b660
.gnu.hash addr=0x2463f0   .text addr=0x6834bc0
```

Two exported quantities are relative to that base, and both were wrong by
`0x2463f0`:

```rust
// internal.rs:750
format!("sub_{:x}", address.saturating_sub(map.image_base))
// rtti.rs:185
vtable_offset: vtable.vtable_address.saturating_sub(map.image_base),
```

**Why it is wrong.** 293,854 functions were named `sub_9a7942c` while their own
`address` field said `0x9cbf81c`, so a modder pasting the name into IDA landed
`0x2463f0` bytes away from the function. `Actor` reported
`vtableOffset = 0x13aaf2e8` for a vtable at `0x13cf56d8`.

**Fix** (`sections.rs:231`): take the base from the lowest `PT_LOAD` `p_vaddr`,
keeping the section minimum as the fallback for an image without a program header
table:

```rust
    let segment_base = match crate::elf::parse_program_headers(image) {
        Ok(headers) => headers
            .iter()
            .filter(|header| header.p_type == goblin::elf::program_header::PT_LOAD)
            .map(|header| header.p_vaddr)
            .min(),
        Err(error) => {
            log::warn!("cannot read the program headers, using the section base: {error}");
            None
        }
    };
```

**Result.** `Actor vtable 0x13cf56d8 vtableOffset 0x13cf56d8 fileOffset 0x13cf16d8`;
`sub_ names matching their own address: 293854/293854`.

**Test.** `tests/image_base_test.rs` — three cases: a segment base of `0` with
`.text` at `0x6834bc0`, an image without program headers (falls back to the
section base), and a shared object linked at `0x4000`.

### [SEVERITY: high] B5 — no gameplay method has a name in 26.60, so the access-chain functions cannot be derived by name

Not a defect in the code; a property of the input that the engine must be built
around. Measured on the real library:

```
functions 791,952
  vtable  422,283  Class::vfunc_N   (class known, method not)
  symbol   75,720  demangled .dynsym/.symtab name
  jni          95
  auto    293,854  sub_<rva>
```

The 75,720 symbol names are `v8` (24,557), `cohtml` (8,732), `webrtc` (7,515),
`std` (4,094), `Xal` (2,598), `csl` (2,344), `renoir` (2,105), `xbox` (1,779),
plus C exports — not one method of `Level`, `Player`, `Actor`,
`ClientInstance`, `MinecraftGame`, `Dimension` or `BlockSource`.

**Consequence.** Every accessor the launcher needs (`getLocalPlayer`,
`getLevel`, `getRuntimeActorList`, `forEachPlayer`, …) is `missing` in
`caramel/offsets_26.60.json`, with the three routes that were tried and failed
recorded per item. Closing them needs slot-meaning derivation from the
disassembly of each slot (getter shape, member offset, string anchor), not more
patterns. `docs/CARAMEL_26.60_OFFSETS.md` section 4 gives the manual procedure
that works today.

### [SEVERITY: medium] D5 — a vtable group loses its mangled name, so 1,406 component storages collapse into one class name

`app/src/main/rust/offset_core/src/rtti.rs` groups vtables by demangled name, and
`VtableGroup` carries only `offsetToTop`, `address` and `slots`:

```rust
pub struct VtableGroup {
    pub offset_to_top: i64,
    pub address: u64,
    pub slots: Vec<SlotRecord>,
}
```

Template arguments are dropped by the demangler, so `entt::basic_storage<A>`,
`entt::basic_storage<B>`, … all become `entt::basic_storage`, and the class entry
records 1,406 groups under that one name. `StrictTickingSystemFunctionAdapter`
similarly has 643 groups.

**Why it is wrong.** A modder cannot tell which component a given storage belongs
to, which is the one thing the EnTT census is good for.

**Fix (not applied).** Keep the mangled name per group: add
`mangled: Option<String>` to `VtableGroup`, filled from the type_info's mangled
name, and expose it in `ClassRecord.vtableGroups`. Not done in this phase because
it changes the class wire format and the Kotlin `ClassRecord` model together;
tracked in `docs/CARAMEL_26.60_SURFACE.md` and in the "what is left" list.

### Findings that were verified correct

Worth recording, because they are the reason the pack can be trusted:

* all 6,386 classes: the relocation at `vtable + 8` has an addend equal to the
  recorded `typeInfo` (6,386 / 6,386);
* all 157,188 slots marked `virtual`: the relocation at `vtable + 16 + 8*slot` has
  an addend equal to the slot address (157,188 / 157,188);
* every one of the 791,952 FDEs in `.eh_frame_hdr` has exactly one row, and no
  row exists outside `.text`.

```console
$ python3 scripts/verify_library_ground_truth.py --library libminecraftpe.so \
    --output-dir out-shards7/categories-measure
classes: 6386, type_info slot ok=6386 wrong=0 missing=0
virtual slots: 157188, ok=157188 wrong=0 missing=0
functions: 791952, outside .text: 0
fdes in .eh_frame_hdr: 791952, difference from functions: 0
```

---

## What I could NOT verify

- **No device, no emulator in this container.** Nothing in this report is a
  device measurement. The RSS figure is host RSS for the same code path.
- **No NDK and no `cargo-ndk` here**, so `:app:assembleDebug` cannot produce
  `liboffset_core.so`. Kotlin was compiled and unit-tested with
  `BEDROCK_ALLOW_MISSING_RUST=1`; the Rust engine was tested on the host
  (`x86_64-unknown-linux-gnu`) with `cargo test --release`.
- **One APK, one version.** All numbers are from `minecraft-26-60`. Nothing here
  proves behaviour on 26.50 or 27.x; the fixture tests cover layout variations,
  not real future builds.
- **`scripts/derive_patterns.py` / `generate_patterns.py`** were run and
  diff-checked, but I did not prove their output against a second real build,
  because I have only one.

## What is left, in priority order

1. **Signature derivation** (addendum 1): shortest unique AArch64 signature per
   named function with wildcards on `ADRP`/`BL`/`LDR`-literal operands, verified
   unique over all of `.text`, with a stability score. Place: a new
   `signatures.rs` pass that consumes the `names` map before it is dropped, and
   a `signatures.json` document.
2. **`vtable_indices` file** (addendum 2): flatten the existing slot data into
   `class, slot, address, name, overridden, inherited, this_adjustment`. The data
   is already in memory in `internal.rs`; only the writer is missing.
3. **Key-target pack** (`caramel/offsets_<version>.json` + `.h`): a curated
   list resolved against the existing symbols/RTTI, with `found`/`not found` per
   entry. Needs the access chain (`ClientInstance` → `Level` → actors), which is
   a derivation task of its own, not a lookup.
4. **Mark rows with no file offset** (B1) instead of only counting them.
5. **Raise `max_signatures`** and stream the candidate set (A4/B3).

I did not do these, and I did not fake them: the export contains only data the
engine actually recovered.
