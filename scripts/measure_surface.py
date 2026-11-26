#!/usr/bin/env python3
"""Measures what a Bedrock library exposes, from the library and from a scan.

Section 12 of the audit asks one question before any derivation is designed:
does this build ship real C++ names, or only `sub_xxxx`? The answer decides
whether a target is found by demangled name, by RTTI vtable slot, by string
anchor or not at all, and it differs per Minecraft version.

Two inputs, neither of them the app:

* the ELF dynamic symbol table, parsed here rather than with `readelf`, so the
  script runs on a machine without the Android or LLVM toolchain;
* the internal table the engine wrote, streamed one record at a time so a
  700 MB table never has to fit in memory.

A *named* function is one the engine gave a name from a symbol or from the
vtable group it belongs to; an *unnamed* one is `sub_<hex>`. A vtable-derived
name such as `Player::vfunc_12` identifies the class and the slot, not the
method, so the two are counted apart: a modder can hook by slot, cannot read
the method name from it.

Usage:

    python3 scripts/measure_surface.py --library libminecraftpe.so \\
        --table /workspace/artifacts/out-current/.ndjson \\
        --json /workspace/artifacts/analysis/surface-26.60.json
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from collections import Counter

#: Classes a Bedrock client has to reach, grouped as the module pack asks for
#: them (section 13 of the audit).
TARGETS = {
    "access_chain": [
        "MinecraftGame", "ClientInstance", "LocalPlayer", "Level", "Dimension",
        "BlockSource", "GuiData", "Options", "SoundEngine", "PacketSender",
        "LoopbackPacketSender",
    ],
    "entity_list": [
        "EntityRegistry", "EntityContext", "ActorOwnerComponent", "Actor",
        "Player", "Mob", "ActorDefinitionIdentifier", "WeakEntityRef",
    ],
    "actor_fields": [
        "StateVectorComponent", "AABBShapeComponent", "ActorTypeComponent",
        "AttributeInstance", "HitResult", "ActorFlags",
    ],
    "combat": [
        "GameMode", "ItemStack", "Item", "ProjectileComponent", "Arrow",
        "Trident", "ItemUseOnEvent",
    ],
    "render": [
        "LevelRenderer", "LevelRendererPlayer", "ScreenContext",
        "MinecraftUIRenderContext", "ScreenView", "UIControl", "Tessellator",
        "BaseActorRenderer", "Font", "Matrix", "Camera",
    ],
    "worldgen": [
        "ChunkSource", "WorldGenerator", "OverworldGenerator", "ChunkViewSource",
        "LevelChunk", "Biome", "BiomeSource", "BlockPalette", "BlockLegacy",
        "FlowingLiquid", "LiquidBlock",
    ],
}

#: Component and registry types whose presence means this build keeps actors in
#: an ECS rather than in the legacy `Actor` field layout.
ECS_MARKERS = [
    "EntityRegistry", "EntityContext", "ActorOwnerComponent",
    "StateVectorComponent", "AABBShapeComponent", "ActorTypeComponent",
    "ECS", "WeakEntityRef",
]


def read_elf_symbols(path: str) -> dict:
    """Counts `.dynsym` and `.symtab` entries without external tools."""
    with open(path, "rb") as handle:
        header = handle.read(64)
        if header[:4] != b"\x7fELF":
            raise SystemExit(f"{path} is not an ELF file")
        if header[4] != 2 or header[5] != 1:
            raise SystemExit(f"{path} is not a 64-bit little-endian ELF")
        (shoff,) = struct.unpack_from("<Q", header, 0x28)
        shentsize, shnum = struct.unpack_from("<HH", header, 0x3A)
        handle.seek(shoff)
        sections = []
        for _ in range(shnum):
            raw = handle.read(shentsize)
            if len(raw) < shentsize:
                break
            fields = struct.unpack_from("<IIQQQQIIQQ", raw, 0)
            sections.append(
                {
                    "type": fields[1],
                    "offset": fields[4],
                    "size": fields[5],
                    "entsize": fields[9],
                }
            )

        result = {
            "dynsym": 0,
            "dynsym_functions": 0,
            "dynsym_objects": 0,
            "symtab": 0,
            "symtab_present": False,
        }
        for section in sections:
            if section["type"] not in (2, 11):  # SHT_SYMTAB, SHT_DYNSYM
                continue
            entsize = section["entsize"] or 24
            count = section["size"] // entsize
            handle.seek(section["offset"])
            functions = objects = 0
            for _ in range(count):
                raw = handle.read(entsize)
                if len(raw) < 24:
                    break
                info = raw[4]
                shndx = struct.unpack_from("<H", raw, 6)[0]
                if shndx == 0:
                    continue
                if info & 0xF == 2:
                    functions += 1
                elif info & 0xF == 1:
                    objects += 1
            if section["type"] == 11:
                result["dynsym"] = count
                result["dynsym_functions"] = functions
                result["dynsym_objects"] = objects
            else:
                result["symtab"] = count
                result["symtab_present"] = count > 0
        return result


def measure(library: str, table: str) -> dict:
    """Streams the table and counts what each target actually has."""
    elf = read_elf_symbols(library)

    functions = Counter()
    name_sources = Counter()
    kinds = Counter()
    statuses = Counter()
    classes: set[str] = set()
    class_slots = Counter()
    slot_names = Counter()
    owners = Counter()
    slot_owners = Counter()
    named_owners = Counter()
    examples: dict[str, list] = {}
    data_symbols = Counter()

    with open(table, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line.startswith('{"type"'):
                continue
            record = json.loads(line)
            kind = record["type"]
            if kind == "class":
                entry = record["class"]
                classes.add(entry["name"])
                # `slots` repeats the primary group; `vtableGroups` is the
                # whole truth, secondary vtables included.
                groups = entry.get("vtableGroups") or []
                class_slots["groups"] += len(groups)
                class_slots["primary"] += len(entry.get("slots") or [])
                for group in groups:
                    for slot in group.get("slots") or []:
                        class_slots["all"] += 1
                        slot_names["named" if slot.get("name") else "unnamed"] += 1
                continue
            if kind == "symbol":
                data_symbols[record["symbol"].get("kind", "unknown")] += 1
                continue
            if kind != "function":
                continue
            entry = record["function"]
            functions["total"] += 1
            name_sources[entry.get("nameSource", "unknown")] += 1
            kinds[entry.get("kind", "unknown")] += 1
            statuses[entry.get("status", "unknown")] += 1
            name = entry.get("name", "")
            if name.startswith("sub_"):
                functions["auto"] += 1
                continue
            functions["named"] += 1
            placeholder = "::vfunc_" in name
            owner = name.split("::")[0]
            owners[owner] += 1
            if placeholder:
                functions["vfunc_placeholder"] += 1
                slot_owners[owner] += 1
            if not placeholder:
                named_owners[owner] += 1
                bucket = examples.setdefault(owner, [])
                if len(bucket) < 6:
                    bucket.append(name)

    targets = {}
    for group, wanted in TARGETS.items():
        entries = {}
        for cls in wanted:
            entries[cls] = {
                "rtti_class": cls in classes,
                "functions_with_a_real_name": named_owners.get(cls, 0),
                "functions_named_after_a_slot": slot_owners.get(cls, 0),
                "examples": examples.get(cls, []),
            }
        targets[group] = entries

    return {
        "elf": elf,
        "table": {
            "functions": functions["total"],
            "functions_with_a_real_name": functions["named"] - functions["vfunc_placeholder"],
            "functions_named_after_a_vtable_slot": functions["vfunc_placeholder"],
            "functions_named_sub": functions["auto"],
            "name_sources": dict(name_sources),
            "kinds": dict(kinds),
            "statuses": dict(statuses),
            "classes": len(classes),
            "vtable_slots": class_slots["all"],
            "vtable_slots_in_primary_group": class_slots["primary"],
            "vtable_groups": class_slots["groups"],
            "slot_names": dict(slot_names),
            "data_symbols": dict(data_symbols),
        },
        "top_named_owners": named_owners.most_common(20),
        "targets": targets,
        "ecs_classes": {marker: sum(1 for cls in classes if marker in cls) for marker in ECS_MARKERS},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", required=True, help="libminecraftpe.so to measure")
    parser.add_argument("--table", required=True, help="NDJSON table the engine wrote")
    parser.add_argument("--json", help="write the full measurement here")
    args = parser.parse_args()

    result = measure(args.library, args.table)
    elf = result["elf"]
    table = result["table"]

    print(f"dynsym            {elf['dynsym']:,} entries "
          f"({elf['dynsym_functions']:,} functions, {elf['dynsym_objects']:,} objects)")
    if elf["symtab_present"]:
        print(f"symtab            present, {elf['symtab']:,} entries")
    else:
        print("symtab            stripped")
    print(f"functions         {table['functions']:,}")
    print(f"  from a symbol   {table['name_sources'].get('symbol', 0):,}")
    print(f"  from JNI        {table['name_sources'].get('jni', 0):,}")
    print(f"  from a vtable   {table['name_sources'].get('vtable', 0):,}")
    print(f"  unnamed (sub_)  {table['functions_named_sub']:,}")
    print(f"  real method name{table['functions_with_a_real_name']:>18,}")
    print(f"  class+slot name {table['functions_named_after_a_vtable_slot']:,}")
    print(f"classes           {table['classes']:,}")
    print(f"vtable slots      {table['vtable_slots']:,} in {table['vtable_groups']:,} groups "
          f"(primary {table['vtable_slots_in_primary_group']:,}, "
          f"named {table['slot_names'].get('named', 0):,})")
    print("data symbols      " + ", ".join(f"{k} {v:,}" for k, v in sorted(table["data_symbols"].items())))
    print("ECS classes       " + (", ".join(f"{k} {v:,}" for k, v in result["ecs_classes"].items() if v) or "none"))
    print("top owners        " + ", ".join(f"{n} {c:,}" for n, c in result["top_named_owners"][:8]))
    for group, entries in result["targets"].items():
        rtti = [c for c, e in entries.items() if e["rtti_class"]]
        named = [c for c, e in entries.items() if e["functions_with_a_real_name"]]
        print(f"{group:14} rtti {len(rtti):>2}/{len(entries):<2} real method names {len(named):>2}/{len(entries)}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(result, handle, indent=2, sort_keys=True)
            handle.write("\n")
        print(f"written           {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
