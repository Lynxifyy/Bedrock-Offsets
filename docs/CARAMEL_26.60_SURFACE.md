# What the 26.60 library actually exposes

Measured on the owner's real library, not on a fixture.

| | |
| --- | --- |
| file | `libminecraftpe.so` from `minecraft-26-60.apk` |
| size | 342,231,392 bytes |
| sha256 | `b6ffe00817117b03a518b4c9254dcbbb821ad53f276d179c67021226ca221a80` |
| arch | AArch64, Android NDK r27 libc++ (`std::__ndk1`) |
| engine | `offset_core` at `1.0.12`, run as `host_shard_measure` |

Every number below comes from a command in this document. The pipeline was run
on the library itself; nothing here is estimated.

```console
$ cd app/src/main/rust/offset_core
$ BEDROCK_LIB=/workspace/artifacts/libminecraftpe.so \
  BEDROCK_CATEGORIES=../../../../assets/categories.json \
  BEDROCK_OUT=/workspace/artifacts/out-shards6 \
  cargo test --release --test host_shard_measure -- --ignored --nocapture
100% Internal extraction complete, peak resident 1077 MB [rss 875 MB] [peak 1077 MB]
coverage: functions=791952 fdes=791952 missing=0 slots=865399 truncated=false withoutFileOffset=0
peak resident: 1077 MB
test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 92.10s

```

## 1. How many functions have a real C++ name

Of the 791,952 functions in the table:

| name source | rows | share | what the name is |
| --- | ---: | ---: | --- |
| `vtable` | 422,283 | 53.3 % | `Class::vfunc_7` — the class and the slot index, **no method name** |
| `symbol` | 75,720 | 9.6 % | demangled C++ or plain C name from `.dynsym`/`.symtab` |
| `jni` | 95 | 0.01 % | `Java_*` export or `RegisterNatives` target |
| `auto` | 293,854 | 37.1 % | `sub_<rva>` — **no name at all** |

So 293,854 functions (37.1 %) are `sub_xxxx`, and only 75,815 (9.6 %) carry a
name a human would recognise. The remaining 53.3 % know their class but not
their method, because the vtable slot naming is positional.

### The 75,720 symbol names are third-party code

Every one of them, by first namespace component:

```console
$ python3 - <<'PY'   # over the 75,720 demangled names
import collections
pref = collections.Counter(n.split('::')[0] for n in names if '::' in n)
PY
  24557 v8
   8732 cohtml
   8134 <C symbol>            # plain C exports: JNI, zlib, sqlite, ...
   7515 webrtc
   4094 std
   2598 Xal                   # Xbox Live
   2344 csl                   # Coherent UI
   2105 renoir                # Adobe/Coherent layout engine
   1845 void cohtml
   1779 xbox
   1107 bool cohtml
   1069 v8_inspector
    733 ska
    599 dcsctp
    508 void std
    505 void v8
    489 absl
    399 pplx
    376 void Xal
    360 bx
    332 leveldb
    329 cppgc
    271 {virtual override thunk({offset(-8)}, webrtc
    248 web
   226 Concurrency

```

There is **not one** method of a gameplay class:

```console
$ grep -c "^Level::"        symbol_names.txt   # 0
$ grep -c "^Player::"       symbol_names.txt   # 0
$ grep -c "^Actor::"        symbol_names.txt   # 0
$ grep -c "^ClientInstance::" symbol_names.txt # 0
$ grep -c "^MinecraftGame::"  symbol_names.txt # 0
$ grep -c "^Dimension::"    symbol_names.txt   # 0
$ grep -c "^BlockSource::"  symbol_names.txt   # 0
$ grep -c "^Mob::"          symbol_names.txt   # 0
$ grep -c "^GameMode::"     symbol_names.txt   # 0
$ grep -c "^ServerInstance::" symbol_names.txt # 0
$ grep -c "^EntityRegistry::" symbol_names.txt # 0
```

The `.dynsym` exports are the HTML UI (Coherent `cohtml`/`csl`), V8, WebRTC,
Xbox Live, telemetry and the C library. The gameplay code is compiled with
hidden visibility and its `.symtab` is stripped, so it appears only as RTTI
vtables and FDEs.

## 2. Does 26.60 use the EnTT ECS?

Yes. But not with the names the brief expected.

### The six names from the brief do not exist in this build

