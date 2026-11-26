package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.InternalCoverage
import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.ShardPart
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
import com.google.gson.JsonParser
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the export path for the category documents the engine writes.
 *
 * The archive must copy those documents byte for byte, count them correctly in
 * the manifest, delete each one once it is inside the archive, and never hold a
 * row on the Java heap.
 */
class OffsetZipShardTest {

    private val rules = listOf(
        CategoryRule("block_placement", "Block placement", emptyList(), emptyList(), emptyList(), true),
        CategoryRule("rendering", "Rendering", emptyList(), emptyList(), emptyList(), false),
        CategoryRule("other_named", "Other", emptyList(), emptyList(), emptyList(), false)
    )

    private fun shard(
        dir: File,
        id: String,
        functions: Int,
        classes: Int = 0,
        symbols: Int = 0
    ): ShardPart {
        val file = File(dir, "$id.json")
        val records = (0 until functions).joinToString(",") { index ->
            """{"address":"0x${(0x1000 + index).toString(16)}","size":16,""" +
                """"name":"Block::placeBlock$index"}"""
        }
        val classRecords = (0 until classes).joinToString(",") { index ->
            """"Block$index":{"name":"Block$index","slots":[]}"""
        }
        val symbolRecords = (0 until symbols).joinToString(",") { index ->
            """{"address":"0x${(0x9000 + index).toString(16)}","name":"sym$index"}"""
        }
        file.writeText(
            """{"schemaVersion":1,"kind":"internal_cpp_category","category":"$id",""" +
                """"label":"${id}","classes":{$classRecords},"functions":[$records],""" +
                """"symbols":[$symbolRecords],"stats":{}}"""
        )
        return ShardPart(
            id = id,
            category = id,
            label = id,
            part = 1,
            classes = classes,
            functions = functions,
            symbols = symbols,
            path = file.absolutePath
        )
    }

    private fun summary(
        shards: List<ShardPart>,
        classMap: Map<String, String>
    ): ScanResultSummary {
        val mapFile = File.createTempFile("classmap", ".json").apply {
            writeText(
                classMap.entries.joinToString(",", "{", "}") { (name, category) ->
                    "\"$name\":\"$category\""
                }
            )
            deleteOnExit()
        }
        return ScanResultSummary(
            displayName = "minecraft-26-60-arm64-v8a-xbox.apk",
            sha256 = "abc",
            sizeBytes = 4096,
            architecture = "AArch64",
            entryPoint = "0x4000",
            sectionCount = 33,
            buildId = "deadbeef",
            apkVersion = "26.60.3",
            internal = InternalSummary(
                classes = shards.sumOf { it.classes },
                vtableSlots = 1,
                functions = shards.sumOf { it.functions },
                dataSymbols = shards.sumOf { it.symbols },
                rows = shards.sumOf { it.entries },
                shards = shards,
                classMapPath = mapFile.absolutePath,
                categoriesApplied = true
            ),
            internalResolved = true
        )
    }

