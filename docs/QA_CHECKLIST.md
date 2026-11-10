# QA checklist

Phase 28 asks for an end-to-end pass on a physical tablet. This file records
what the repository verifies on its own, and what still needs a device. A check
is only ticked when something actually ran.

## Verified automatically

These run in CI and locally, on every change.

| # | Check | How it is verified |
|---|---|---|
| 1 | The engine reads a real cross-compiled `liboffset_core.so` | `tests/elf_test.rs`, `tests/sections_test.rs`, `tests/symbols_test.rs`, `tests/rtti_test.rs` |
| 2 | Extraction, mapping, scanning, prologue check, symbol check and vtable resolution agree on one offset | `tests/end_to_end_test.rs::the_whole_pipeline_agrees_on_one_offset` |
| 3 | A signature that is not in the library reports no hits | `tests/end_to_end_test.rs::a_signature_that_is_absent_produces_no_hits` |
| 4 | A corrupted APK is rejected with `InvalidZip`, not a crash | `tests/end_to_end_test.rs::a_corrupted_apk_fails_closed` |
| 5 | An APK without `libminecraftpe.so` is rejected with `MissingSoFile` | `tests/end_to_end_test.rs::an_apk_without_the_library_fails_closed` |
| 6 | A 32-bit library is rejected with `UnsupportedArchitecture` | `tests/end_to_end_test.rs::a_32_bit_library_is_rejected` |
| 7 | A truncated library is rejected with `InvalidElf` | `tests/end_to_end_test.rs::a_truncated_library_is_rejected` |
| 8 | A match that is not a prologue is discarded and counted | `tests/end_to_end_test.rs::a_match_that_is_not_a_prologue_is_discarded` |
| 9 | Cancelling a scan returns `Cancelled` | `tests/end_to_end_test.rs::cancelling_a_scan_returns_cancelled` |
| 10 | Progress starts at 0, never decreases, and ends at 100 | `tests/end_to_end_test.rs::the_scan_reports_progress_from_zero_to_one_hundred` |
| 11 | Scanning a large section does not grow the peak resident set by 100 MB | `tests/scanner_test.rs`, using `VmHWM` from `/proc/self/status` |
| 12 | Every typed engine failure has a user-facing message | `MainViewModelTest`, `ErrorMapper` branch coverage |
| 13 | The release APK is under the size budget, ships the engine, has no `Log.d`/`Log.v` and requests no `INTERNET` permission | `scripts/verify_release.py`, run by CI after `assembleRelease` |
| 14 | The signature pack is in sync with its source | `scripts/generate_patterns.py patterns.txt --check` |
| 15 | Two offset documents can be diffed | `scripts/verify_offsets.py`, 11 cases in `scripts/tests/test_verify_offsets.py` |
| 16 | The changelog groups commits correctly | 20 cases in `scripts/tests/test_changelog.py` |

Run all of it with:

```bash
./gradlew test lintDebug assembleRelease
python3 scripts/verify_release.py
python3 -m unittest discover -s scripts/tests -t . -v
(cd app/src/main/rust/offset_core && cargo test --all-targets && cargo clippy --all-targets -- -D warnings)
```

## Needs a physical tablet

Nothing below can be replaced by a host test: each one depends on a real
Android device, a real APK, or real storage providers. They are written as steps
so the pass is repeatable.

### 1. Install the release APK

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.chimerateam.offsetextractor/.MainActivity
```

Expect: the app opens on the idle screen, no crash, no permission prompt (the app
requests none).

### 2. Scan a 1.21.0 APK and compare against known offsets

Select the APK, let the scan finish, and open the results. Every offset whose
name is in the pack must have an address, and the addresses must match the
values the loader expects for that build. A `"-"` is a failure for that
signature: re-derive it.

### 3. Scan a 1.21.50 APK and confirm the offsets moved

The same signatures should mostly resolve to different addresses, because the
library changed. If every offset is identical to the 1.21.0 scan, the wrong
library was scanned; check `source.librarySha256` differs between the two
documents.

### 4. Corrupted APK

Rename a JPEG to `.apk` and select it. Expect a clean message about the archive,
not a crash, and the UI back in a usable state.

### 5. 32-bit APK

Select an APK whose library is `armeabi-v7a`. Expect "Only arm64-v8a is
supported." and no extraction left behind.

### 6. A 2 GB APK, watching memory

```bash
adb shell dumpsys meminfo com.chimerateam.offsetextractor | grep -E "TOTAL|Native Heap"
```

Watch it during extraction. Expect the total to stay well under 100 MB and to
fall back after the scan. Extraction streams, so the peak should not track the
APK size.

### 7. Export and load

Export the document, then paste the `offsets` map into a mock loader config and
confirm every name resolves. Check the file landed where you chose and is valid
JSON.

### 8. Cancel mid-scan

Start a scan and press cancel. Expect the UI to return to idle within a moment,
a log line recording the cancellation, and no extracted file left in the cache.

### 9. Rotate during a scan

Start a scan and rotate the device. Expect the scan to keep running, the log to
survive and the progress to continue from where it was. The ViewModel is
retained, so nothing restarts.

### 10. Airplane mode

Enable airplane mode and repeat a scan. The app has no `INTERNET` permission, so
nothing should change. If it fails only with a network, the build is not the one
this checklist describes.

## Recording a pass

Record the device model, the Android version, the app `versionName`, the APK
build, the outcome of each step, and the `stats` block from each exported
document. The `stats` block is what makes a later regression diagnosable.
