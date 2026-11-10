# Architecture

The app is three pieces that never share a byte buffer:

```
 SAF picker ──fd──▶ Rust engine (mmap, read-only) ──JSON──▶ Kotlin ViewModel ──▶ Compose UI
 (Kotlin)          (offset_core)                     (Gson)      (StateFlow)        (stateless)
```

A 2 GB APK and an 800 MB library move through that path without a single
allocation sized by the input. This document explains how, and where the
boundaries are.

## Why zero-copy is mandatory, not an optimisation

The target is a tablet with limited RAM, and Android will kill a process that
crosses its heap ceiling. `android:largeHeap` is set as a safety net, but a
large heap is still a few hundred megabytes, and a library can be larger than
that. Any design that reads the library into a `ByteArray` fails on the exact
device the app is for.

So the rule is absolute: **no component ever holds the whole APK or the whole
library in memory**. It is enforced in three places:

1. **Extraction** streams the archive through a fixed 64 KB buffer
   (`zip_extract.rs`).
2. **Mapping** uses `mmap` (`memmap2`), so the library costs address space, not
   resident memory; only the pages actually touched are faulted in
   (`elf.rs`).
3. **Scanning** splits the mapped bytes into 4 MB chunks and hands each worker a
   borrowed slice (`scanner.rs`).

Peak resident memory is measured, not assumed: the engine reads `VmHWM` from
`/proc/self/status` and reports it with the result. The integration test
`scanner_test.rs::a_64_mb_section_scans_within_the_memory_budget` asserts the
growth stays under 100 MB while scanning a 64 MB section.

## The pipeline

### 1. Storage access (Kotlin, `data/saf`)

The Storage Access Framework hands back a content `Uri`. The app asks
`contentResolver.openFileDescriptor(uri, "r")` for a `ParcelFileDescriptor` and
passes the integer descriptor across JNI. No file path is ever constructed, so
there is nothing for scoped storage to reject. If the descriptor cannot be
opened, the APK is copied to the app cache as a fallback and that path is used
instead.

The descriptor is duplicated inside the engine (`fd.rs` uses `libc::dup`), so
the engine owns a descriptor that stays valid for the whole scan even if the
caller closes its own.

### 2. Streaming extraction (Rust, `zip_extract.rs`)

The ZIP central directory is parsed from the descriptor, and
`lib/arm64-v8a/libminecraftpe.so` is streamed into the app cache through a
64 KB buffer. The SHA-256 is computed on the same pass with `sha2`, so the file
is read once, not twice. A missing entry returns `CoreError::MissingSoFile`,
which Kotlin maps to a message about Java Edition or a bundle without arm64
libraries.

### 3. Zero-copy mapping (Rust, `elf.rs`)

The extracted file is mapped read-only. `Elf::lazy_parse` decodes only the
header; section headers, symbols and relocations are materialised by the phases
that need them. The full `Elf::parse` would eagerly build every table, which on
an 800 MB library is exactly the allocation this design exists to avoid.

The header is validated before anything else: ELF magic, then the machine type
(`EM_AARCH64`). A 32-bit object is rejected with
`CoreError::UnsupportedArchitecture`, which surfaces as "Only arm64-v8a is
supported."

### 4. Section map (Rust, `sections.rs`)

Section names come from `.shstrtab`. `.text`, `.rodata` and `.data.rel.ro` are
located, and for each the virtual address, size and file offset are recorded.
The delta `sh_addr - sh_offset` is the number that turns a file offset into the
address an offset would have once loaded:

```
address = file_offset + delta
```

Every later phase reports both forms, because a file offset is stable across
loads and a virtual address is what a loader patches.

### 5. Signature scanning (Rust, `scanner.rs` + `patterns.rs`)