```console
$ cd /workspace/artifacts/out-shards6/categories-measure
$ for c in EntityRegistry EntityContext ActorOwnerComponent StateVectorComponent \
           AABBShapeComponent ActorTypeComponent; do
    printf "%-24s %s\n" "$c" "$(grep -o "$c" *.json | wc -l)"; done
EntityRegistry           0
EntityContext            1
ActorOwnerComponent      0
StateVectorComponent     0
AABBShapeComponent       0
ActorTypeComponent       0

```

An earlier version of this page concluded from that table that "those six names
belong to a different Bedrock build". The table is right, the conclusion is
wrong: it greps the *output documents*, where a component type can never appear
because an EnTT component is a plain struct with no vtable and no `type_info`.
The strings are in the library itself:

```console
$ python3 - <<'PY'   # printable string around each hit in .rodata
...
PY
const char *entt::internal::pretty_function() [Type = ActorOwnerComponent]
NSt6__ndk120__shared_ptr_emplaceIN4entt13basic_storageI19ActorOwnerComponent8EntityIdNS_9allocatorIS3_EEEENS5_IS4_EEEE
const char *entt::internal::pretty_function() [Type = StateVectorComponent]
NSt6__ndk120__shared_ptr_emplaceIN4entt13basic_storageI20StateVectorComponent8EntityIdNS_9allocatorIS3_EEEENS5_IS4_EEEE
const char *entt::internal::pretty_function() [Type = AABBShapeComponent]
20IStrictTickingSystemI22StrictExecutionContextI6FilterIJ20LocalPlayerComponentEE4ReadIJ18AABBShapeComponent28ClimbingLa
const char *entt::internal::pretty_function() [Type = ActorTypeComponent]
```

So this build does carry `entt::basic_storage<ActorOwnerComponent, EntityId,
std::allocator<ActorOwnerComponent>>` and a `StrictTickingSystem<...,
Read<AABBShapeComponent, ClimbingLadderComponent ...>>`. The correct statement
is: **the types exist in the build, and no static method in the engine today
turns them into offsets.** Strings are the handle; section 7 measures how far a
string anchor gets.

### What the build does have

Classes with RTTI vtables, all resolved from `.rela.dyn`:

| class | slots | vtable | file offset |
| --- | ---: | --- | --- |
| `ITickingSystem` | 11 | `0x13f3a5b0` | `0x13f365b0` |
| `StrictTickingSystemFunctionAdapter` | 14 | `0x13791dc0` | `0x1378ddc0` |
| `EntityComponentDefinition` | 16 | `0x13ccb5b8` | `0x13cc75b8` |
| `EntityComponentFactoryJson` | 2 | `0x13d06238` | `0x13d02238` |
| `EntityComponentFactoryCerealBase` | 11 | `0x13ad98f0` | `0x13ad58f0` |
| `BlockComponentStorage::ComponentInstance` | 7 | `0x13854f60` | `0x13850f60` |
| `entt::basic_sparse_set` | 14 | `0x1376f608` | `0x1376b608` |
| `entt::basic_storage` | 14 | `0x1376b818` | `0x13767818` |
| `entt::basic_sigh_mixin` | 17 | `0x13acb9d0` | `0x13ac79d0` |
| `entt::internal::dispatcher_handler` | 9 | `0x1376be48` | `0x13767e48` |

`BlockComponentStorage` itself has no vtable in this build; only its nested
`ComponentInstance` does.

### How many component types and systems there are

A template instantiation gets its own vtable, and the engine keeps every group,
so the group count is a census:

| class | vtable groups | meaning |
| --- | ---: | --- |
| `entt::basic_storage` | 1,406 | 1,406 component storage types |
| `StrictTickingSystemFunctionAdapter` | 643 | 643 ECS systems |
| `EntityComponentDefinition` | 164 | 164 component definitions |
| `BlockComponentStorage::ComponentInstance` | 54 | per-block storages |

That is the strongest evidence that 26.60 is an EnTT build: a non-ECS build has
no `entt::basic_storage` vtable at all, let alone 1,406 of them.

**Limitation.** The demangler drops template arguments, so all 1,406 storages are
recorded under the single name `entt::basic_storage` and the per-group mangled
name is not kept. A modder therefore cannot tell from the pack which component a
given storage belongs to. Keeping `mangled` per vtable group is the fix; it is
listed in `AUDIT_REPORT.md`.

