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

## Testing a new entry

```bash
cd app/src/main/rust/offset_core
cargo test
```

Then run a scan and check the live log for
`[RUST] Pass D/E: X verified, Y ambiguous, Z not found`. A `not_found` entry is
harmless; it is reported so a stale signature is visible rather than silent.
