# Adding internal signatures

The internal C++ extraction recovers functions without symbols from `.eh_frame`
and from RTTI vtables. Two optional parts of `app/src/main/assets/patterns.json`
let you attach a human name to a function the binary cannot name by itself.

Both lists are additive. A pattern that matches nothing is reported as
`not_found`; a pattern that matches more than once is reported as `ambiguous`
with every candidate listed. The extractor never guesses.

## Internal byte signatures

```json
{
  "internalPatterns": [
    { "name": "Level::tick", "pattern": "FF 03 01 D1 ?? ?? 02 A9", "resolve": "self", "offset": 0 }
  ]
}
```

- `pattern` is a sequence of hex bytes. `??` matches any byte.
- `resolve` controls which address is reported:
  - `self` reports the match address plus `offset` (default).
  - `bl_target` decodes the first `BL` inside the match and reports its callee.
  - `adrp_add` decodes an `ADRP`+`ADD` pair and reports the global it points at.
  - `prologue_back` walks back to the nearest verified function start from the
    `.eh_frame` table.
- `offset` is a signed byte adjustment applied by `self`.

Keep the pattern short and specific. A four-instruction window that includes a
call or a global reference is usually unique; a bare prologue is not.

## String anchors

```json
{
  "stringAnchors": [
    { "name": "Actor::setName", "string": "actor_name" }
  ]
}
```

The extractor finds the string in `.rodata`, then finds every `ADRP`+`ADD` or
`ADRP`+`LDR` pair in `.text` that materialises its address. Each reference is
mapped to its enclosing function through the `.eh_frame` table.

Prefer a string that appears once. A common log prefix will produce dozens of
candidates and an `ambiguous` result.

## How signatures are made unique

Every vtable slot and every symbol-named function gets a signature. It starts as
the first 48 bytes of the function with relocatable operands wildcarded (`ADRP`
and `ADD` immediates, `B`/`BL` targets, `LDR` literal), and is extended in 16-byte
steps up to 512 bytes until it selects exactly one function start in `.text`. A
signature that is still ambiguous at 512 bytes is stored with
`signatureUnique: false`, and `stats.truncatedSignatures` says the cap was hit.

Four things keep that pass inside a tablet's memory:

- a signature is held as bytes plus a wildcard bitmap and rendered only while a
  row is written. The hex text a row shows is about three times the bytes it
  describes, and on a 26.60 library there is one pattern per named function;
- the first eight bytes at every function start are collected once and sorted, so
  a signature whose first eight bytes occur at exactly one start is unique by
  construction and never needs a scan. Only the rest go through the matcher;
- the matcher runs over one window of `.text` at a time, sized from the widest
  reach, with the patterns borrowed rather than copied, and the pages behind each
  window are released as it advances;
- the extension rounds stop at `SIGNATURE_MEMORY_BUDGET` (192 MB) and say so in
  the live log. Reaching 512 bytes on a few hundred thousand patterns at once is
  what the low-memory killer ended on a 3 GB tablet.

Measured on the host (`real_library_stats`, 700,000 functions, 500,000 named):

| stage | before | after |
|---|---|---|
| peak resident, worst case (500,000 patterns still ambiguous) | 929 MB | 709 MB |
| peak resident, realistic (most patterns resolve in the first round) | — | 556 MB |
| round 1 (48 bytes) | 460 MB | 460 MB |
| round 5 (112 bytes) | 683 MB | 539 MB |

On a 2 GB cloud phone the same 700,000-function library completes: the live log
showed 761 MB at round 1 and 790 MB at round 2, and logcat recorded no process
exit for the whole scan.

## Testing a new entry

```bash
cd app/src/main/rust/offset_core
cargo test
```

Then run a scan and check the live log for
`[RUST] Pass D/E: X verified, Y ambiguous, Z not found`. A `not_found` entry is
harmless; it is reported so a stale signature is visible rather than silent.
