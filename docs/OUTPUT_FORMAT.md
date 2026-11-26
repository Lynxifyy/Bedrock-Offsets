# Offset document format

A scan is saved as one ZIP. This is the contract between the extractor and the
loader that consumes the offsets (`HoneyLoader` or an equivalent). The archive
is produced by `data/export/OffsetZip.kt`; the Rust engine produces the pieces,
Kotlin assembles and streams them.

## ZIP layout

```
BEDROCK_OFFSETS_<version>.zip
├── manifest.json
├── JNI/
│   └── JNI_OFFSETS_<version>.json
└── InternalCPP/
    ├── INTERNALCPP_INDEX_<version>.json
    ├── INTERNALCPP_BLOCK_PLACEMENT_<version>.json
    ├── INTERNALCPP_ACTOR_ENTITY_<version>.json
    ├── ...
    ├── INTERNALCPP_OTHER_NAMED_<version>.json
    └── INTERNALCPP_UNNAMED_PART_01_<version>.json ... PART_NN
```

`<version>` is produced by one function, `OffsetExporter.versionLabel`. It comes
from the APK manifest `versionName` first, then the APK file name, then the
build id prefix. Dots are kept, so `26.60.3` stays `26.60.3`; only characters
that are unsafe in a file name are removed and `+` is never used. The same
string appears in every file name and in `source.apkVersion`, so a name can
never disagree with the document.

`manifest.json` lists every other entry with its size, its record count and its
SHA-256. The archive is re-opened after writing and every CRC, size and digest
is checked; a mismatch fails the download instead of shipping a broken file.

```json
{
  "schemaVersion": 1,
  "kind": "bedrock_offsets_zip",
  "generatedBy": "BedrockOffsetExtractor",
  "generatedAt": "2026-11-25T05:12:00Z",
  "version": "26.60.3",
  "apk": "minecraft-26-60-arm64-v8a-xbox.apk",
  "buildId": "...",
  "librarySha256": "...",
  "entryCount": 17,
  "files": [
    {
      "path": "JNI/JNI_OFFSETS_26.60.3.json",
      "size": 1234,
      "entries": 91,
      "sha256": "..."
    }
  ]
}
```

The category documents are written by the Rust engine in one streaming pass:
it receives the same `categories.json` the UI uses, classifies every record as
it writes it, and closes one finished JSON document per category. The export
copies those documents into the archive byte for byte and deletes each one once
its entry is closed, so no row is ever parsed, re-serialised or held on the Java
heap. An older cached result that still has a single NDJSON table is split by
`InternalTableSplitter`; the log says so when that path is taken.

The JNI document under `JNI/` is the v1 document described below and is
unchanged. The `InternalCPP/` folder holds one file per category plus an index.

### The archive always carries the internal data

An archive without `InternalCPP/` is worthless to a client that came for the
C++ offsets, and it verifies against its own manifest, so nothing downstream can
notice the loss. Three guards make that state unreachable:

- the engine writes the NDJSON table into `filesDir/scan-data`, which the OS
  never trims and auto-backup excludes, and the scan fails rather than reporting
  success when the table it just wrote cannot be read back;
- the exporter refuses to write an archive when the table is missing, empty or
  shorter than the scan reported, and refuses again when the category files do
  not add up to the table's rows;
- the finished archive is re-opened, and every entry's CRC, size and digest is
  checked along with the presence of every category file.

The UI follows the same rule: once the data is gone, the download button becomes
"Internal data was cleared. Extract it again to download", and the success toast
reports how many files and internal rows the archive holds.

The current schema version is **1**. A field is only ever added within a
version; a rename or a removal bumps `schemaVersion`.

## Example

