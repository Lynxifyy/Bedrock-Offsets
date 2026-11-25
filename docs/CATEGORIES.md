# Category rules

The internal C++ table is split into one file per category. The split is data,
not code: `app/src/main/assets/categories.json` holds an ordered list of rules,
and Settings > Download can import a replacement or reset to the bundled file.

## How a record is classified

Every class and every function is assigned to exactly one category. Rules are
evaluated in file order and the first match wins:

1. **By class.** The rule's `classes` regexes are matched against the demangled
   class name. With `includeBases` set, a base class also satisfies the rule,
   which is how `Player` lands in `actor_entity` through `Mob` and `Actor`. All
   of a class's vtable slots inherit the class category.
2. **By function name.** The rule's `functions` regexes are searched in a
   symbol-named function, for example `*mayPlace*`.
3. **By namespace.** The rule's `namespaces` list is matched against the
   namespace before `::`, so `std::__ndk1::...` goes to `thirdparty`.
4. **Fallback.** A name that matched nothing goes to `other_named`; a `sub_*`
   placeholder goes to `unnamed`.

A class is written to exactly one category file. When a base class lives in a
different category, the derived class still lists it by name in `bases`; the
index maps every class to its category.

## Rule format

```json
{
  "version": 1,
  "categories": [
    {
      "category": "block_placement",
      "label": "Block placement",
      "classes": ["^(Block|BlockLegacy|BlockSource|BlockItem|GameMode)$"],
      "functions": ["useItemOn", "mayPlace", "placeBlock"],
      "namespaces": [],
      "includeBases": true
    }
  ]
}
```

* `category` is used in the file name: `INTERNALCPP_<CATEGORY>_<version>.json`.
  A bare array of rules is also accepted, and `id` works as an alias.
* Every category rule also needs a `label` for the generated file; it defaults to
  the `category` value.
* `label` is the human-readable name written into the file.
* `classes`, `functions` and `namespaces` may be empty.
* `includeBases` defaults to `true`.
* The order of the array is the priority order.
* A malformed regex is skipped rather than failing the export.

## Starter categories

| id | Covers |
|---|---|
| `block_placement` | `Block`, `BlockLegacy`, `BlockSource`, `BlockActor`, `BlockItem`, `GameMode`; `*useItemOn*`, `*placeBlock*`, `*mayPlace*`, `*destroyBlock*` |
| `actor_entity` | `Actor`, `Mob`, `Player`, `LocalPlayer`, `ServerPlayer`, `ActorDefinition*`, `EntityContext*` |
| `movement` | `MoveInputHandler`, `*PlayerMovement*`, `*Velocity*`, `*setPos*`, `*Jump*`, `*Sprint*`, `*Sneak*`, `*Fly*` |
| `combat` | `*attack*`, `*hurt*`, `*Damage*`, `*Knockback*`, `*Health*` |
| `inventory_items` | `Item*`, `ItemStack*`, `Inventory*`, `Container*`, `PlayerInventory` |
| `world_level` | `Level`, `Dimension`, `ChunkSource`, `LevelChunk`, `ClientLevel`, `Biome*`, `Weather*` |
| `rendering` | `LevelRenderer`, `GameRenderer`, `ScreenContext`, `MinecraftUIRenderContext`, `mce::*`, `dragon::*`, `bgfx::*`, `*Renderer*`, `*Tessellator*` |
| `ui_screens` | `Screen*`, `*ScreenController*`, `UIControl*`, `Scene*`, `SceneStack*`, `Hud*`, `*Layout*` |
| `input` | `ClientInputHandler`, `*InputHandler*`, `*Mouse*`, `*Keyboard*`, `*Gamepad*`, `*Touch*` |
| `network_packets` | `*Packet`, `PacketSender`, `NetworkHandler`, `ClientNetworkHandler`, `RakNet::*`, `*Connection*` |
| `client_game` | `ClientInstance`, `MinecraftGame`, `Minecraft`, `IClientInstance`, `MinecraftScreenModel`, `Options*`, `AppPlatform*` |
| `audio` | `SoundEngine*`, `*Audio*`, `*Sound*` |
| `commands` | `Command*`, `*CommandRegistry*` |
| `thirdparty` | namespaces `webrtc`, `absl`, `icu_*`, `leveldb`, `std`, `__cxxabiv1`, `google`, `cohtml`, `bx`, `bgfx`, `pplx`, `entt`, `moodycamel`, `Json`, `nlohmann`, `cpprestsdk`, `sigslot`, `rtc`, `cricket`, `zstd`, `curl`, `openssl` |
| `other_named` | a real name that matched nothing |
| `unnamed` | `sub_*` only, split into parts of at most 100,000 entries |

`unnamed` is chunked as `INTERNALCPP_UNNAMED_PART_01_<version>.json`,
`PART_02`, and so on. The index lists every part.

## Changing the rules

1. Open Settings > Download > Category rules.
2. Tap **Import categories.json** and pick your file. It is copied into the
   app's private storage and used for the next export.
3. Tap **Reset to default** to delete the override and return to the bundled
   file.

The exporter checks that the sum of category function counts equals the total
function count. A mismatch fails the download, not the scan, so the scan result
is never thrown away because a rule file was wrong.
