package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.data.native.TypeVTable
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the JSON document handed to a downstream loader. */
class OffsetExporterTest {

    private val text = SectionInfo(
        name = ".text",
        kind = "text",
        address = 0x400000,
        size = 0x7a0000,
        offset = 0x3000,
        align = 16,
        delta = 0x3fd000,
        allocated = true,
        executable = true,
        writable = false,
        occupiesFile = true
    )

    private val summary = ScanResultSummary(
        displayName = "minecraft-1.21.50.apk",
        sha256 = "0123456789abcdef",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        textSection = text,
        buildId = "410ac215603eebd7",
        buildIdSource = "note",
        patternHits = listOf(
            PatternHit(
                name = "clientInstance",
                sectionOffset = 0x1000,
                fileOffset = 0x401000,
                address = 0x401000,
                addressHex = "0x401000",
                prologueKind = "frame_push",
                prologueInstruction = "stp x29, x30, [sp, #-16]!"
            )
        ),
        discardedMatches = 2,
        vtables = VtableMap(
            source = VtableMap.SOURCE_RELOCATIONS,
            typeInfoCandidates = 1,
            relocationsExamined = 3,
            entries = mapOf(
                "ClientInstance" to TypeVTable(
                    name = "ClientInstance",
                    mangled = "14ClientInstance",
                    vtableAddress = 0x403000,
                    vtableHex = "0x403000",
                    vtableOffset = 0x3000,
                    typeInfoAddress = 0x403080
                )
            )
        ),
        symbols = listOf(
            ExportedSymbol(
                name = "Java_com_example_start",
                address = 0x410000,
                addressHex = "0x410000",
                size = 32,
                binding = 1
            )
        ),
        expectedPatterns = listOf("clientInstance", "absentSignature")
    )

    @Test
    fun theDocumentIsValidJsonWithTheExpectedShape() {
        val root = JsonParser.parseString(OffsetExporter.toJson(summary)).asJsonObject

        assertEquals(OffsetExporter.SCHEMA_VERSION, root.get("schemaVersion").asInt)
        assertEquals("BedrockOffsetExtractor", root.get("generatedBy").asString)

        val source = root.getAsJsonObject("source")
        assertEquals("minecraft-1.21.50.apk", source.get("apk").asString)
        assertEquals("410ac215603eebd7", source.get("buildId").asString)
        assertEquals(0x400000L, source.get("textAddress").asLong)
        assertEquals(0x3fd000L, source.get("textDelta").asLong)
    }

    @Test
    fun everyOffsetAppearsUnderAStableKey() {
        val offsets = JsonParser.parseString(OffsetExporter.toJson(summary))
            .asJsonObject
            .getAsJsonObject("offsets")

        assertEquals("0x403000", offsets.get("vtable:ClientInstance").asString)
        assertEquals("0x401000", offsets.get("pattern:clientInstance").asString)
        assertEquals("0x410000", offsets.get("symbol:Java_com_example_start").asString)
    }

    @Test
    fun aSignatureThatDidNotMatchIsWrittenAsMissing() {
        val offsets = JsonParser.parseString(OffsetExporter.toJson(summary))
            .asJsonObject
            .getAsJsonObject("offsets")

        assertEquals(OffsetRows.MISSING_ADDRESS, offsets.get("pattern:absentSignature").asString)
    }

    @Test
    fun theStatsLetAReaderJudgeCompleteness() {
        val stats = JsonParser.parseString(OffsetExporter.toJson(summary))
            .asJsonObject
            .getAsJsonObject("stats")

        assertEquals(2, stats.get("expectedSignatures").asInt)
        assertEquals(1, stats.get("matchedSignatures").asInt)
        assertEquals(2, stats.get("discardedMatches").asInt)
        assertEquals(1, stats.get("vtables").asInt)
        assertEquals(1, stats.get("symbols").asInt)
    }

    @Test
    fun theVtableEntryCarriesBothAbsoluteAndRebaseStableAddresses() {
        val vtable = JsonParser.parseString(OffsetExporter.toJson(summary))
            .asJsonObject
            .getAsJsonObject("vtables")
            .getAsJsonObject("ClientInstance")

        assertEquals("0x403000", vtable.get("address").asString)
        assertEquals("0x3000", vtable.get("offset").asString)
        assertEquals("0x403080", vtable.get("typeInfo").asString)
    }

    @Test
    fun theDocumentIsPrettyPrinted() {
        val json = OffsetExporter.toJson(summary)

        assertTrue("expected indentation", json.contains("\n  "))
        assertTrue("expected a trailing newline", json.endsWith("\n"))
    }

    @Test
    fun theFileNameIsKeyedByBuildId() {
        assertEquals("offsets_410ac215603eebd7.json", OffsetExporter.fileName(summary))
    }

    @Test
    fun theFileNameFallsBackToTheHashWhenThereIsNoBuildId() {
        val name = OffsetExporter.fileName(summary.copy(buildId = ""))

        assertEquals("offsets_0123456789abcdef.json", name)
        assertFalse(name.contains("/"))
    }

    @Test
    fun anEmptySummaryStillProducesAValidDocument() {
        val empty = summary.copy(
            patternHits = emptyList(),
            vtables = VtableMap.EMPTY,
            symbols = emptyList(),
            expectedPatterns = emptyList()
        )

        val root = JsonParser.parseString(OffsetExporter.toJson(empty)).asJsonObject

        assertEquals(0, root.getAsJsonObject("offsets").size())
        assertEquals(0, root.getAsJsonObject("vtables").size())
    }
}
