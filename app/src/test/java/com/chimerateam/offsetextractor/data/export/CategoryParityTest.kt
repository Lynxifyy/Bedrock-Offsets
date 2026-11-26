package com.chimerateam.offsetextractor.data.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the shipped category rules to the names a client developer looks for.
 *
 * The Rust engine classifies with the same document, and its own tests assert
 * the same expectations, so the two implementations cannot drift apart without
 * one of them failing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryParityTest {

    private val classifier = Classifier(
        CategoryCatalog.load(ApplicationProvider.getApplicationContext<Context>())
    )

    @Test
    fun blockPlacementHoldsTheBlockClasses() {
        listOf("Block", "BlockLegacy", "BlockSource", "BlockActor", "BlockItem", "GameMode")
            .forEach { name ->
                assertEquals(
                    "$name must be in block placement",
                    "block_placement",
                    classifier.categoryForClass(name, emptyList())
                )
            }
    }

    @Test
    fun blockPlacementHoldsThePlacementFunctions() {
        listOf(
            "GameMode::useItemOn(BlockPos const&)",
            "BlockLegacy::buildBlock",
            "BlockSource::placeBlock",
            "Block::mayPlace",
            "Block::tryPlace",
            "Player::destroyBlock"
        ).forEach { name ->
            assertEquals(
                "$name must be in block placement",
                "block_placement",
                classifier.categoryForFunction(name, null)
            )
        }
    }

    @Test
    fun theOtherCategoriesKeepTheirClasses() {
        assertEquals("actor_entity", classifier.categoryForClass("Actor", emptyList()))
        assertEquals("actor_entity", classifier.categoryForClass("Mob", emptyList()))
        assertEquals("world_level", classifier.categoryForClass("Level", emptyList()))
        assertEquals("client_game", classifier.categoryForClass("ClientInstance", emptyList()))
        assertEquals("rendering", classifier.categoryForClass("LevelRenderer", emptyList()))
    }

    @Test
    fun aNamespacePrefixStopsAtTheNamespaceBoundary() {
        // `Json` used to claim every Mojang `Json*` class by bare prefix match,
        // so 148,009 game functions were filed as third-party libraries.
        assertEquals("thirdparty", classifier.categoryForFunction("Json::Value::asString", null))
        assertEquals("other_named", classifier.categoryForFunction("JsonUtil::parse", null))
        assertEquals(
            "thirdparty",
            classifier.categoryForFunction("std::vector<int>::push_back", null)
        )
        assertEquals(
            "thirdparty",
            classifier.categoryForFunction("icu_69::UnicodeString::length", null)
        )
    }

    @Test
    fun theEntityComponentSystemHasItsOwnCategory() {
        listOf(
            "EntityRegistry",
            "EntityContext",
            "StateVectorComponent",
            "AABBShapeComponent",
            "ActorOwnerComponent",
            "StrictTickingSystemFunctionAdapter"
        ).forEach { name ->
            assertEquals("$name is ECS", "ecs", classifier.categoryForClass(name, emptyList()))
        }
        assertEquals("ecs", classifier.categoryForFunction("ecs::registry::view", null))
        // entt itself is a third-party library; only its use through a Bedrock
        // ECS class makes a row gameplay code.
        assertEquals("thirdparty", classifier.categoryForFunction("entt::basic_view::each", null))
    }

    @Test
    fun theScriptingApiHasItsOwnCategory() {
        assertEquals(
            "scripting",
            classifier.categoryForFunction("Scripting::Player::getPosition", null)
        )
        assertEquals(
            "scripting",
            classifier.categoryForClass("ScriptModuleMinecraft", emptyList())
        )
        assertEquals("scripting", classifier.categoryForClass("Scripting::Player", emptyList()))
    }

    @Test
    fun anUnknownSubIsUnnamedAndAnUnknownNameIsOther() {
        assertEquals("unnamed", classifier.categoryForFunction("sub_1234", null))
        assertEquals("other_named", classifier.categoryForFunction("SomethingElse", null))
    }
}
