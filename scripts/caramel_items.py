"""The offsets CaramelClient needs, grouped by module.

Each item is one thing the launcher has to resolve. ``spellings`` lists every
name the engine may use for it: a class name for ``class_vtable`` items, and
``Class::method`` for ``function`` items. The resolver in ``caramel_pack`` tries
every spelling and reports the item as ``missing`` when none of them resolve, so
a gap is visible instead of absent.

``why`` records which CaramelClient module needs the item, so the coverage table
in the UI can say what a missing offset blocks.
"""

from __future__ import annotations

ACCESS_CHAIN = [
    {
        "name": "MinecraftGame",
        "kind": "class_vtable",
        "spellings": ["MinecraftGame"],
        "why": "root of every access chain",
    },
    {
        "name": "ClientInstance",
        "kind": "class_vtable",
        "spellings": ["ClientInstance"],
        "why": "per-client state: local player, level, options",
    },
    {
        "name": "LocalPlayer",
        "kind": "class_vtable",
        "spellings": ["LocalPlayer"],
        "why": "voice listener position, PvP reach source",
    },
    {
        "name": "Level",
        "kind": "class_vtable",
        "spellings": ["Level"],
        "why": "actor and player lists, tick hook, replay snapshot",
    },
    {
        "name": "Dimension",
        "kind": "class_vtable",
        "spellings": ["Dimension"],
        "why": "per-dimension entity iteration, cross-dimension audio filter",
    },
    {
        "name": "BlockSource",
        "kind": "class_vtable",
        "spellings": ["BlockSource"],
        "why": "block get/set for worldgen and physics overrides",
    },
    {
        "name": "MinecraftGame::getClientInstance",
        "kind": "function",
        "spellings": ["MinecraftGame::getClientInstance", "MinecraftGame::clientInstance"],
        "why": "first hop of every access chain",
    },
    {
        "name": "ClientInstance::getLocalPlayer",
        "kind": "function",
        "spellings": ["ClientInstance::getLocalPlayer", "ClientInstance::getLocalPlayerPtr"],
        "why": "voice listener, reach indicator",
    },
    {
        "name": "ClientInstance::getLevel",
        "kind": "function",
        "spellings": ["ClientInstance::getLevel", "ClientInstance::getLevelPtr"],
        "why": "second hop to the world",
    },
    {
        "name": "ClientInstance::getMinecraftGame",
        "kind": "function",
        "spellings": ["ClientInstance::getMinecraftGame"],
        "why": "back-pointer used by several mods",
    },
    {
        "name": "Level::getDimension",
        "kind": "function",
        "spellings": ["Level::getDimension", "Level::getDimensionConst"],
        "why": "dimension of the local player",
    },
    {
        "name": "Dimension::getBlockSource",
        "kind": "function",
        "spellings": ["Dimension::getBlockSource", "Dimension::getBlockSourceFromMainChunkSource"],
        "why": "block access for worldgen and physics",
    },
    {
        "name": "Level::getBlockSource",
        "kind": "function",
        "spellings": ["Level::getBlockSource"],
        "why": "block access from the level",
    },
]

