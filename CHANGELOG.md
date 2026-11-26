## v1.1.0-pre.3

### Bug Fixes

- the signature pass no longer dies on a large library. On a 700,000-function library the extension rounds held every pattern as hex text and reached 929 MB; patterns are now bytes plus a wildcard bitmap, the scan runs over one bounded window of `.text` at a time, and the rounds stop at a 192 MB budget instead of pushing the process into the low-memory killer. Peak resident on the same library: 709 MB worst case, 556 MB when most patterns resolve in the first round
- the same 700,000-function library now completes on a 2 GB phone: 500,000 signatures, no process exit, 761 MB at round 1 and 790 MB at round 2 in the live log
- a scan that is killed or crashes leaves a report: uncaught exceptions are appended to `live.log`, and the log pane replays the last 40 `CRASH` lines on the next start, so a silent kill can be pasted back instead of guessed at
- a symbol table that hits the 500,000-entry cap now says so in the live log instead of looking complete

### Tests

- signature storage tests: bytes plus bitmap round-trip, wildcard positions survive, a 500,000-pattern table stays inside the budget
- chunked-scan tests: a signature longer than the window still selects the same functions, and a signature that differs only past the window boundary does not collide

## v1.1.0-pre.2

### Bug Fixes

- classes keep their full template identity: the demangler used to stop at the first `<`, so every `std::__function::__func`, `StrictTickingSystemFunctionAdapter` and `entt::basic_storage` instantiation collapsed into one row, 62,306 vtable groups into 6,386
- vtable groups are keyed by their mangled name instead of by their demangled one, so a class with several base subobjects keeps all of its groups instead of the first one seen
- a vtable slot is named after the class that declares it, and one address now carries one name across every class that inherits it, instead of `Mob::vfunc_0` and `Player::vfunc_0` disagreeing about the same function
- new evidence sources were scored below the `Class::vfunc_N` placeholder they were meant to replace, so a lambda call operator could never win its own slot
- the enclosing function of a lambda is read out of a template argument too, so `__func<Level::tick()::$_0, …>` yields `Level::tick()` rather than a truncated prefix

### Features

- a complete Itanium ABI demangler: substitutions, literals, packs, expressions, `Z` local names, lambdas, unnamed types, abi tags. Checked against `c++filt` on a 5,942-symbol corpus: 6,121 of 6,123 agree, and the two that do not are libc++'s own `1l` literal rendering
- base classes are read from `__class_type_info`, `__si_class_type_info` and `__vmi_class_type_info`, with flags, offsets and virtual/public bits
- a cross-reference pass over `.text`: `BL`, tail `B`, `ADRP`+`ADD` data references, `LDR` literal, `BLR`/`BR` indirect exits, per-function callers, callees and referrers
- lambda call operators are named from the `std::function` wrapper's vtable, and the wrapper's only in-image callee is named as the lambda body
- ECS adapter instantiations are parsed into a system name plus the `Include<…>`/`Exclude<…>` component lists
- signatures extend to 512 bytes instead of 128: on an aarch64 `libc++_shared.so` with 1,591 signatures, unique ones went from 1,523 (95.7 %) to 1,583 (99.5 %), and slot signatures from 839 of 841 to 841 of 841
- `scripts/coverage_report.py` measures what an export actually names, split into gameplay and third-party, and writes both a manifest block and a Markdown table
- `AUDIT_REPORT.md` v2 with every finding quoted at its `path:line`, and `COVERAGE.md` with the baseline and the measured deltas

### Tests

- a demangler corpus test that re-runs `c++filt` comparison, plus fixture tests for `Z` local names, packs and substitutions
- lambda naming tests, including one that fails if the evidence ranking puts a placeholder above a recovered name
- xref decoding tests over a synthetic ELF with a known call graph
- `scripts/tests/test_coverage_report.py`, and a `--self-test` inside the script itself

## v1.1.0-pre.1

### Bug Fixes