A signature like `48 8B ?? ?? 89` is compiled into a byte mask plus a boolean
wildcard mask. The exact (non-wildcard) bytes are fed to an
[Aho-Corasick](https://en.wikipedia.org/wiki/Aho%E2%80%93Corasick_algorithm)
automaton, which finds every candidate in one pass over the section. Each
candidate is then checked against the wildcard mask by hand. The automaton
narrows the search; the manual check decides.

The section is then split into 4 MB chunks, one per Rayon worker. Chunks overlap
by 32 bytes so a match straddling a boundary is still found, and duplicates
introduced by the overlap are removed afterwards. An `AtomicBool` is checked
inside the parallel loop, so cancelling a scan stops within one chunk instead of
running to completion.

### 6. Prologue verification (Rust, `prologue.rs`)

A byte-pattern match is a guess. The 16 bytes at the match are disassembled with
[Capstone](https://www.capstone-engine.org/) for `CS_ARCH_ARM64`, and the match
is kept only if the instructions look like a real function prologue, such as
`STP x29, x30, [sp, #-N]!` or a `PACIBSP` frame. Matches that do not decode to
a prologue are discarded and counted. This is what turns "these bytes appear" into
"a function probably starts here."

### 7. RTTI vtable resolution (Rust, `rtti.rs`)

Type names in `.rodata` are recognised by their length-prefixed form
(`14ClientInstance` is the name `ClientInstance` with a 14-byte prefix).
`.rela.dyn` relocations are walked to find the pointer that targets each name;
the pointer immediately before it in `.data.rel.ro` is the vtable. If the
relocations are stripped, a direct scan of `.data.rel.ro` for the same pointer
value is the fallback.

### 8. Symbol cross-check (Rust, `verify.rs`)

The dynamic symbol table (`.dynsym`) is parsed and the exported `Java_*` symbols
are extracted. A pattern hit whose name matches a symbol within 4 bytes is
tagged `VERIFIED`; a hit that disagrees is tagged `CONFLICT`; a hit with no
symbol to compare against is `UNVERIFIED`. The confidence score is
`verified / total`, and it is deliberately `0` when there is nothing to compare,
so a stripped library reports no confidence rather than a misleading 100%.

## The JNI boundary

`NativeLib.kt` is the only place Kotlin touches the engine. Every native method
returns a JSON string and throws `EngineException` (a typed failure with an
`EngineErrorKind`) on error; the message is never parsed out of a string.

Three rules make that boundary safe:

- **Panics do not escape.** `android.rs::catch_panics` wraps every entry point,
  so a panic in the engine becomes an error value. The panic hook still logs the
  message and a backtrace first, so a caught panic is never silent.
- **Progress flows one way.** The engine holds a `GlobalRef` to a Kotlin
  `ProgressCallback` and calls `onProgress(percent, message)`. A Rayon worker is
  not attached to the JVM, so `progress.rs` attaches it for the call and detaches
  after; the local string reference is deleted explicitly. A callback that throws
  has its exception cleared, because returning to Java with a pending exception
  is undefined behaviour.
- **Cancellation is a flag, not a thread kill.** `CancellationSignal` sets an
  `AtomicBool` the scan polls; nothing is interrupted asynchronously.

## Kotlin layering

- `data/native` — the JNI surface and the models it returns.
- `data/saf` — descriptor acquisition, the cache-copy fallback, document writes.
- `data/cache` — extracted libraries and cached JSON, with zero-overwrite
  deletion.
- `data/export` — the JSON document a loader reads.
- `ui/viewmodel` — one `MainViewModel` owns the state machine, the batch queue
  and the log. Work runs on `Dispatchers.IO` under a `SupervisorJob`, so one
  failed APK in a batch does not take the ViewModel with it.
- `ui/screens`, `ui/components` — stateless composables driven by state.

The ViewModel survives rotation because it is retained by the activity's
`ViewModelStore`, and the scan runs in `viewModelScope` rather than in the
activity. Rotating the device mid-scan changes nothing.

## Where the memory goes

| Stage | Held in memory | Scales with input? |
|---|---|---|
| Extraction | 64 KB buffer | no |
| Mapping | the mapping (address space) | address space only |
| Section map | one struct per section | no |
| Scan | 4 MB chunk + result vector | results only |
| Prologue check | 16 bytes per match | no |
| Symbol check | `.dynsym` entries | with symbol count |
| Kotlin | the JSON result | results only |

The only term that grows with the input is the result list, and a signature pack
is small by construction.

## Further reading

- [`docs/OUTPUT_FORMAT.md`](docs/OUTPUT_FORMAT.md) — the JSON a loader reads.
- [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) — JNI crashes and what
  they mean.
- [`SECURITY.md`](SECURITY.md) — the threat model.
