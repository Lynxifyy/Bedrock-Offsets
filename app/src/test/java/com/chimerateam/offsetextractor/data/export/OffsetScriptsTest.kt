package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.JniOffsetEntry
import com.chimerateam.offsetextractor.data.native.JniReport
import com.chimerateam.offsetextractor.data.native.TypeVTable
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modder-facing script exports built from a scan. */
class OffsetScriptsTest {

    private val summary = ScanResultSummary(
        displayName = "bedrock.apk",
        sha256 = "abc",
        sizeBytes = 1,
        architecture = "AArch64",
        entryPoint = "0x0",
        sectionCount = 30,
        buildId = "1.21.0",
        imageBase = 0x1000,
        jni = JniReport(
            offsets = listOf(
                JniOffsetEntry(
                    javaName = "Java_com_mojang_Native_nativeTick",
                    address = 0x2000,
                    addressHex = "0x2000",
                    source = "symbol",
                    confidence = "high"
                )
            )
        ),
        vtables = VtableMap(
            source = VtableMap.SOURCE_RELOCATIONS,
            typeInfoCandidates = 1,
            relocationsExamined = 1,
            entries = mapOf(
                "_ZTV5Actor" to TypeVTable(
                    name = "Actor",
                    mangled = "_ZTV5Actor",
                    vtableAddress = 0x3010,
                    vtableHex = "0x3010",
                    vtableOffset = 0x2010,
                    typeInfoAddress = 0x3000
                )
            )
        ),
        symbols = listOf(
            ExportedSymbol(
                name = "Java_com_mojang_Native_nativeTick",
                address = 0x2000,
                addressHex = "0x2000",
                size = 16,
                binding = 1
            ),
            ExportedSymbol(
                name = "operator new(unsigned long)",
                address = 0x4000,
                addressHex = "0x4000",
                size = 16,
                binding = 1
            )
        )
    )

    @Test
    fun entriesAreRvAsDeduplicatedByName() {
        val entries = OffsetScripts.entries(summary)

        assertEquals(3, entries.size)
        assertEquals(0x2010L, entries.first { it.name == "Actor" }.rva)
        assertEquals(0x1000L, entries.first { it.name.startsWith("Java_") }.rva)
    }

    @Test
    fun idaPythonNamesEveryEntryFromTheImageBase() {
        val script = OffsetScripts.render(summary, ScriptFormat.IdaPython)

        assertTrue(script.contains("import idc"))
        assertTrue(script.contains("IMAGE_BASE = 0x1000"))
        assertTrue(script.contains("idc.set_name(IMAGE_BASE + 0x1000,"))
    }

    @Test
    fun ghidraScriptCarriesALabelForEveryEntry() {
        val script = OffsetScripts.render(summary, ScriptFormat.GhidraScript)

        assertTrue(script.contains("extends GhidraScript"))
        assertTrue(script.contains("createLabel(toAddr(delta + 0x1000L)"))
    }

    @Test
    fun fridaScriptExposesAnOffsetsMapAndAttachHelper() {
        val script = OffsetScripts.render(summary, ScriptFormat.FridaTypeScript)

        assertTrue(script.contains("const moduleName = \"libminecraftpe.so\";"))
        assertTrue(script.contains("\"Actor\": 0x2010,"))
        assertTrue(script.contains("Interceptor.attach(base.add(offsets[name])"))
    }

    @Test
    fun cppHeaderSanitisesNames() {
        val script = OffsetScripts.render(summary, ScriptFormat.CppHeader)

        assertTrue(script.contains("#pragma once"))
        assertTrue(script.contains("namespace bedrock_offsets {"))
        assertTrue(script.contains("inline constexpr std::uintptr_t Actor = 0x2010;"))
        assertFalse(script.contains("("))
    }

    @Test
    fun everyFormatIsNamedAndCarriesAnExtension() {
        ScriptFormat.entries.forEach { format ->
            assertTrue(format.label.isNotBlank())
            assertTrue(format.extension.isNotBlank())
            assertTrue(format.mimeType.contains("/"))
        }
    }
}