- name every function from the lowest `PT_LOAD` address, which is what the row's own `address` field is relative to: taking the first allocated section instead shifted all 293,854 `sub_<hex>` names and every `vtableOffset` by the 0x2463f0 header gap in the 26.60 library
- stop naming a vtable slot that holds data: 91,622 of the 883,574 rows were not code (79,366 in `.rodata`, 11,441 in `.data.rel.ro`, 815 in `.bss`), and 79,366 of them were marked `verified` because the bytes at a `.rodata` address happened to decode as an AArch64 prologue. The table now holds 791,952 rows, exactly one per `.eh_frame` FDE
- give every class record a real file offset: `vtableOffset` is image-base-relative, so a consumer patching at that value wrote into the wrong place
- never write `fileOffset: "0x0"` for a row whose address has no bytes in the file; 814 rows pointed at the ELF header
- require a category namespace prefix to end at a `::` boundary, which moved `other_named` from 234,404 to 156,323 functions and `thirdparty` from 437,960 to 349,585, and add the `ecs` and `scripting` buckets that 48,650 functions from `StrictTickingSystemFunctionAdapter`, `Scripting` and friends had no home in
- report the category output's real path and size in the summary instead of guessing it

### Features

- the engine stamps its version (crate version plus output schema) and the load base it used into the summary, the manifest and the cache key, and refuses a cached table written by another engine or base
- `scripts/verify_load_base.py` recovers the base from the ELF headers alone and checks a table against it
- a 26.60 offset pack for CaramelClient: 35 requested items across the access chain, entity list, actor fields, combat, render, voice, replay and world generation, 13 found, 22 missing with the reason each one is missing recorded, validated by `scripts/validate_caramel_pack.py`
- `scripts/measure_surface.py` prints what a library exposes (symbols, name sources, RTTI groups, EnTT storage, category targets) and is the reproducible form of the numbers in `docs/CARAMEL_26.60_SURFACE.md`
- `AUDIT_REPORT.md` records every finding with the offending code, the fix and the test, grouped by severity

### Tests

- the `.rodata` string-anchor pass had no test and no data; it now has three tests on the synthetic library, including the ambiguous case, which names every candidate `status: "ambiguous"` instead of picking one
- five tests for `scripts/measure_surface.py` over hand-built ELF fixtures, which caught a wrong per-class slot-name count in the script itself
- the full ground-truth check against the real library: 6,386/6,386 `type_info` slots, 157,188/157,188 virtual slots, 791,952 FDEs matching `.eh_frame_hdr`

## v1.0.12

### Bug Fixes

- delete the downloaded APK once the library is extracted and hashed, and the extracted library once the internal table is validated, so one scan of a 1 GB build no longer leaves about 2 GB behind; "Delete downloaded APK after scan" is a Settings switch and the storage list shows what is left
- remove the duplicate category split: the engine classifies every record while it streams and writes one finished JSON document per category, so the export no longer parses 890,000 rows twice or re-serialises them
- copy those documents into the archive through a 64 KB buffer and delete each one as soon as its entry is closed, and write the JNI document with a `JsonWriter` instead of building the object graph first, which is what requested the 77 MB allocation that failed with 6 MB free
- compress the archive at level 6 instead of 9
- keep the result card whole: the results pane was measured wrap-content while it contained a weighted list, so it took the screen and pushed its own pinned controls out of view, cutting the card at the same pixel every time
- move Copy, Share and Save JNI into one overflow menu so the pinned bar leaves the card room, and keep the progress pill clear of the navigation bar and the action row
- translate every export failure into one plain sentence, with the technical text kept in the log
- cap the in-memory log at 2,000 lines; every line is also appended to `cacheDir/logs/live.log` (trimmed to its newest 4 MB), and Copy uses that file so the start of a run survives the cap
- the live log now follows the newest line: "at the bottom" is the last item being visible, not the last item being first, so a real log was never followed and a "Jump to latest" chip now appears once the user scrolls up
- the vtable pass logs one summary line instead of one line per class
- the result, the log lines and the export job now carry one scan identifier: a new scan stamps its lines, empties the pane and the file, and clears the result, so a result can never be shown beside another scan's log
- report a vtable slot cap in the internal summary instead of truncating silently

