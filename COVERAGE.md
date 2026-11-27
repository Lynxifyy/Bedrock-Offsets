# Coverage

Two kinds of number appear here. **Owner-reported** figures come from the
26.60 export produced on the tablet; this sandbox does not have that
`libminecraftpe.so`, so they cannot be re-measured here. **Measured here**
figures come from a command in this repository, on a fixture or on the
demangler corpus, and can be reproduced.

Reproduce any export yourself:

```
python3 scripts/coverage_report.py BEDROCK_OFFSETS_<version>.zip \
    --json coverage.json --markdown COVERAGE_export.md
```

The script counts a name as real only when it is neither `sub_<rva>` nor
`Class::vfunc_N`, splits gameplay from third-party code, and compares class rows
with vtable groups.

## Baseline (owner-reported, 26.60, before this branch)

| Metric | Value |
|---|---:|
| Functions enumerated | 791,952 |
| Functions with a real method name | 75,817 (9.6 %) |
| Of those, gameplay classes (`Level::`, `Player::`, `Actor::`, `ClientInstance::`, `GameMode::`, `BlockSource::`) | 0 |
| Functions with no signature | 358,515 (45 %) |
| Functions with a non-unique signature | 43,316 |
| Unique signatures | 390,121 (49 %) |
| Vtable groups | 62,306 |
| Class rows | 6,386 |
| Slot rows in `classes` | 178,691 of 865,399 |
| Functions named `sub_<rva>` | 293,854 (37.1 %) |
| Functions larger than 128 bytes with no signature | 205,295 |

## What this branch changes, and how it is measured

| Work package | Change | Evidence |
|---|---|---|
| WP1 | complete Itanium demangler, corpus-checked | 6121/6123 corpus symbols agree with `c++filt` (99.97 %), 0 engine misses. `cargo test --test demangle_corpus_test` |
| WP1 | classes keyed by full mangled identity; all vtable groups kept | `rtti.rs` unit tests; a two-instantiation fixture keeps two entries |
| WP1 | base graph from the three `type_info` shapes | `resolve_bases` tests (si-class, vmi-class, unknown) |
| WP3 | declaring-class slot names plus address-wide unification | `a_name_learned_for_one_class_reaches_every_class_sharing_the_address` |
| WP3 | lambda call operator named from the enclosing function | `a_lambda_call_operator_is_named_from_its_vtable_slot` |
| WP3 | ECS adapter parsed into system + Include/Exclude lists | `an_ecs_adapter_yields_its_system_and_components` |
| WP2 | xref table: calls, tail calls, ADRP+ADD data refs, referrers | 6 `xref.rs` tests on a synthetic ELF |
| WP4 | signature length cap 128 → 512 bytes (rounds at 48…512) | `libc++_shared.so` (aarch64, 1591 signatures): unique 1,523 → **1,583** (95.7 % → 99.5 %); slot signatures 839/841 → **841/841**; pass 0.09 s → 0.31 s |
| WP4 | round-based planner replaced by one plan pass (see below) | 800,000-function fixture: stage **846 s and unfinished** (round 2 of 29) → **294-316 ms**; whole pipeline 9.2 s at 635 MB peak; `cargo run --release --bin bench -- /tmp/scale_a.so` |
| WP0 | coverage measured from the export, not from enumeration | `scripts/coverage_report.py --self-test` |

## Measured here

| Suite | Result |
|---|---|
| `cargo test --all-targets` | 185 + 66 tests, 0 failures (5 ignored) |
| `cargo run --release --bin bench -- /tmp/scale_a.so` | 800,000 functions, 6,386 vtable groups: 496,600 signatures, **417,721 unique (84.1 %)**, 78,879 in identical-body groups with an anchor; 9.2 s wall, 635 MB peak |
| `:app:testDebugUnitTest` | 325 tests, 0 failures |
| `scripts/coverage_report.py --self-test` | ok, fixture with known answers |
| Device run (cloud phone, Android 14) | fixture library scanned: 662 functions, 89 classes, 317 named, 305 unique signatures, export zip 61 KB written and re-parsed by `coverage_report.py` |
| Device export coverage, pre.1 build | overall 81/662 named (12.24 %), gameplay 34/606 (5.61 %), third party 47/56 (83.93 %), 305 of 307 signatures unique, 89 class rows for 89 groups |
| Device export coverage, 1.1.0-pre.2 release build | overall **82/662 named (12.39 %)**, 307 of 307 signatures unique, 89 class rows for 89 groups — the one new name is the `Level::tick()::$_0` lambda body, and the two colliding signatures were separated by the 512-byte reach |
| Device run, 1.1.0-pre.3 debug build, 4,000-function fixture | host `bench` 2,465 unique signatures, phone **2,465** — the two agree; 2,627 signatures, 89 classes, 576 slots |
| Device run, 1.1.0-pre.3 debug build, **800,000-function library** on a **2 GB** phone | 800,000 functions, 6,386 classes, 41,505 slots, 496,600 signatures, **417,721 unique**, 6,477 Part 1 entries, **no OOM, no kill**; 878 MB resident at the table write (measured by the app's own counter) |
| Device export, 800,000-function library | 13 entries, 381.3 MB of JSON raw, **20.3 MB zipped**, largest single file 121 MB raw — against the owner's 663 MB 26.60 export, on a build with more functions |
| `scripts/coverage_report.py` on that device export | 480,000 of 800,000 named (60.0 %), 417,721 unique signatures (52.2 %), 6,386 class rows for 6,386 groups — nothing collapsed |

The fixture is a 451 KB library built for this test, not Minecraft, so its
percentages say nothing about 26.60. They do show the pipeline end to end: the
export parses, class rows equal vtable groups, and a lambda inside
`Level::tick()` comes out as
`std::__ndk1::__function::__func<Level::tick()::$_0, …>::operator()` with
`nameSource: "lambda"` instead of `vfunc_0`.

## Deltas that this branch cannot claim

The named-function delta on 26.60 is **unknown until the owner re-exports**.
The engine now produces names from three new sources, but the count depends on
how many lambda vtables and ECS adapters the build actually contains, and that
is a property of the library, not of the code. Quote the number from
`coverage_report.py` on the next export instead of estimating it here.

## Goals and where they stand

| Goal | Target | Status |
|---|---|---|
| Real names, gameplay classes | > 0, honest figure | code path exists; figure unmeasured on 26.60 |
| Unique signature per named function | ≥ 98 % | **84.1 %** on the synthetic fixture. The missing 15.9 % are thunk families whose members are byte-identical (400 per family), so no byte pattern can separate them; each gets a group plus an anchor instead. Real-library figure unmeasured here |
| Every vtable group its own identity | 100 % | done in the engine: groups are keyed by mangled name |
| Component registry | every ECS component | parsing of `Include<…>`/`Exclude<…>` done; storage accessors not recovered |
| Export size | ≤ 5 MB gameplay pack | gameplay pack not done; the whole 800k-function archive is **20.3 MB zipped** on device (was 663 MB for 26.60) |
| Scan completes on a 2 GB phone | no OOM | **done**: 800,000 functions finished, 878 MB resident at the peak |
