# CaramelClient access chain on 26.60: what is found and what is not

Companion to `CARAMEL_26.60_SURFACE.md`. That document says what the library
exposes; this one says which of the offsets CaramelClient needs were resolved.

| | |
| --- | --- |
| pack | `caramel/offsets_26.60.json` |
| schema | `caramel/schema.json` |
| library sha256 | `b6ffe00817117b03a518b4c9254dcbbb821ad53f276d179c67021226ca221a80` |
| result | **13 of 35 items found** (6 access-chain classes, 7 ECS classes), 22 missing |

```console
$ python3 scripts/caramel_pack.py --library libminecraftpe.so \
    --output-dir out-shards6/categories-measure --version 26.60 \
    --engine-version 1.0.12 --out caramel/offsets_26.60.json
caramel/offsets_26.60.json: 35 items, 13 found, 0 partial, 22 missing
$ python3 scripts/validate_caramel_pack.py caramel/offsets_26.60.json \
    --library libminecraftpe.so
caramel/offsets_26.60.json: ok, 35 items (13 found, 0 partial, 22 missing)
```

`rva` is the virtual address from the file. Because the library's first
`PT_LOAD` has `p_vaddr == p_offset == 0`, an RVA is also the load-time offset
from the module base. `file_offset` is the byte offset in the file, computed
from the section table: for `.data.rel.ro` it is `rva - 0x4000`.

## 1. Access chain

`MinecraftGame → ClientInstance → LocalPlayer | Level → Dimension | BlockSource`

| item | kind | status | rva | file offset | how found | confidence |
| --- | --- | --- | --- | --- | --- | --- |
| `MinecraftGame` | class vtable | **found** | `0x1389f8a8` | `0x1389b8a8` | RTTI type_info → vtable via `.rela.dyn`, 316 slots | certain |
| `ClientInstance` | class vtable | **found** | `0x1388fe60` | `0x1388be60` | RTTI, 436 slots | certain |
| `LocalPlayer` | class vtable | **found** | `0x139f6e90` | `0x139f2e90` | RTTI, base `Player`, 247 slots | certain |
| `Level` | class vtable | **found** | `0x13e0a0e8` | `0x13e060e8` | RTTI, base `BlockSourceListener`, 413 slots | certain |
| `Dimension` | class vtable | **found** | `0x13e0cec8` | `0x13e08ec8` | RTTI, bases `IDimension`, `LevelListener`, `SavedData`, `Bedrock::EnableNonOwnerReferences` | certain |
| `BlockSource` | class vtable | **found** | `0x13d6dcc0` | `0x13d69cc0` | RTTI, no bases, 67 slots | certain |
| `MinecraftGame::getClientInstance` | function | **missing** | — | — | — | none |
| `ClientInstance::getLocalPlayer` | function | **missing** | — | — | — | none |
| `ClientInstance::getLevel` | function | **missing** | — | — | — | none |
| `ClientInstance::getMinecraftGame` | function | **missing** | — | — | — | none |
| `Level::getDimension` | function | **missing** | — | — | — | none |
| `Dimension::getBlockSource` | function | **missing** | — | — | — | none |
| `Level::getBlockSource` | function | **missing** | — | — | — | none |

Every class in the chain is resolved; not one hop *function* is. That is the
honest state, and it is the same for every build whose `.symtab` is stripped.

### Why each function is missing

The pack records the attempts. For `ClientInstance::getLocalPlayer`:

```json
"tried": [
  "symbols: 75720 functions carry a demangled name and none of ['ClientInstance::getLocalPlayer', 'ClientInstance::getLocalPlayerPtr'] match",
  "vtable slots: ClientInstance has 436 slots and every one is named ClientInstance::vfunc_N, so the slot number is known but its meaning is not",
  "string anchor: 'getLocalPlayer' occurs 0 times in the library",
  "string anchor: 'getLocalPlayerPtr' occurs 0 times in the library"
]
```

Three independent routes, three empty results:

1. **Symbols.** 75,720 functions have a demangled name and none of them is a
   gameplay method. See `CARAMEL_26.60_SURFACE.md` section 1.
2. **Vtable slot names.** `ClientInstance` has 436 slots. Every one is named
   positionally (`ClientInstance::vfunc_0` … `vfunc_435`). The *slot index* is
   known and certain; which index is `getLocalPlayer` is not recorded anywhere in
   the file.
3. **String anchors.** The method name does not occur in the library at all, so
   there is no assertion string to anchor to. `forEachPlayer` occurs twice, both
   inside the mangled name of the scripting helper
   `ResourceUriCommandAnon::_forEachPlayerSendPacket`, which is not `Level`'s.

## 2. Entity and player list