### Features

- expanded widths (from 840 dp, for example a 1920x1200 tablet) get a navigation rail instead of the bottom bar, and the destinations' icons moved into `AppTab` so the bar and the rail cannot drift apart
- the results card opens with six stat tiles (functions, classes, slots, JNI offsets, signatures, confidence) and a completeness badge that reads `Complete: 791,952 of 791,952 functions` from the manifest check, amber when a pass was capped
- the palette is a graphite system instead of the blue/pink brand: neutral surface steps, one accent (cool blue), one secondary (warm amber), semantic success/warning/info colours exposed through `LocalSemanticColors`, and no gradient app bar or literal colour anywhere in a screen
- the archive carries `README.txt`, and `manifest.json` carries a `coverage` block (internal functions against `.eh_frame` FDEs, JNI exports, registered natives, pattern status, union count), a `categories` array with each category's file and row counts, and a `completeness` block
- after the archive is written it is re-opened and every category document is walked with a streaming reader, so the export fails unless `scan rows == table rows == category entries == entries re-read from the ZIP`; the results card and the log show the numbers
- `scripts/verify_release.py` checks the README, both coverage halves and the re-read completeness of a downloaded archive
- category documents are written by the engine with the same `categories.json` the UI uses, and a shared expectation set in the Kotlin and Rust tests keeps the two classifiers identical
- motion tokens (`ui/theme/Motion.kt`): three durations, four easings, three named springs, a 4 dp spacing scale and a reduce-motion switch that collapses every animation to a snap
- the JNI set is now the union of three sources instead of the signatures alone: every exported `Java_*` symbol, every `JNI_*` lifecycle export, and every entry of a `RegisterNatives` table found through the relocations, each with `source` and `confidence`
- `signatures` in the JNI document reports every pattern individually as `matched`, `ambiguous`, `not_found` or `symbol_only`, and a pattern the symbol table replaced is listed in `coverage.patternsStale` instead of disappearing
- `coverage` in the JNI document carries the exports, registered natives, pattern totals and union count, and the live log prints the same line
- Copy and Share now render through the same streaming writer as the archive, so the two JNI documents cannot drift apart again; `textAddress` and `textDelta` are `0x`-prefixed hex in both, as `docs/OUTPUT_FORMAT.md` already said

## v1.0.11

### Bug Fixes

- keep the internal C++ table in `filesDir` instead of the OS-trimmable cache, and fail the scan instead of reporting success when the table it wrote cannot be read back, which is how a download could ship a ZIP without the internal offsets
- refuse to build the archive when the table is missing, empty or shorter than the scan reported, and disable the download with an explanation once the data is gone
- verify the finished archive entry by entry, including that every category file is present and that the category counts add up to the table
- show the archive's file and internal-row counts in the success toast, so a ZIP with no internal data can no longer look like a successful download
- name every remaining suspect with a reason instead of a bare `suspect` flag
- stop the low-memory notice from interrupting a running scan, and make it opt-in with a Settings toggle
- keep every verified-signature row reachable: the pinned footer no longer repeats the internal counts that the summary card already shows
- write the internal table with a streaming serializer and one binary search over the `.eh_frame` sizes instead of a per-record JSON tree and an 800k-entry map
- resolve most signature uniqueness from the first eight bytes at each function start instead of rescanning the library for every round

## v1.0.10

### Bug Fixes

- stop inventing vtable groups from a relocated pointer followed by a `type_info` pointer, which gave classes like `Actor`, `Mob` and `Level` a one-slot primary vtable
- order a class's vtable groups so the primary one (offset-to-top zero) is first, so `slotCount` reports the real primary run again
- read the offset-to-top word as plain data instead of a relocation target, which had reported offsets in the tens of millions

## v1.0.9

