package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.ShardPart
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
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
}
