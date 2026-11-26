# Audit, v2 engine

Every finding below is quoted with the file and line where it lives, at the
commit this branch started from (`main`, `1769e28`). Numbers labelled
"owner-reported" come from the 26.60 export the analyst produced; numbers
labelled "measured here" were produced by a command in this sandbox. The
26.60 `libminecraftpe.so` is **not** in this sandbox, so nothing about that
build can be re-measured here — see "What could not be measured".

## D1 — template arguments were discarded when naming a class

`app/src/main/rust/offset_core/src/rtti.rs:438-439` (main):

```rust
                // Template arguments do not change the class name.
                b'I' => break,
```

The comment is wrong. `std::__ndk1::__function::__func<int, ...>` and
`...<long, ...>` are different classes with different vtables, and the
arguments are exactly what tells them apart. Breaking at `I` also stops parsing
the rest of the nested name, so everything after the first template argument
was dropped.

**Effect (owner-reported):** `__func` appears as one class for 12,799
instantiations, `StrictTickingSystemFunctionAdapter` 643, `entt::basic_storage`
1,406; 62,306 vtable groups collapse to 6,386 rows.

**Fixed:** `demangle.rs` is a complete Itanium demangler (substitutions,
`L…E` literals, packs, expressions, local names, lambdas), validated against
`c++filt` — see "Demangler corpus" below.

## D2 — local classes and lambdas were dropped

`rtti.rs:429` accepted only `N…E`, `St…` or `<len><name>`:

```rust
fn demangle_type_name(text: &str) -> Option<String> {
```

A lambda's type starts with `Z` (`Z <encoding> E <entity>`), so it fell to
`None`. `is_name_byte` (`rtti.rs:527`) also rejected `$`, which is the first
character of `$_0`.

**Fixed:** the demangler parses `Z…E` and reports the local name; `naming.rs`
reads the enclosing function out of it (`Level::tick()::{lambda(int)#1}` →
`Level::tick()`).

## D3 — one row per demangled name

`rtti.rs:180-196` (main):

```rust
    let mut entries: BTreeMap<String, TypeVTable> = BTreeMap::new();
    ...
            .or_insert_with(|| resolved.clone());
```

`or_insert_with` keeps whichever vtable was seen first and silently discards
every other group of the same class — including the secondary base subobject
vtables a multiple-inheritance class needs. A class with three bases lost two
of its three groups.

**Fixed:** entries are keyed by the mangled name, which is unique; every group
is kept, with `offsetToTop`, `typeInfo` and `typeInfoKind` recorded, and
`baseName` kept as a secondary field for family lookups.

## D4 — slots named by the most-derived class

`internal.rs:1282` `insert_slot_names` and `internal.rs:1056`:

```rust
                        (format!("{}::vfunc_{slot}", vtable.name), "import")
```

Every class names its own slots, so `Mob::vfunc_0` and `Player::vfunc_0` are
different strings for the same function, and a name learned for one class never
reached the others.

**Fixed:** a slot identical to a base's slot is named after the class that
declares it, an override keeps its own class; then every slot row at the same
address is unified, so one address has one name across all classes
(`unify_slot_names_by_address`). `Class::vfunc_N` remains as an explicit
fallback label.

## D5 — 37 % of functions had no name and no signature

Owner-reported: 293,854 functions with `nameSource:"auto"`, `status:"fde_only"`,
`sub_<rva>`; 358,515 of 791,952 with no signature; 43,316 non-unique.

Cause, in code: names are inserted only from symbols, vtable slots and 91
patterns (`insert_symbol_names`, `insert_slot_names`, `apply_internal_patterns`
in `internal.rs:520-600`). Non-virtual member functions, static helpers and
free functions are in none of those sets.

**Partially fixed:** the naming engine adds evidence sources that do not need a
vtable (lambda call operators, unique vtable constructors, ECS adapters), and
signatures are generated for every function rather than only slots. The count
of `sub_` functions on 26.60 is **not** re-measured here.

## D6 — signature engine caps

`internal.rs:41`, `:45`, `:1912`:

```rust
pub const MAX_SIGNATURE_BYTES: usize = 48;
pub const MAX_SIGNATURE_EXTENDED_BYTES: usize = 128;
const MIN_SIGNATURE_RUN: usize = 10;
```

**Partial fix:** the caps are unchanged in this commit; wildcarding and the
anchor fallbacks are the next work package. Owner-reported: 205,295 functions
larger than 128 bytes have no signature.

## D7 — "completeness" counted enumeration

The manifest's "N of N functions" counts rows written, not names found. The
coverage script added in this branch (`scripts/coverage_report.py`) measures
real names, gameplay names, placeholder slots, unique signatures and class rows
against groups instead, so the number in the manifest cannot be read as
"everything is named".

## D8 — no cross-references

There was no caller/callee data in the export at all. **Fixed for the engine
side:** `xref.rs` decodes `BL`, `B` (tail calls only), `ADRP`+`ADD`, `LDR`
literal, `LDR Xn,[Xm,#imm]`, `BLR`/`BR`/`RET`, and produces callers, callees,
data references and a referrer index. It is not yet exported as a shard.

## D9 — output size

663 MB, with a 368 MB `other_named` document. The exporter streams category
documents and deflates the zip (`OffsetZip.kt:114-180`), but the default
category set still puts every unnamed and third-party row in the archive. Not
changed in this branch.

## D10 — 91 hand-written patterns

`patterns.json` remains the source for part 1; 23 of them are stale on 26.60
(owner-reported). Kept as regression fixtures, not fixed here.

## D11/D12 — classes without RTTI, ECS components

`HitResult`, `Options`, `ScreenContext`, `Tessellator`, `PacketSender`,
`MoveInputHandler`, `ClientInputHandler`, `BlockLegacy` have no vtables, so
`collect_class_slots` cannot see them. ECS components exist as template
arguments and strings only. The naming engine parses ECS adapter template
arguments into component lists (`naming.rs::parse_ecs_system`), which is the
first half of a registry; storage accessors are not recovered yet.

## Demangler corpus (measured here)

```
python3 scripts/build_demangle_corpus.py   # 179 types, 5942 symbols
cargo test --test demangle_corpus_test
test result: ok. 4 passed
```

6121 of 6123 corpus symbols agree with `c++filt` (99.97 %); the two
disagreements are libc++'s `1l` literal rendering in `std::ratio`, and 24 known
upstream `cpp_demangle` gaps are excluded from the comparison list by name.

## What could not be measured

| Claim | Why not | How to check |
|---|---|---|
| Real-name coverage of 26.60 | the library is not in this sandbox | run the app's export, then `python3 scripts/coverage_report.py export.zip` |
| Signature survival across versions | one APK only | import a second APK and compare `signature` fields |
| Entity-list walk | needs a running game | Validate screen + mod debug log |
| FPS / memory on device | no device here; cloud phone is used for UI smoke tests only | device profiler |