| item | status | why it matters |
| --- | --- | --- |
| `Level::getRuntimeActorList` | **missing** | ESP, voice emitters, replay |
| `Level::forEachPlayer` | **missing** | mic icons, name plates |
| `Level::forEachActor` | **missing** | entity iteration |
| `Level::getPlayerList` / `getUsers` | **missing** | names, XUIDs |
| `Level::fetchEntity` / `getRuntimeEntity` | **missing** | hit prediction |
| `Level::getActorByUniqueID` | **missing** | replay, kill effects |
| `Dimension::forEachPlayer` | **missing** | cross-dimension audio |
| `BlockSource::fetchEntities` / `getEntities` | **missing** | AABB queries |
| `EntityRegistry` | **missing** | ECS registry |
| `EntityContext` / `StrictEntityContext` | **missing** | ECS handle |
| `ActorOwnerComponent` | **missing** | component → `Actor*` |
| `StateVectorComponent` | **missing** | ECS position/velocity |
| `AABBShapeComponent` | **missing** | ECS hitbox |
| `ActorTypeComponent` | **missing** | ECS type id |
| `EntityRegistry::each` / `view` | **missing** | registry iteration |

None of the six component/registry classes the brief names exist in this build
(`CARAMEL_26.60_SURFACE.md` section 2), so their absence is expected. What the
build *does* expose is the EnTT plumbing, which is in the pack under `ecs`.

## 3. ECS: what is found

| item | status | rva | file offset | groups |
| --- | --- | --- | --- | ---: |
| `ITickingSystem` | **found** | `0x13f3a5b0` | `0x13f365b0` | 1 |
| `StrictTickingSystemFunctionAdapter` | **found** | `0x13791dc0` | `0x1378ddc0` | 643 |
| `EntityComponentDefinition` | **found** | `0x13ccb5b8` | `0x13cc75b8` | 164 |
| `EntityComponentFactoryJson` | **found** | `0x13d06238` | `0x13d02238` | 1 |
| `BlockComponentStorage::ComponentInstance` | **found** | `0x13854f60` | `0x13850f60` | 54 |
| `entt::basic_storage` | **found** | `0x1376b818` | `0x13767818` | 1,406 |
| `entt::basic_sparse_set` | **found** | `0x1376f608` | `0x1376b608` | 4 |

These are addresses a mod can hook today: `ITickingSystem::vfunc_N` is the
dispatch point of every ECS system, and each of the 643
`StrictTickingSystemFunctionAdapter` instantiations is one system.

## 4. What a human has to do to close the gaps

The engine cannot name a vtable slot without a disassembler. Until that exists,
each hop has to be identified once, by hand, and then stored as a **vtable index
+ class**, which is stable across builds:

1. Open `libminecraftpe.so` in IDA/Ghidra at the class vtable
   (`Level` → `0x13e0a0e8`).
2. Walk the slots in order. The slot index is what you record, not the address.
3. Find `Level::getRuntimeActorList` by its shape: a function that returns a
   `std::vector<Actor*>&` reads three pointers from `this + 0x???` and returns
   `this + offset`; `forEachPlayer` takes a `std::function` and loops.
4. Cross-check with the demangled type of the *callers*: the scripting bindings
   (`ScriptModuleMinecraft`) do call into these methods and their mangled names
   are in `.dynsym`, so a breakpoint or a `BL` backtrace from a script binding
   lands on the real method.
5. Record the result as `{"class": "Level", "slot": N}` in a follow-up table.
   The pack schema already has `kind: "vtable_slot"` for it.

Step 4 is the one that works without a device: `ScriptBlock::getDimension`,
`ScriptEnchantInfo::getLevelRange` and friends are exported, and the functions
they call are the gameplay methods.

## 5. CaramelClient module status

| module | needs | status | blocked by |
| --- | --- | --- | --- |
| Proximity Voice Chat | `LocalPlayer` position, player list, names | **partial** | classes found, `getLocalPlayer`/`forEachPlayer` missing |
| PvP HUD (reach, trajectory) | `Player` position, `HitResult`, reach constants | **partial** | classes found, methods missing |
| Replay | tick hook, world snapshot | **partial** | `Level` found, `Level::tick` missing |
| `.universe` mods | render hooks, vtable slots | **partial** | vtables found, slot meanings unknown |
| `.singularity` worldgen | `BlockSource::getBlock`/`setBlock`, chunk sources | **partial** | `BlockSource` found, methods missing |
| BetterRenderDragon | `ScreenContext`, `MinecraftUIRenderContext` | **not started** | not in this step's scope |

Nothing in this table is guesswork: every "found" row is a resolved RTTI vtable
with a file offset that cross-checks against the library's relocations, and every
"missing" row names the three routes that were tried.

## What I could not verify

* No device was used. The pack is derived from the library file only, which is
  the correct source for offsets; but a hook that installs at `Level`'s vtable
  slot 0 has not been executed.
* Which slot index corresponds to which method cannot be derived from this build
  with the engine as it stands. Section 4 is the manual procedure, not a result.