    @Test
    fun everyShardBecomesOneEntryAndIsDeletedAfterwards() {
        val dir = createTempDirectory("shards-").toFile()
        val shards = listOf(
            shard(dir, "block_placement", functions = 3, classes = 2),
            shard(dir, "other_named", functions = 1, symbols = 1)
        )
        val summary = summary(shards, mapOf("Block" to "block_placement"))
        val target = File(createTempDirectory("zip-").toFile(), "BEDROCK_OFFSETS_26.60.3.zip")

        val result = OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
            .build(summary, target)

        assertTrue("the archive must verify", result.verified)
        ZipFile(target).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("InternalCPP/INTERNALCPP_BLOCK_PLACEMENT_26.60.3.json"))
            assertTrue(names.contains("InternalCPP/INTERNALCPP_OTHER_NAMED_26.60.3.json"))
            val entry = zip.getEntry("InternalCPP/INTERNALCPP_BLOCK_PLACEMENT_26.60.3.json")
            val text = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue("the document must be copied verbatim", text.contains("Block::placeBlock2"))
            assertTrue(text.contains("\"category\":\"block_placement\""))
        }
        shards.forEach { shard ->
            assertTrue(
                "the shard must be deleted once it is in the archive",
                !File(shard.path).exists()
            )
        }
    }

    @Test
    fun theManifestCountsEqualTheFileContents() {
        val dir = createTempDirectory("shards-").toFile()
        val shards = listOf(
            shard(dir, "block_placement", functions = 4, classes = 1, symbols = 2),
            shard(dir, "other_named", functions = 5)
        )
        val summary = summary(shards, emptyMap())
        val target = File(createTempDirectory("zip-").toFile(), "BEDROCK_OFFSETS_26.60.3.zip")

        val result = OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
            .build(summary, target)

        val categoryEntries = result.manifest.filter { it.path.startsWith("InternalCPP/INTERNALCPP_") }
            .filterNot { it.path.contains("_INDEX_") }
        assertEquals(2, categoryEntries.size)
        // 4 functions + 1 class + 2 symbols in one file, 5 functions in the other.
        assertEquals(12, categoryEntries.sumOf { it.entries })
        assertEquals(12, result.internalRows)
    }

    @Test
    fun aThreeHundredThousandRowShardSetStaysInsideTheHeapBudget() {
        val dir = createTempDirectory("big-").toFile()
        val shard = shard(dir, "block_placement", functions = 300_000)
        val summary = summary(listOf(shard), emptyMap())
        val target = File(createTempDirectory("zip-").toFile(), "BEDROCK_OFFSETS_26.60.3.zip")
        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(50)
        val before = runtime.totalMemory() - runtime.freeMemory()

        val result = OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
            .build(summary, target)

        val after = runtime.totalMemory() - runtime.freeMemory()
        val growth = (after - before) / (1024 * 1024)
        assertTrue("the archive must verify", result.verified)
        assertTrue(
            "building from a 300,000-row shard must not grow the heap by ${growth}MB",
            growth < HEAP_BUDGET_MB
        )
    }

    private companion object {
        /** Heap the copy-through path may add; parsing the rows needed hundreds. */
        const val HEAP_BUDGET_MB = 96
    }

    @Test
    fun theArchiveCarriesAReadmeAndTheCoverageBlocks() {
        val dir = createTempDirectory("shards-").toFile()
        val shards = listOf(shard(dir, "block_placement", functions = 2, classes = 1))
        val summary = summary(shards, mapOf("Block" to "block_placement"))
            .let { base ->
                base.copy(
                    internal = base.internal?.copy(
                        coverage = InternalCoverage(
                            functions = 2,
                            fdeFunctions = 2,
                            functionsMatchFdes = true,
                            classes = 1,
                            slots = 1,
                            dataSymbols = 0,
                            functionSource = "eh_frame"
                        )
                    )
                )
            }
        val target = File(createTempDirectory("zip-").toFile(), "BEDROCK_OFFSETS_26.60.3.zip")

        val result = OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
            .build(summary, target)

        assertTrue(result.verified)
        assertEquals(3, result.rereadRows)
        ZipFile(target).use { zip ->
            val readme = zip.getInputStream(zip.getEntry("README.txt")).bufferedReader().readText()
            assertTrue("the guide must name the version", readme.contains("BEDROCK_OFFSETS 26.60.3"))
            assertTrue("the guide must explain how to load", readme.contains("HOW TO LOAD"))
            assertTrue("the guide must list the categories", readme.contains("block_placement"))

            val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                .bufferedReader()
                .use { JsonParser.parseReader(it).asJsonObject }
            val coverage = manifest.getAsJsonObject("coverage").getAsJsonObject("internal")
            assertEquals(2, coverage.get("fdeFunctions").asInt)
            assertTrue(coverage.get("functionsMatchFdes").asBoolean)
            val categories = manifest.getAsJsonArray("categories")
            assertEquals(1, categories.size())
            assertEquals(3, categories[0].asJsonObject.get("entries").asInt)
            val completeness = manifest.getAsJsonObject("completeness")
            assertEquals(3, completeness.get("expectedRows").asInt)
            assertTrue(completeness.get("complete").asBoolean)
        }
    }

    @Test
    fun aRowCountTheArchiveCannotBackUpFailsVerification() {
        val dir = createTempDirectory("shards-").toFile()
        val shards = listOf(shard(dir, "block_placement", functions = 2))
        val summary = summary(shards, emptyMap())
        val target = File(createTempDirectory("zip-").toFile(), "BEDROCK_OFFSETS_26.60.3.zip")
        val builder = OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
        val result = builder.build(summary, target)
        val categories = result.manifest
            .filter { it.path.startsWith("InternalCPP/INTERNALCPP_") && !it.path.contains("_INDEX_") }
            .map { it.path }

        // Re-reading the finished archive must catch a scan that claims more
        // rows than the files hold, which a manifest-only check never could.
        val outcome = builder.verify(target, result.manifest, categories, expectedInternalRows = 3)

        assertTrue(!outcome.ok)
        assertEquals(2, outcome.rereadRows)
        assertTrue(outcome.detail.contains("2 rows"))
    }

    @Test
    fun theIndexListsEmptyCategoriesWithNoFile() {
        val dir = createTempDirectory("shards-").toFile()
        val summary = summary(listOf(shard(dir, "block_placement", functions = 1)), emptyMap())
        val target = File(createTempDirectory("zip-").toFile(), "BEDROCK_OFFSETS_26.60.3.zip")

        OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile()).build(summary, target)

        ZipFile(target).use { zip ->
            val index = zip.getInputStream(
                zip.getEntry("InternalCPP/INTERNALCPP_INDEX_26.60.3.json")
            ).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            val categories = index.getAsJsonArray("categories")
            val byCategory = categories.map { it.asJsonObject }
                .associateBy { it.get("category").asString }
            assertEquals(3, byCategory.size)
            val rendering = byCategory.getValue("rendering")
            assertEquals(0, rendering.get("entries").asInt)
            assertTrue("an empty category has no file", rendering.get("file").isJsonNull)
            assertEquals(0, rendering.getAsJsonArray("files").size())
            assertEquals(
                "InternalCPP/INTERNALCPP_BLOCK_PLACEMENT_26.60.3.json",
                byCategory.getValue("block_placement").get("file").asString
            )
        }
    }
}
