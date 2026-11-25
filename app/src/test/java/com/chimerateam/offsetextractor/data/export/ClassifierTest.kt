package com.chimerateam.offsetextractor.data.export

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the ordered classifier against the shipped rule file. */
class ClassifierTest {

    private fun rules(): List<CategoryRule> {
        val candidates = listOf(
            File("src/main/assets/categories.json"),
            File("app/src/main/assets/categories.json")
        )
        val file = candidates.first { it.isFile }
        return CategoryCatalog.parse(file.readText())
    }

    private fun classifier() = Classifier(rules())

    @Test
    fun aSubclassInheritsItsBaseCategory() {
        val classifier = classifier()

        assertEquals(
            "actor_entity",
            classifier.categoryForClass("Player", listOf("Mob", "Actor"))
        )
    }

    @Test
    fun aClassRuleMatchesTheClassItself() {
        val classifier = classifier()

        assertEquals("block_placement", classifier.categoryForClass("BlockSource", emptyList()))
        assertEquals("client_game", classifier.categoryForClass("ClientInstance", emptyList()))
    }

    @Test
    fun aFunctionRegexMatchesASymbolNamedFunction() {
        val classifier = classifier()

        assertEquals(
            "block_placement",
            classifier.categoryForFunction("BlockSource::mayPlace", null)
        )
        assertEquals("combat", classifier.categoryForFunction("Mob::hurt", null))
    }

    @Test
    fun aNamespaceSendsThirdPartyCodeToItsOwnCategory() {
        val classifier = classifier()

        assertEquals(
            "thirdparty",
            classifier.categoryForFunction("std::__ndk1::vector<int>::push_back", null)
        )
        assertEquals(
            "thirdparty",
            classifier.categoryForFunction("webrtc::VideoFrame::timestamp", null)
        )
    }

    @Test
    fun theFirstMatchingRuleWins() {
        val classifier = classifier()

        // `BlockSource` matches block_placement before anything else, even
        // though its bases could match actor_entity.
        assertEquals(
            "block_placement",
            classifier.categoryForClass("BlockSource", listOf("Actor"))
        )
    }

    @Test
    fun anUnmatchedNameFallsBackToOtherNamed() {
        val classifier = classifier()

        assertEquals(
            "other_named",
            classifier.categoryForFunction("Mystery::doSomething", null)
        )
    }

    @Test
    fun aSubPlaceholderIsUnnamed() {
        val classifier = classifier()

        assertEquals("unnamed", classifier.categoryForFunction("sub_13cc5b40", null))
        assertTrue(classifier.isUnnamed("sub_13cc5b40"))
    }

    @Test
    fun aClassCategoryWinsOverAFunctionRule() {
        val classifier = classifier()

        assertEquals(
            "actor_entity",
            classifier.categoryForFunction("Actor::hurt", "actor_entity")
        )
    }

    @Test
    fun aBareRuleArrayUsesTheCategoryKey() {
        val json = listOf(
            "{\"category\":\"first\",\"classes\":[\"^Alpha\"]}",
            "{\"category\":\"second\",\"classes\":[\"^Beta\"]}"
        ).joinToString(prefix = "[", postfix = "]", separator = ",")

        val parsed = CategoryCatalog.parse(json)

        assertEquals(listOf("first", "second"), parsed.map { it.id })
        assertEquals("first", Classifier(parsed).categoryForClass("Alpha", emptyList()))
    }

    @Test
    fun theBundledRulesUseTheCategoryKey() {
        val file = listOf(
            File("src/main/assets/categories.json"),
            File("app/src/main/assets/categories.json")
        ).first { it.isFile }

        assertTrue(file.readText().contains("\"category\""))
    }
}