```json
{
  "schemaVersion": 1,
  "generatedBy": "BedrockOffsetExtractor",
  "source": {
    "apk": "minecraft-1.21.50.apk",
    "architecture": "AArch64",
    "librarySha256": "9f2c...e1",
    "librarySizeBytes": 24681024,
    "buildId": "a1b2c3d4e5f60718",
    "buildIdSource": "note",
    "entryPoint": "0x1a2b3c",
    "textAddress": "0x1a2b3c",
    "textSize": 14680064,
    "textDelta": "0x10000"
  },
  "offsets": {
    "vtable:ClientInstance": "0x2c4a100",
    "pattern:Java_com_mojang_minecraftpe_MainActivity_nativeResize": "0x889c618",
    "pattern:Java_com_mojang_minecraftpe_MainActivity_isEduMode": "0x889df5c",
    "pattern:Java_com_mojang_minecraftpe_MainActivity_nativeShutdown": "-",
    "symbol:Java_com_mojang_nativeTick": "0x1d0a4c8"
  },
  "verification": {
    "verified": 90,
    "conflicts": 0,
    "unverified": 1,
    "symbols_available": true
  },
  "vtables": {
    "ClientInstance": {
      "address": "0x2c4a100",
      "offset": "0x2c4a100",
      "typeInfo": "0x2c4a0e0"
    }
  },
  "symbols": {
    "Java_com_mojang_nativeTick": "0x1d0a4c8"
  },
  "stats": {
    "expectedSignatures": 91,
    "matchedSignatures": 91,
    "discardedMatches": 0,
    "vtables": 1,
    "symbols": 214,
    "verifiedOffsets": 4,
    "conflictingOffsets": 0,
    "unverifiedOffsets": 6,
    "confidencePercent": 40
  }
}
```

## Top level

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | number | Format version. Reject a document with a version you do not know. |
| `generatedBy` | string | Always `BedrockOffsetExtractor`. |
| `source` | object | Identity of the library the offsets came from. |
| `offsets` | object | Flat name to address map. This is what a loader reads. |
| `verification` | object | How the offsets were cross-checked. |
| `vtables` | object | Resolved vtables, by type name. |
| `symbols` | object | Exported symbols, by name. |
| `stats` | object | Counts that describe the scan. |

## `source`

The identity block. Compare `librarySha256` or `buildId` before trusting any
offset: an offset from a different build is a wrong number, not a stale one.

| Field | Type | Meaning |
|---|---|---|
| `apk` | string | Display name of the APK, for a human reader. Not a path. |
| `architecture` | string | Always `AArch64`. The engine rejects anything else. |
| `librarySha256` | string | Lower-case hex SHA-256 of `libminecraftpe.so`. |
| `librarySizeBytes` | number | Size of the extracted library. |
| `buildId` | string | GNU build ID from `.note.gnu.build-id`, or a pseudo-ID when absent. |
| `buildIdSource` | string | `note` when it came from the note section, `text-hash` when it was derived. |
| `entryPoint` | string | ELF entry point as `0x`-prefixed hex. |
| `textAddress` | string | Virtual address of `.text`, when the section exists. |
| `textSize` | number | Size of `.text` in bytes. |
| `textDelta` | string | `sh_addr - sh_offset` for `.text`, as `0x`-prefixed hex. |

Gson omits a field whose value is `null`, so a library without a `.text` section
simply has no `textAddress`, `textSize` or `textDelta` key. Treat a missing key
as unknown rather than as zero.

## `offsets`

The flat map a loader consumes. Keys are namespaced by how the value was found:

| Prefix | Source | Stability |
|---|---|---|
| `vtable:` | RTTI vtable resolution | Stable while the class layout is unchanged. |
| `pattern:` | A verified signature match | Follows the signature; re-derive per build. |
| `symbol:` | An exported dynamic symbol | Most stable, but absent from a stripped library. |

Values are `0x`-prefixed lower-case hex virtual addresses. The literal string
`"-"` means **not found in this build**: the signature was expected but no
verified match existed. A loader must treat `"-"` as a failure, not as address
zero.

## `verification`

| Field | Type | Meaning |
|---|---|---|
| `verified` | number | A signature and a symbol of the same name agreed. |
| `conflicts` | number | A signature and a symbol of the same name disagreed. |
| `unverified` | number | Nothing to compare against. Normal for a stripped library. |
| `symbols_available` | boolean | Whether `.dynsym` held anything to compare with. |

`verified + conflicts + unverified` is the total number of offsets considered.
Confidence is `verified / total`, and is reported as `0` when the total is `0`,
so a stripped library never claims a perfect score. A non-zero `conflicts` is
the signal to re-check the signature pack before using the document.

## `vtables`

Keyed by type name. Each entry has:

| Field | Type | Meaning |
|---|---|---|
| `address` | string | Virtual address of the vtable. |
| `offset` | string | The same address as a file offset, which survives a rebase. |
| `typeInfo` | string | Virtual address of the type's `type_info` record. |