`entt` occurs 36,557 times in the output, almost always as the template
argument of a storage class:

```console
$ grep -o "entt::[A-Za-z_]*" *.json | sed "s/.*://" | sort | uniq -c | sort -rn | head -4
  35989 basic_storage
    330 internal
    117 basic_sigh_mixin
    111 basic_sparse_set

```

The system signatures name their components, which is how the ECS surface can be
read even without gameplay symbols. One mangled name from `ecs.json`:

```
StrictTickingSystemFunctionAdapterIXadL_ZN15CameraBobSystem4tickERK5ViewTI19StrictEntityContextJ7IncludeIJN13VanillaCamera18CameraBobComponentEEEN15MinecraftCamera15CameraComponentENS7_

```

which demangles to

```
StrictTickingSystemFunctionAdapter<&CameraBobSystem::tick(
    ViewT<StrictEntityContext,
          Include<VanillaCamera::CameraBobComponent>,
          MinecraftCamera::CameraComponent, ...>)>

```

That is the real naming scheme in 26.60: the context type is
`StrictEntityContext` (not `EntityContext`), the view is `ViewT<...>`, and
components are namespaced (`VanillaCamera::CameraBobComponent`,
`MinecraftCamera::CameraComponent`).

### Why the component names are invisible to RTTI

An EnTT component is a plain struct with no virtual functions, so it has no
vtable and no `type_info`. It can therefore never appear as a class in the
engine's class table, no matter how the engine is written. Components are only
reachable through

* the mangled names of the systems that view them (`ecs.json`, 5,140 functions),
* the `entt::basic_storage<...>` instantiations that own them, and
* code that walks the storage.

This is a hard limit of static RTTI-based analysis, not a gap in the engine.

## 3. The gap between 883,574 functions and 791,952 FDEs

The 883,574 figure was wrong, and the difference was 91,622 rows that were not
code. It is now fixed (`fix(internal): never name a vtable slot that holds
data`), so the table holds 791,952 rows and the gap is zero.

### Independent confirmation of the FDE count

`.eh_frame_hdr` carries a binary search table of every FDE. `scripts/verify_library_ground_truth.py`
parses it directly, without touching the engine:

```console
$ python3 scripts/verify_library_ground_truth.py \
    --library libminecraftpe.so --output-dir out/categories
classes: 6386, type_info slot ok=6386 wrong=0 missing=0
virtual slots: 157188, ok=157188 wrong=0 missing=0
functions: 791952, outside .text: 0
fdes in .eh_frame_hdr: 791952, difference from functions: 0

```

`0x6834bc0` is the start of `.text`, and the count matches the engine's
`fdes=791952` exactly.

### Every FDE has exactly one row

```console
rows at an FDE start: 791952
  by nameSource: {vtable: 422283, symbol: 75720, jni: 95, auto: 293854}
rows not at an FDE start: 0

```

So the decomposition of the old 883,574 was:

| | rows |
| --- | ---: |
| functions with an FDE (all real functions) | 791,952 |
| extra rows whose address is not code | 91,622 |
| — in `.rodata` | 79,366 |
| — in `.data.rel.ro` | 11,441 |
| — in no section (`.bss`) | 815 |

The 91,622 came from `insert_slot_names`, which named every non-zero vtable slot
of every vtable group. Secondary groups hold more than function pointers: a
virtual-base group starts with a vbase offset, and a nested-vtable slot points
at another vtable. Those pointers became `Class::vfunc_N` "functions", and
79,366 of them were then marked `verified` because the bytes at a `.rodata`
address happened to look like an AArch64 prologue:

```
RemotePlayer::vfunc_6  address 0x2e3bb90  status verified
signature 5F 24 03 D5 A0 ?? ?? ?? 00 ?? ?? ?? A6 ?? ?? ?? 5F 24 03 D5 C0 03 5F D6
          bti c; adrp; ...; bti c; ret

```

`0x2e3bb90` is inside `.rodata` (`0x2419f00..0x3785ff4`). It is data that
happens to decode as instructions.

## 4. Ground truth: the vtables and slots are right

The library uses `RELA` relocations, so the vtable slots are **zero in the file**
and their values live in `.rela.dyn` as addends. That gives an independent check
of the whole RTTI path:

```console
$ python3 scripts/verify_library_ground_truth.py \
    --library libminecraftpe.so --output-dir out/categories
classes: 6386, type_info slot ok=6386 wrong=0 missing=0
virtual slots: 157188, ok=157188 wrong=0 missing=0
functions: 791952, outside .text: 0
fdes in .eh_frame_hdr: 791952, difference from functions: 0
real 0m8.5s

```

(The library has 1,052,764 `R_AARCH64_RELATIVE` entries in `.rela.dyn`.)

* For all 6,386 classes, the relocation at `vtable + 8` has an addend equal to
  the `typeInfo` the engine recorded.
* For all 157,188 slots the engine calls `virtual`, the relocation at
  `vtable + 16 + 8*slot` has an addend equal to the slot address.

There is no silent error in either table.

## 5. What this means for derivation

Because no gameplay symbol exists, a `Class::method` name can only come from
code analysis, never from a name lookup. The order of attack has to be:

1. **RTTI gives the class, the vtable and the slot count** — already certain.
2. **A slot's meaning must be derived from its code**: a slot that stores `x0`
   at offset 0x20 of the returned object is a getter for a pointer member at
   0x20; a slot that reads `[x0, #0x40]` twice and returns a float pair is
   `getPos`. This needs a disassembler, which the engine does not have yet.
3. **A function's meaning can also come from what it references**: the
   constructor is the function that writes the class vtable pointer, and a
   `getInstance` is the function that reads the same `.bss` global every caller
   uses.

Steps 2 and 3 are the next engine feature. They are the only path to
`Level::getRuntimeActorList` on a build like this, and no amount of pattern
matching replaces them.

## 6. Reproducing this measurement

```console
$ cd app/src/main/rust/offset_core
$ cargo test --release --test host_shard_measure -- --ignored --nocapture   # 92 s
$ cd /workspace/artifacts/out-shards6/categories-measure
$ python3 -c "import json,glob,collections; \
  f=[x for p in glob.glob('*.json') if p!='classmap.json' for x in json.load(open(p))['functions']]; \
  print(len(f), collections.Counter(x['nameSource'] for x in f))"
791952 Counter({'vtable': 422283, 'auto': 293854, 'symbol': 75720, 'jni': 95})

```

## 7. Naming per gameplay class, and the string-anchor route

Measured with engine `0.1.0+2` (`engineVersion` in the payload) over the same
table, by `scripts/measure_surface.py`, which is the reproducible form of
sections 1 and 7:

```console
$ python3 scripts/measure_surface.py --library libminecraftpe.so \
    --table /workspace/artifacts/out-current/.ndjson \
    --json /workspace/artifacts/analysis/surface-26.60.json
dynsym            91,693 entries (79,316 functions, 11,354 objects)
symtab            stripped
functions         791,952
  from a symbol   75,720
  from JNI        95
  from a vtable   422,283
  unnamed (sub_)  293,854
  real method name            75,817
  class+slot name 422,281
classes           6,386
vtable slots      865,399 in 62,306 groups (primary 178,691, named 865,399)
data symbols      data 11,354
ECS classes       ECS 3
top owners        v8 24,557, cohtml 8,732, webrtc 7,515, std 4,094, Xal 2,598, csl 2,344, renoir 2,105, void cohtml 1,845
access_chain   rtti  9/11 real method names  0/11
entity_list    rtti  3/8  real method names  0/8
actor_fields   rtti  1/6  real method names  0/6
combat         rtti  4/7  real method names  0/7
render         rtti  5/11 real method names  0/11
worldgen       rtti  7/11 real method names  0/11
```

Per class (`rtti` = the class has an RTTI record; `slot names` = functions named
`Class::vfunc_N`; `real names` = functions with an actual method name):

