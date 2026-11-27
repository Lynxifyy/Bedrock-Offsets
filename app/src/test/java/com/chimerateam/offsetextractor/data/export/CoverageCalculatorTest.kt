package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.ShardPart
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the coverage screen counts the same things the Python report does.
 *
 * The fixtures are real category documents in the engine's own shape, written
 * by hand, so the parser is exercised against the format it will meet rather
 * than against a mock of it.
 */
class CoverageCalculatorTest {

    private fun document(category: String, functions: String, classes: String = "{}"): String = """
        {"schemaVersion":2,"generatedBy":"BedrockOffsetExtractor",
        "kind":"internal_cpp_category","category":"$category","label":"$category",
        "classes":$classes,"functions":[$functions],"symbols":[]}
    """.trimIndent()

    private fun shard(dir: File, category: String, body: String): ShardPart {
        val file = File(dir, "INTERNALCPP_${category.uppercase()}.json")
        file.writeText(body)
        return ShardPart(
            id = category,
            category = category,
            label = category,
            functions = 1,
            path = file.absolutePath,
            bytes = file.length()
        )
    }

    private fun summary(shards: List<ShardPart>) = ScanResultSummary(
        displayName = "fixture.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "none",
        sectionCount = 8,
        internal = InternalSummary(functions = 1, shards = shards),
        internalResolved = true
    )

    @Test
    fun realNamesPlaceholdersAndUnnamedAreCountedApart() {
        val dir = createTempDirectory().toFile()
        val body = document(
            "client_game",
            """
            {"address":"0x10","name":"Level::tick","nameSource":"symbol",
             "signature":"aa bb","signatureUnique":true},
            {"address":"0x20","name":"Player::vfunc_3","nameSource":"vtable",
             "signature":"cc dd","signatureUnique":false},
            {"address":"0x30","name":"sub_1234","nameSource":"auto","signature":null},
            {"address":"0x40","name":"sub_5678","nameSource":"auto","signature":null}
            """.trimIndent()
        )

        val report = CoverageCalculator.compute(summary(listOf(shard(dir, "client_game", body))))

        val bucket = report!!.categories.single()
        assertEquals(4, bucket.functions)
        assertEquals(1, bucket.realNamed)
        assertEquals(1, bucket.placeholderSlots)
        assertEquals(2, bucket.unnamed)
        assertEquals(2, bucket.signatures)
        assertEquals(1, bucket.uniqueSignatures)
        assertEquals(25.0, bucket.realNamedPercent, 1e-9)
    }

    @Test
    fun theGameplayTotalLeavesTheThirdPartyCategoryOut() {
        val dir = createTempDirectory().toFile()
        val gameplay = document(
            "actor_entity",
            """{"address":"0x10","name":"Actor::getPos","nameSource":"symbol",
                "signature":"aa","signatureUnique":true}"""
        )
        val thirdParty = document(
            "thirdparty",
            """{"address":"0x20","name":"v8::internal::Heap::CollectGarbage",
                "nameSource":"symbol","signature":"bb","signatureUnique":true}"""
        )

        val report = CoverageCalculator.compute(
            summary(
                listOf(
                    shard(dir, "actor_entity", gameplay),
                    shard(dir, "thirdparty", thirdParty)
                )
            )
        )!!

        assertEquals(2, report.overall.functions)
        assertEquals(2, report.overall.realNamed)
        assertEquals(1, report.gameplay.functions)
        assertEquals(1, report.gameplay.realNamed)
        assertEquals(100.0, report.gameplay.realNamedPercent, 1e-9)
    }

    @Test
    fun vtableGroupsAreCountedPerClassSoCollapsingIsVisible() {
        val dir = createTempDirectory().toFile()
        val body = document(
            "client_game",
            """{"address":"0x10","name":"Level::tick","nameSource":"symbol",
                "signature":"aa","signatureUnique":true}""",
            """
            {"Level":{"name":"Level","vtable":"0x100","vtableGroups":[
                {"mangled":"_ZTV5Level","vtable":"0x100","slots":4},
                {"mangled":"_ZTVN5Level3SubE","vtable":"0x200","slots":2}]},
             "Player":{"name":"Player","vtable":"0x300"}}
            """.trimIndent()
        )

        val report = CoverageCalculator.compute(summary(listOf(shard(dir, "client_game", body))))!!

        val bucket = report.overall
        assertEquals(2, bucket.classes)
        assertEquals(3, bucket.vtableGroups)
    }

    @Test
    fun aScanWithoutCategoryDocumentsReportsNothing() {
        assertNull(CoverageCalculator.compute(summary(emptyList())))
    }

    @Test
    fun aMissingDocumentIsSkippedRatherThanFailingTheScreen() {
        val dir = createTempDirectory().toFile()
        val shard = ShardPart(
            id = "client_game",
            category = "client_game",
            path = File(dir, "gone.json").absolutePath
        )

        assertNull(CoverageCalculator.compute(summary(listOf(shard))))
    }

    @Test
    fun theOverallRowIsTheSumOfTheCategories() {
        val dir = createTempDirectory().toFile()
        val first = document(
            "client_game",
            """{"address":"0x10","name":"Level::tick","nameSource":"symbol",
                "signature":"aa","signatureUnique":true}"""
        )
        val second = document(
            "actor_entity",
            """{"address":"0x20","name":"sub_99","nameSource":"auto","signature":"bb",
                "signatureUnique":true}"""
        )

        val report = CoverageCalculator.compute(
            summary(listOf(shard(dir, "client_game", first), shard(dir, "actor_entity", second)))
        )!!

        assertTrue(report.categories.size == 2)
        assertEquals(2, report.overall.functions)
        assertEquals(1, report.overall.realNamed)
        assertEquals(2, report.overall.uniqueSignatures)
        assertEquals(100.0, report.overall.uniqueSignaturePercent, 1e-9)
        assertEquals(50.0, report.overall.realNamedPercent, 1e-9)
    }
}
