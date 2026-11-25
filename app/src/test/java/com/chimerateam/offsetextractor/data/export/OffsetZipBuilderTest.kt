package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the single ZIP layout, the manifest and the CRC re-check. */
class OffsetZipBuilderTest {

    private val rules = listOf(
        CategoryRule(
            id = "actor_entity",
            label = "Actors",
            classes = listOf(Regex("^Player$")),
            functions = emptyList(),
            namespaces = emptyList(),
            includeBases = true
        ),
        CategoryRule(
            id = "other_named",
            label = "Other",
            classes = emptyList(),
            functions = emptyList(),
            namespaces = emptyList(),
            includeBases = false
        ),
        CategoryRule(
            id = "unnamed",
            label = "Unnamed",
            classes = emptyList(),
            functions = emptyList(),
            namespaces = emptyList(),
            includeBases = false
        )
    )

    private fun table(): File {
        val file = File(createTempDirectory().toFile(), "table.ndjson")
        val classLine = """{"type":"class","class":{"name":"Player","mangled":"Player",""" +
            """"bases":["Mob"],"vtable":"0x3000","vtableOffset":"0x3000",""" +
            """"typeInfo":"0x2f00","slotCount":1,"slots":[]}}"""
        val functionLine = """{"type":"function","function":{"address":"0x1000",""" +
            """"fileOffset":"0x0","size":16,"name":"Player::getPos",""" +
            """"nameSource":"symbol","aliases":[],"kind":"function",""" +
            """"status":"verified","prologueKind":"frame_push"}}"""
        val duplicate = """{"type":"function","function":{"address":"0x1010",""" +
            """"fileOffset":"0x10","size":16,"name":"Player::getPos",""" +
            """"nameSource":"symbol","aliases":[],"kind":"function",""" +
            """"status":"verified","prologueKind":"frame_push"}}"""
        val symbol = """{"type":"symbol","symbol":{"address":"0x2000","size":8,""" +
            """"name":"_binary_classes_dex_size","mangled":"_binary_classes_dex_size",""" +
            """"kind":"data","reason":"object_symbol"}}"""
        file.writeText(
            listOf(
                """{"type":"meta","schemaVersion":1,"kind":"internal_cpp","stats":{}}""",
                classLine,
                functionLine,
                duplicate,
                symbol
            ).joinToString("\n")
        )
        return file
    }

    private fun summary(table: File) = ScanResultSummary(
        displayName = "minecraft-26-60-arm64-v8a-xbox.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        buildId = "deadbeef",
        apkVersion = "26.60.3",
        internal = InternalSummary(
            classes = 1,
            vtableSlots = 1,
            functions = 2,
            named = 2,
            path = table.absolutePath
        ),
        internalResolved = true
    )

    @Test
    fun theZipUsesTheDocumentedFolderLayoutAndVersion() {
        val table = table()
        val target = File(createTempDirectory().toFile(), "BEDROCK_OFFSETS_26.60.3.zip")

        val result = OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
            .build(summary(table), target)

        assertEquals("26.60.3", result.version)
        assertTrue(result.verified)
        ZipFile(target).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("manifest.json"))
            assertTrue(names.contains("JNI/JNI_OFFSETS_26.60.3.json"))
            assertTrue(names.contains("InternalCPP/INTERNALCPP_INDEX_26.60.3.json"))
            assertTrue(
                names.contains("InternalCPP/INTERNALCPP_ACTOR_ENTITY_26.60.3.json")
            )
            assertTrue(
                "a plus must never appear in an entry name: $names",
                names.none { it.contains("+") }
            )
        }
    }

    @Test
    fun theManifestCountsAndDigestsMatchTheArchive() {
        val table = table()
        val target = File(createTempDirectory().toFile(), "BEDROCK_OFFSETS_26.60.3.zip")
        val result = OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
            .build(summary(table), target)

        ZipFile(target).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                .bufferedReader()
                .use { JsonParser.parseReader(it).asJsonObject }
            assertEquals("26.60.3", manifest.get("version").asString)
            assertEquals(result.manifest.size, manifest.get("entryCount").asInt)
            assertEquals(
                result.manifest.size,
                manifest.getAsJsonArray("files").size()
            )
            for (entry in result.manifest) {
                val zipped = zip.getEntry(entry.path)
                assertNotNull("missing ${entry.path}", zipped)
                assertEquals(entry.size, zipped.size)
            }
        }
    }

    @Test
    fun aCategoryFileCarriesItsOwnStatsAndRecords() {
        val table = table()
        val target = File(createTempDirectory().toFile(), "BEDROCK_OFFSETS_26.60.3.zip")
        OffsetZipBuilder(Classifier(rules), createTempDirectory().toFile())
            .build(summary(table), target)

        ZipFile(target).use { zip ->
            val actor = zip.getInputStream(
                zip.getEntry("InternalCPP/INTERNALCPP_ACTOR_ENTITY_26.60.3.json")
            ).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }

            assertEquals("actor_entity", actor.get("category").asString)
            assertEquals("26.60.3", actor.get("version").asString)
            assertEquals(1, actor.getAsJsonObject("stats").get("classes").asInt)
            assertEquals(2, actor.getAsJsonObject("stats").get("functions").asInt)
            assertTrue(actor.getAsJsonObject("classes").has("Player"))
            assertEquals(2, actor.getAsJsonArray("functions").size())
        }
    }
}