## `stats`

Diagnostics for a human. Nothing here is required to use the offsets.

| Field | Meaning |
|---|---|
| `expectedSignatures` | Signatures in the pack that were searched for. |
| `matchedSignatures` | Signatures with at least one verified hit. |
| `discardedMatches` | Candidates rejected by prologue verification. |
| `vtables` | Vtables resolved. |
| `symbols` | Exported symbols found. |
| `verifiedOffsets` / `conflictingOffsets` / `unverifiedOffsets` | Same counts as `verification`. |
| `confidencePercent` | `confidence * 100`, truncated. |

## `jniOffsets`, `signatures` and `coverage`

`offsets` is the pattern-only view, kept so an existing loader keeps working.
The complete JNI set is `jniOffsets`: the union of every source, so a native no
signature knows is still present.

```json
"jniOffsets": [
  {
    "javaName": "nativeInit",
    "signature": "(JLjava/lang/String;)Z",
    "class": "com/mojang/minecraftpe/MainActivity",
    "address": "0x5f0a41c0",
    "offset": "0x5f0a41c0",
    "source": "register_natives",
    "confidence": "high"
  }
]
```

| Field | Type | Meaning |
|---|---|---|
| `javaName` | string | Symbol name, table name, or pattern name. |
| `signature` | string | JNI descriptor, when a `RegisterNatives` table supplied one. |
| `class` | string | Owning class, when a class descriptor was found near the table. |
| `address` | string | Virtual address, `0x`-prefixed hex. |
| `offset` | string | `address - imageBase`; the value that survives a rebase. |
| `source` | string | `symbol`, `entry_point`, `register_natives` or `pattern`. |
| `confidence` | string | `high` (linker or loader wrote the address), `medium` (pattern plus a decoded prologue), `low` (nothing confirmed it). |

`signatures` reports every pattern that was searched for, so a pattern that
found nothing is visible instead of absent:

| Field | Type | Meaning |
|---|---|---|
| `name` | string | Pattern name. |
| `status` | string | `matched` (one hit), `ambiguous` (several hits, all kept in `jniOffsets`), `not_found` (no hit, no symbol), `symbol_only` (no usable hit, but the symbol named it: the pattern needs updating). |
| `hits` | number | Hits the pattern produced. |

`coverage` counts every source:

| Field | Type | Meaning |
|---|---|---|
| `exports` | number | Exported `Java_*` symbols. |
| `entryPoints` | number | `JNI_*` lifecycle exports such as `JNI_OnLoad`. |
| `registered` | number | Natives found in `RegisterNatives` tables. |
| `patterns` | number | Patterns searched for. |
| `patternsMatched` / `patternsAmbiguous` / `patternsNotFound` | number | Status totals. |
| `patternsStale` | string[] | Patterns whose function the symbol table named. |
| `union` | number | Entries in `jniOffsets`. |
| `truncated` | boolean | A source hit its own cap, so the union is incomplete. |

Both `jniOffsets` and `signatures` are absent when the engine sent no union,
which happens only with an engine older than this field.

## Versioning rules

1. Adding an optional field keeps `schemaVersion` unchanged. A reader must
   ignore keys it does not know.
2. Removing a field, renaming one, or changing a value's type or meaning bumps
   `schemaVersion`.
3. The `offsets` map is the stable surface. Everything else is context.
4. Addresses are strings, not numbers. JavaScript loses precision above 2^53 and
   a loader may be written in JavaScript.

## Checking two documents against each other

`scripts/verify_offsets.py` diffs two documents and reports added, removed and
changed offsets:

```bash
python3 scripts/verify_offsets.py offsets_1.21.0.json offsets_1.21.50.json
```

Exit codes: `0` when every offset matches, `1` when something changed, `2` when
an input cannot be read. Use it before shipping a signature pack for a new build.

## Internal C++ document

The internal extraction is split by category. `INTERNALCPP_INDEX_<version>.json`
is the entry point: it lists every category file with its counts and maps each
class to its category, so a client can open only the file it needs. A combined
`INTERNALCPP_OFFSETS_<version>.json` is written only when the Settings toggle
"Also include one combined file" is on.