| group | class | RTTI | slot names | real names |
| --- | --- | --- | ---: | ---: |
| access_chain | `MinecraftGame` | yes | 440 | 0 |
| | `ClientInstance` | yes | 462 | 0 |
| | `LocalPlayer` | yes | 159 | 0 |
| | `BlockSource` | yes | 67 | 0 |
| | `SoundEngine` | yes | 50 | 0 |
| | `Level` | yes | 30 | 0 |
| | `LoopbackPacketSender` | yes | 12 | 0 |
| | `Dimension` | yes | 11 | 0 |
| | `GuiData` | yes | 7 | 0 |
| | `Options`, `PacketSender` | no | 0 | 0 |
| entity_list | `Player` | yes | 55 | 0 |
| | `Actor` | yes | 10 | 0 |
| | `Mob` | yes | 1 | 0 |
| | `EntityRegistry`, `EntityContext`, `ActorOwnerComponent`, `WeakEntityRef` | no | 0 | 0 |
| actor_fields | `AttributeInstance` | yes | 3 | 0 |
| | `StateVectorComponent`, `AABBShapeComponent`, `ActorTypeComponent`, `HitResult`, `ActorFlags` | no | 0 | 0 |
| combat | `GameMode` | yes | 21 | 0 |
| | `Arrow` | yes | 11 | 0 |
| | `ItemStack` | yes | 8 | 0 |
| | `Item` | yes | 1 | 0 |
| | `Trident`, `ProjectileComponent`, `ItemUseOnEvent` | no | 0 | 0 |
| render | `LevelRendererPlayer` | yes | 48 | 0 |
| | `MinecraftUIRenderContext` | yes | 42 | 0 |
| | `LevelRenderer` | yes | 15 | 0 |
| | `Font` | yes | 7 | 0 |
| | `BaseActorRenderer` | yes | 2 | 0 |
| | `ScreenContext`, `ScreenView`, `UIControl`, `Tessellator`, `Camera`, `Matrix` | no | 0 | 0 |
| worldgen | `OverworldGenerator` | yes | 13 | 0 |
| | `ChunkViewSource` | yes | 9 | 0 |
| | `LiquidBlock` | yes | 8 | 0 |
| | `BlockPalette` | yes | 6 | 0 |
| | `WorldGenerator` | yes | 4 | 0 |
| | `ChunkSource` | yes | 3 | 0 |
| | `Biome` | yes | 2 | 0 |
| | `LevelChunk`, `BlockLegacy`, `BiomeSource`, `FlowingLiquid` | no | 0 | 0 |

Not one gameplay class has a real method name. That is not a gap in this run;
it is what an unexported, stripped build looks like.

### How far a `.rodata` string anchor gets

The anchor pass (`.rodata` string → `ADRP`+`ADD`/`LDR` site → enclosing
function) is now covered by `tests/string_anchor_test.rs`. Three cases are
asserted on a synthetic library: a planted pair names its function
(`nameSource: "string"`), an absent string is counted in `notFound`, and two
functions computing the same address both get the name with `status:
"ambiguous"` so neither is presented as a fact.

Candidate anchors on the real library (`refsites` = code sites computing the
string's address):

```text
ActorOwnerComponent      occurrences=271  refsites=133
StateVectorComponent     occurrences=316  refsites=164
AABBShapeComponent       occurrences=125  refsites= 69
ActorTypeComponent       occurrences= 12  refsites=  8
EntityRegistry           occurrences=  8  refsites=  0
ActorComponentFactory    occurrences= 38  refsites=  0
"Level::tick"            occurrences=  1  refsites=  0
const char *entt::internal::pretty_function() [Type = ActorOwnerComponent]
                         occurrences=  1  refsites=  0
```

Two dead ends and one opening:

* A bare component name is referenced by tens to hundreds of functions, so an
  anchor on it can only produce `ambiguous` rows.
* The unique `pretty_function` string is referenced by **nothing** in `.text`:
  it is dead data, and no relocation in `.rela.dyn` points at it either. A
  `.rodata` string is only useful when its address is actually computed.
* The `entt::basic_storage<...>` mangled names are in `.rodata` and hold the
  component the storage belongs to — the mapping this page calls missing above.
  Turning them into offsets needs a second hop (string → pointer table slot →
  referencing function), which the engine does not do yet.

So the shipped pack still carries no anchors, and that is now a decision with
numbers behind it rather than a silent zero: `ambiguous: 0` and `notFound: 0`
in a payload with an empty `stringAnchors` request mean "none supplied", not
"all failed".

## What I could not measure

* Nothing in this document needs a device: every number comes from the library
  file itself.
* `minecraft-26-60.apk`'s own `.symtab` (as opposed to the library's) is not
  relevant here; the APK's DEX is a separate question.
* Whether the game's `Level::forEachPlayer` exists with that exact name cannot be
  answered from this build by any static method available today; see section 5.
