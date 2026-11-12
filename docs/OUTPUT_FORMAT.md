# Offset document format

A scan writes one JSON file. This is the contract between the extractor and the
loader that consumes the offsets (`HoneyLoader` or an equivalent). The document
is produced by `data/export/OffsetExporter.kt`; the Rust engine produces the
pieces, Kotlin assembles them.

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