### Bug Fixes

- anchor each signature on its longest literal run, so a real 26.60 library no longer stalls for hours in signature round 1
- match signature candidates in groups of 8192 so the matcher's memory stays flat at a million candidates
- share slot records and signatures through `Arc` instead of copying them per vtable group, cutting the peak resident set on 26.60 from 1.79 GB to 1.37 GB
- stop capping generated signatures at 20 000; every vtable slot and symbol-named function now gets one

## v1.0.8

### Features

- export one `BEDROCK_OFFSETS_<version>.zip` with `manifest.json`, `JNI/` and `InternalCPP/`
- split the internal C++ table into one JSON per category, driven by an editable `categories.json`
- run the export in a foreground service so it survives closing or rotating the app, with retry and a persisted job
- show in-app progress with a determinate ring, stage, size and cancel, plus a system notification
- classify classes and functions by class, base class, function regex and namespace, with a Settings override
- generate signatures for every vtable slot and symbol-named function, extended until unique
- name destructors, mark overrides and inherited slots, and keep all vtable groups of a class
- separate data symbols from functions and explain each remaining suspect

### Bug Fixes

- reject vtables invented from `type_info` pointers inside base arrays, which truncated real slot runs to zero
- keep dots in versioned file names and derive the name and the JSON version from one function
- make colliding offset names unique with an `@<address>` suffix
- copy slot sizes, prologues and status from `eh_frame`
- release the scan payload as soon as an export job ends

## v1.0.7

### Features

- extract every internal C++ function and vtable slot from `.eh_frame` and RTTI, streamed to a file
- name internal functions from symbols, vtables, signatures and string anchors, with generated signatures
- export `JNI_OFFSETS_<version>.json` and `INTERNALCPP_OFFSETS_<version>.json` with version-aware names
- download both documents from a pinned bar on the Scanner tab
- add an APKs tab that lists Minecraft Bedrock downloads from mcpedl.org, newest first, with an Extract action
- blue, white and pink theme with animated surfaces, and working Settings toggles

### Bug Fixes

- preserve the OS error kind and errno when a mapping fails
- extract each library to a per-hash directory and re-extract once if the file vanishes mid-scan
- never cache a result whose vtable or internal pass failed
- keep the offset list, search box and download buttons on screen on wide layouts
- middle-ellipsize long symbol names and copy the full name on long press
- show the APK size separately from the library size
- report the ABI list when the arm64 library is missing from an APK
- show "none (shared object)" instead of `0x0` for a shared object entry point

## v1.0.0

### Features

