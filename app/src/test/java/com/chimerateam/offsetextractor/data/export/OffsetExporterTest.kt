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
    fun theJniFileNameUsesTheVersionFromTheApkName() {
        assertEquals("JNI_OFFSETS_1.21.50.json", OffsetExporter.jniFileName(summary))
        assertEquals(
            "INTERNALCPP_OFFSETS_1.21.50.json",
            OffsetExporter.internalFileName(summary)
        )
    }

    @Test
    fun aTwoPartVersionIsJoinedWithDots() {
        val name = OffsetExporter.jniFileName(
            summary.copy(displayName = "minecraft-26-66.apk")
        )

        assertEquals("JNI_OFFSETS_26.66.json", name)
    }

    @Test
    fun aThreePartVersionIsKept() {
        val name = OffsetExporter.jniFileName(
            summary.copy(displayName = "minecraft-1-16-221.apk")
        )

        assertEquals("JNI_OFFSETS_1.16.221.json", name)
    }

    @Test
    fun theManifestVersionIsTheSecondChoice() {
        val name = OffsetExporter.jniFileName(
            summary.copy(displayName = "minecraft.apk", apkVersion = "1.20.80")
        )

        assertEquals("JNI_OFFSETS_1.20.80.json", name)
    }

    @Test
    fun theBuildIdIsTheLastResort() {
        val name = OffsetExporter.jniFileName(
            summary.copy(
                displayName = "minecraft.apk",
                apkVersion = "",
                buildId = "410ac215603eebd7"
            )
        )

        assertEquals("JNI_OFFSETS_410ac215.json", name)
    }

    @Test
    fun unsafeCharactersAreRemovedFromTheVersion() {
        val name = OffsetExporter.jniFileName(
            summary.copy(displayName = "minecraft-26-66 (beta!).apk")
        )

        assertEquals("JNI_OFFSETS_26.66.json", name)
        assertFalse(name.contains("("))
        assertFalse(name.contains("!"))
        assertFalse(name.contains("/"))
    }

    @Test
    fun theInternalDocumentStreamsTheTable() {
        val table = kotlin.io.path.createTempFile("internal", ".ndjson").toFile()
        table.deleteOnExit()
        val metaLine = """{"type":"meta","schemaVersion":1,"kind":"internal_cpp","stats":{}}"""
        val classLine = listOf(
            """{"type":"class","class":{"name":"ClientInstance",""",
            """"mangled":"14ClientInstance","bases":[],""",
            """"vtable":"0x403000","vtableOffset":"0x3000",""",
            """"typeInfo":"0x403080","slotCount":1,"slots":[""",
            """{"slot":0,"offset":"0x0","address":"0x401000",""",
            """"fileOffset":"0x1000","size":4,""",
            """"name":"ClientInstance::vfunc_0","kind":"virtual",""",
            """"overrides":false,"inherited":false,"status":"verified"}]}}"""
        ).joinToString("")
        val functionLine = listOf(
            """{"type":"function","function":{"address":"0x401000",""",
            """"fileOffset":"0x1000","size":4,""",
            """"name":"ClientInstance::vfunc_0","nameSource":"vtable",""",
            """"aliases":[],"kind":"function","status":"verified",""",
            """"prologueKind":"frame_push"}}"""
        ).joinToString("")
        table.writeText(listOf(metaLine, classLine, functionLine).joinToString("\n"))
        val internal = com.chimerateam.offsetextractor.data.native.InternalSummary(
            classes = 1,
            vtableSlots = 1,
            functions = 1,
            named = 1,
            path = table.absolutePath
        )
        val writer = java.io.StringWriter()

        OffsetExporter.writeInternal(summary.copy(internal = internal), writer)

        val root = JsonParser.parseString(writer.toString()).asJsonObject
        assertEquals("internal_cpp", root.get("kind").asString)
        assertEquals(1, root.getAsJsonObject("stats").get("functions").asInt)
        assertEquals(
            "0x401000",
            root.getAsJsonObject("classes")
                .getAsJsonObject("ClientInstance")
                .getAsJsonArray("slots")[0].asJsonObject.get("address").asString
        )
        assertEquals(
            "0x401000",
            root.getAsJsonObject("offsets").get("ClientInstance::vfunc_0").asString
        )
        assertEquals(
            1,
            root.getAsJsonArray("functions").size()
        )
    }

    @Test
    fun theZipNameAndTheJsonFieldShareOneVersion() {
        val versioned = summary.copy(
            displayName = "minecraft-26-60-arm64-v8a-xbox.apk",
            apkVersion = "26.60.3"
        )

        assertEquals("26.60.3", OffsetExporter.versionLabel(versioned))
        assertEquals("BEDROCK_OFFSETS_26.60.3.zip", OffsetExporter.zipFileName(versioned))
        assertEquals(
            "JNI_OFFSETS_26.60.3.json",
            OffsetExporter.jniFileName(versioned)
        )
        val source = JsonParser.parseString(OffsetExporter.toJson(versioned))
            .asJsonObject
            .getAsJsonObject("source")
        assertEquals("26.60.3", source.get("apkVersion").asString)
    }

    @Test
    fun theManifestVersionWinsOverTheApkName() {
        val versioned = summary.copy(
            displayName = "minecraft-26-66.apk",
            apkVersion = "26.60.3"
        )

        assertEquals("BEDROCK_OFFSETS_26.60.3.zip", OffsetExporter.zipFileName(versioned))
    }

    @Test
    fun collidingFunctionNamesGetAnAddressSuffix() {
        val table = kotlin.io.path.createTempFile("internal", ".ndjson").toFile()
        table.deleteOnExit()
        fun line(address: String) = """{"type":"function","function":{""" +
            """"address":"$address","fileOffset":"0x0","size":4,""" +
            """"name":"Player::getPos","nameSource":"symbol","aliases":[],""" +
            """"kind":"function","status":"verified","prologueKind":"none"}}"""
        table.writeText(listOf(line("0x1000"), line("0x1010")).joinToString("\n"))
        val internal = com.chimerateam.offsetextractor.data.native.InternalSummary(
            functions = 2,
            named = 2,
            path = table.absolutePath
        )
        val writer = java.io.StringWriter()

        OffsetExporter.writeInternal(summary.copy(internal = internal), writer)

        val offsets = JsonParser.parseString(writer.toString())
            .asJsonObject
            .getAsJsonObject("offsets")
        assertEquals(2, offsets.size())
        assertEquals("0x1000", offsets.get("Player::getPos@0x1000").asString)
        assertEquals("0x1010", offsets.get("Player::getPos@0x1010").asString)
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