ENTITY_LIST = [
    {
        "name": "Level::getRuntimeActorList",
        "kind": "function",
        "spellings": ["Level::getRuntimeActorList", "Level::getRuntimeActorListConst"],
        "why": "iterate every entity for ESP, voice emitters, replay",
    },
    {
        "name": "Level::forEachPlayer",
        "kind": "function",
        "spellings": ["Level::forEachPlayer", "Level::forEachPlayerConst"],
        "why": "player list for mic icons and name plates",
    },
    {
        "name": "Level::forEachActor",
        "kind": "function",
        "spellings": ["Level::forEachActor"],
        "why": "entity iteration with a predicate",
    },
    {
        "name": "Level::getPlayerList",
        "kind": "function",
        "spellings": ["Level::getPlayerList", "Level::getUsers", "Level::getPlayerListConst"],
        "why": "player list container, needed for names and XUIDs",
    },
    {
        "name": "Level::fetchEntity",
        "kind": "function",
        "spellings": ["Level::fetchEntity", "Level::getRuntimeEntity"],
        "why": "runtime id to Actor* for hit prediction",
    },
    {
        "name": "Level::getActorByUniqueID",
        "kind": "function",
        "spellings": ["Level::getActorByUniqueID", "Level::getEntityByUniqueId"],
        "why": "unique id to Actor* for replay and kill effects",
    },
    {
        "name": "Dimension::forEachPlayer",
        "kind": "function",
        "spellings": ["Dimension::forEachPlayer"],
        "why": "players in one dimension, for voice falloff",
    },
    {
        "name": "BlockSource::fetchEntities",
        "kind": "function",
        "spellings": ["BlockSource::fetchEntities", "BlockSource::getEntities"],
        "why": "entities inside an AABB",
    },
    {
        "name": "EntityRegistry",
        "kind": "class_vtable",
        "spellings": ["EntityRegistry", "entt::basic_registry"],
        "why": "ECS registry that owns the component pools",
    },
    {
        "name": "EntityContext",
        "kind": "class_vtable",
        "spellings": ["EntityContext", "StrictEntityContext", "entt::entity"],
        "why": "ECS handle used by every system signature",
    },
    {
        "name": "ActorOwnerComponent",
        "kind": "class_vtable",
        "spellings": ["ActorOwnerComponent", "ActorOwnerComponentImpl"],
        "why": "component to Actor* mapping when the build uses ECS",
    },
    {
        "name": "StateVectorComponent",
        "kind": "class_vtable",
        "spellings": ["StateVectorComponent"],
        "why": "position and velocity under ECS",
    },
    {
        "name": "AABBShapeComponent",
        "kind": "class_vtable",
        "spellings": ["AABBShapeComponent", "HitboxComponent"],
        "why": "hitbox under ECS",
    },
    {
        "name": "ActorTypeComponent",
        "kind": "class_vtable",
        "spellings": ["ActorTypeComponent"],
        "why": "entity type id under ECS",
    },
    {
        "name": "EntityRegistry::each",
        "kind": "function",
        "spellings": ["EntityRegistry::each", "EntityRegistry::view", "EntityRegistry::on_construct"],
        "why": "registry iteration entry point",
    },
]

ECS = [
    {
        "name": "ITickingSystem",
        "kind": "class_vtable",
        "spellings": ["ITickingSystem"],
        "why": "base of every ECS ticking system",
    },
    {
        "name": "StrictTickingSystemFunctionAdapter",
        "kind": "class_vtable",
        "spellings": ["StrictTickingSystemFunctionAdapter"],
        "why": "adapts a free function into a system; its mangled name lists the components",
    },
    {
        "name": "EntityComponentDefinition",
        "kind": "class_vtable",
        "spellings": ["EntityComponentDefinition"],
        "why": "component schema, proves the component model",
    },
    {
        "name": "EntityComponentFactoryJson",
        "kind": "class_vtable",
        "spellings": ["EntityComponentFactoryJson", "EntityComponentFactoryCerealBase"],
        "why": "component serialisation",
    },
    {
        "name": "BlockComponentStorage",
        "kind": "class_vtable",
        "spellings": ["BlockComponentStorage", "BlockComponentStorage::ComponentInstance"],
        "why": "per-block ECS storage",
    },
    {
        "name": "entt::basic_storage",
        "kind": "class_vtable",
        "spellings": ["entt::basic_storage"],
        "why": "the pool template behind every component array",
    },
    {
        "name": "entt::basic_sparse_set",
        "kind": "class_vtable",
        "spellings": ["entt::basic_sparse_set"],
        "why": "entity to slot mapping",
    },
]

GROUPS = {
    "access_chain": ACCESS_CHAIN,
    "entity_list": ENTITY_LIST,
    "ecs": ECS,
}