- scan a queue of APKs one at a time ([2c5052b](https://github.com/Lynxifyy/Bedrock-Offsets/commit/2c5052bd55af60b6a99ffd79005d7be15ad4a4f4))
- polish the states, the splash and the feedback ([0b1133e](https://github.com/Lynxifyy/Bedrock-Offsets/commit/0b1133e32660ff413a5dcd63d19d682cb460a796))
- survive memory pressure and hostile inputs ([119f977](https://github.com/Lynxifyy/Bedrock-Offsets/commit/119f977141766cef4901a86358edd4c904044f8f))
- cross-check offsets against the library's symbol table ([ba769c7](https://github.com/Lynxifyy/Bedrock-Offsets/commit/ba769c783369fdd81a3a6f570c9ab3b36aeab44a))
- structured live log with tags, colour and bounded history ([dec79c1](https://github.com/Lynxifyy/Bedrock-Offsets/commit/dec79c146e2e947dff0bcdcbfe482be27aa93242))
- cache scan results keyed by build identifier ([c8fa25c](https://github.com/Lynxifyy/Bedrock-Offsets/commit/c8fa25c2a25d0402b6571f34a10328daddb0817a))
- export offsets to device storage ([1c8aace](https://github.com/Lynxifyy/Bedrock-Offsets/commit/1c8aace1b1cc9e67cb47a121fe3fa5451f1a8114))
- results panel with grouped offsets and filtering ([8b0994a](https://github.com/Lynxifyy/Bedrock-Offsets/commit/8b0994aedac19f7ce9c57b330cadb4107f076e08))
- report scan progress from the engine to the UI ([05fa693](https://github.com/Lynxifyy/Bedrock-Offsets/commit/05fa693df3c2ec3b4f5806a100373cf2ff5f227d))
- resolve RTTI vtables from the relocation table ([4adb2f0](https://github.com/Lynxifyy/Bedrock-Offsets/commit/4adb2f0cc1ee0606837ff0ead9597a00d593ec46))
- verify function prologues with Capstone ([516b202](https://github.com/Lynxifyy/Bedrock-Offsets/commit/516b2029dfb498a64409afe414aa6464726b4554))
- add the Aho-Corasick signature scanner ([eb11b14](https://github.com/Lynxifyy/Bedrock-Offsets/commit/eb11b14499a6696838585356337b0bec9f6b2f42))
- extract the build id and exported JNI symbols ([e38a536](https://github.com/Lynxifyy/Bedrock-Offsets/commit/e38a5360cc2209870045f9231c7b2e7f5813f586))
- read section headers and map virtual addresses ([a076475](https://github.com/Lynxifyy/Bedrock-Offsets/commit/a076475b7c81555054a2ad7316e68819c6a539fe))
- map the native library with zero-copy mmap ([e8388aa](https://github.com/Lynxifyy/Bedrock-Offsets/commit/e8388aa08a66078fc9efdf3778071e3f174011e6))
- add the Rust NDK JNI bridge ([16b39c7](https://github.com/Lynxifyy/Bedrock-Offsets/commit/16b39c7d51d6e067f48d67f1690e0d53ddc385d5))
- add async ViewModel with typed error mapping ([aa7bd67](https://github.com/Lynxifyy/Bedrock-Offsets/commit/aa7bd6711351009d75c7f993c3af635773d6bf07))
- add streaming ZIP extractor in the Rust core engine ([c62aeea](https://github.com/Lynxifyy/Bedrock-Offsets/commit/c62aeea087ee432052c65cc460863018f3b65823))
- add zero-copy SAF file picker for APK selection ([52f527a](https://github.com/Lynxifyy/Bedrock-Offsets/commit/52f527a73b08f22b9a39b5e43781665592af7472))
- add Compose shell, caramel theme and live log pane ([8232566](https://github.com/Lynxifyy/Bedrock-Offsets/commit/82325667f1c51ad248c02bf31abfc8893f61a111))
- scaffold Android Compose project with CI and R8 rules ([634f2fc](https://github.com/Lynxifyy/Bedrock-Offsets/commit/634f2fcdc553da84813a0a3f2571e428516588d3))

### Fixes

- harden the boundary between the engine and the device ([463d20f](https://github.com/Lynxifyy/Bedrock-Offsets/commit/463d20fd4b460360ea9cc38f9be06afb1bcc472e))

### Performance

- keep the release build small and provable ([93eabfc](https://github.com/Lynxifyy/Bedrock-Offsets/commit/93eabfc3855084a93fc69a788e4d945cba2f0352))

### Documentation

- explain the engine, the output format and the failure modes ([f8ce23a](https://github.com/Lynxifyy/Bedrock-Offsets/commit/f8ce23a6b8e035de48399ed5e96f6f1a36965c6d))

### Build

- automate the offset tooling in Python ([cda60b1](https://github.com/Lynxifyy/Bedrock-Offsets/commit/cda60b11008426ec9f8c37144c7fe4d1078a3a73))

### Other

- add reproducible local toolchain setup script ([ec315f3](https://github.com/Lynxifyy/Bedrock-Offsets/commit/ec315f3d125318b1ab281e555ac903a49d2b437b))

### Other

- Add greeting to Readme.md ([efb78d8](https://github.com/Lynxifyy/Bedrock-Offsets/commit/efb78d8308a1ab049ce3069650049f49dd435dde))