```json
{
  "schemaVersion": 1,
  "generatedBy": "BedrockOffsetExtractor",
  "kind": "internal_cpp_index",
  "version": "26.60.3",
  "source": {
    "apk": "minecraft-26-60-arm64-v8a-xbox.apk",
    "apkVersion": "26.60.3",
    "architecture": "AArch64",
    "librarySha256": "...",
    "buildId": "...",
    "imageBase": "0x0",
    "textAddress": "0x...",
    "textSize": 0,
    "textDelta": "0x0"
  },
  "stats": {
    "classes": 7396,
    "functions": 803306,
    "dataSymbols": 0,
    "categories": 17
  },
  "categories": [
    {
      "id": "actor_entity",
      "category": "actor_entity",
      "label": "Actors and entities",
      "file": "InternalCPP/INTERNALCPP_ACTOR_ENTITY_26.60.3.json",
      "classes": 120,
      "functions": 41000,
      "symbols": 0
    }
  ],
  "classToCategory": {
    "Player": "actor_entity"
  }
}
```

## Category file

Every category file has the same `source` block, its own `stats`, the classes
that belong to it (with all their slots, bases and signatures) and its
functions. A class is written to exactly one file; a base class in another
category is still listed by name in `bases`.

```json
{
  "schemaVersion": 1,
  "generatedBy": "BedrockOffsetExtractor",
  "kind": "internal_cpp_category",
  "category": "actor_entity",
  "label": "Actors and entities",
  "version": "26.60.3",
  "source": { "...": "same block as the index" },
  "stats": {
    "classes": 120,
    "functions": 41000,
    "symbols": 0,
    "entries": 41120
  },
  "classes": {
    "Player": {
      "mangled": "6Player",
      "bases": ["Mob", "Actor"],
      "vtable": "0x...",
      "typeInfo": "0x...",
      "slotCount": 247,
      "slots": [
        {
          "slot": 0,
          "offset": "0x0",
          "address": "0x...",
          "fileOffset": "0x...",
          "size": 12,
          "name": "Player::~Player",
          "kind": "virtual",
          "prologueKind": "frame_push",
          "overrides": true,
          "inherited": false,
          "status": "verified",
          "signature": "FD 7B ?? A9 ...",
          "signatureUnique": true
        }
      ],
      "vtableGroups": [
        {
          "offsetToTop": 0,
          "address": "0x...",
          "slots": ["..."]
        }
      ]
    }
  },
  "functions": [
    {
      "address": "0x...",
      "fileOffset": "0x...",
      "size": 12,
      "name": "...",
      "nameSource": "eh_frame|symbol|vtable|signature|string|auto",
      "aliases": [],
      "kind": "function|thunk|pure_virtual|import",
      "status": "verified|fde_only|suspect|ambiguous|not_found|fde_missing|import",
      "suspectReason": "overlapping_fde|unverified_name|no_fde_no_prologue",
      "prologueKind": "frame_push|none",
      "signature": null,
      "signatureUnique": null
    }
  ],
  "symbols": [
    {
      "address": "0x...",
      "size": 8,
      "name": "_binary_classes_dex_size",
      "mangled": "_binary_classes_dex_size",
      "kind": "data",
      "reason": "object_symbol"
    }
  ]
}
```

`classes`, `functions` and `symbols` are streamed: the Rust engine writes an
NDJSON table in the app cache, the exporter splits it by category, and the ZIP
writer copies each category through a `JsonWriter` so a 340 MB library never has
to become one giant `String`.

Addresses are image-relative virtual addresses, the same convention as the JNI
document. A slot's `size` is the FDE size of the function at that address;
a slot whose function has no FDE keeps `size: 0` and is marked `fde_missing`,
while a slot that points at an unresolved import is marked `import` and keeps
its place in the run. `vtableGroups` lists every vtable of a class with multiple
inheritance; `slots` repeats the primary group for compatibility.

Data symbols such as `_binary_classes_dex_size` and `__emutls_t.*` are not
functions. They are emitted in `symbols` with `kind: "data"` and are excluded
from the function table, the stats and the suspect count.

## Category rules

The split is data, not code. `app/src/main/assets/categories.json` is an
ordered list of rules; the first match wins. Settings > Download imports a
custom file and can reset to the bundled one. See
[CATEGORIES.md](CATEGORIES.md) for the rule format and the starter categories.
