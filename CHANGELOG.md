## v1.0.12

### Bug Fixes

- delete the downloaded APK once the library is extracted and hashed, and the extracted library once the internal table is validated, so one scan of a 1 GB build no longer leaves about 2 GB behind; "Delete downloaded APK after scan" is a Settings switch and the storage list shows what is left
- remove the duplicate category split: the engine classifies every record while it streams and writes one finished JSON document per category, so the export no longer parses 890,000 rows twice or re-serialises them
- copy those documents into the archive through a 64 KB buffer and delete each one as soon as its entry is closed, and write the JNI document with a `JsonWriter` instead of building the object graph first, which is what requested the 77 MB allocation that failed with 6 MB free
- compress the archive at level 6 instead of 9
- keep the result card whole: the results pane was measured wrap-content while it contained a weighted list, so it took the screen and pushed its own pinned controls out of view, cutting the card at the same pixel every time
- move Copy, Share and Save JNI into one overflow menu so the pinned bar leaves the card room, and keep the progress pill clear of the navigation bar and the action row
- translate every export failure into one plain sentence, with the technical text kept in the log
- cap the in-memory log at 2,000 lines and raise it from 500 only because the log file now carries the rest
- report a vtable slot cap in the internal summary instead of truncating silently

### Features

- category documents are written by the engine with the same `categories.json` the UI uses, and a shared expectation set in the Kotlin and Rust tests keeps the two classifiers identical
- motion tokens (`ui/theme/Motion.kt`): three durations, four easings, three named springs, a 4 dp spacing scale and a reduce-motion switch that collapses every animation to a snap

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
