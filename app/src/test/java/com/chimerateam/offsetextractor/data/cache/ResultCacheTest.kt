package com.chimerateam.offsetextractor.data.cache

import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.data.native.TypeVTable
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Verifies a stored scan round-trips and that a bad entry is a miss, not a crash. */
class ResultCacheTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val cacheDir: File get() = temporary.newFolder("cache")

    private val summary = ScanResultSummary(
        displayName = "minecraft.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        textSection = SectionInfo(
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
        ),
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
        expectedPatterns = listOf("clientInstance")
    )

    @Test
    fun aStoredScanRoundTripsWithEveryFieldIntact() {
        val store = ResultCache(ScanCache(cacheDir))

        assertTrue(store.store(summary))
        val loaded = store.load(summary.buildId)

        assertNotNull(loaded)
        assertEquals(summary.buildId, loaded?.buildId)
        assertEquals(summary.displayName, loaded?.displayName)
        assertEquals(summary.textSection?.delta, loaded?.textSection?.delta)
        assertEquals(summary.patternHits, loaded?.patternHits)
        assertEquals(summary.discardedMatches, loaded?.discardedMatches)
        assertEquals(summary.vtables.entries.keys, loaded?.vtables?.entries?.keys)
        assertEquals(summary.symbols, loaded?.symbols)
        assertEquals(summary.expectedPatterns, loaded?.expectedPatterns)
    }

    @Test
    fun anUnknownBuildIdIsAMiss() {
        val store = ResultCache(ScanCache(cacheDir))

        assertNull(store.load("nothing-here"))
    }

    @Test
    fun aSummaryWithoutABuildIdIsNotStored() {
        val store = ResultCache(ScanCache(cacheDir))

        assertTrue(!store.store(summary.copy(buildId = "")))
        assertNull(store.load(""))
    }

    @Test
    fun aCorruptDocumentIsAMiss() {
        val scanCache = ScanCache(cacheDir)
        scanCache.writeDocument("id", "{ not json")
        val store = ResultCache(scanCache)

        assertNull(store.load("id"))
    }

    @Test
    fun aDocumentStoredForAnotherBuildIsAMiss() {
        val scanCache = ScanCache(cacheDir)
        scanCache.writeDocument("id", """{"schemaVersion":1,"summary":{"buildId":"other"}}""")
        val store = ResultCache(scanCache)

        assertNull(store.load("id"))
    }

    @Test
    fun anOlderSchemaVersionIsAMiss() {
        val scanCache = ScanCache(cacheDir)
        scanCache.writeDocument("id", """{"schemaVersion":0,"summary":{"buildId":"id"}}""")
        val store = ResultCache(scanCache)

        assertNull("an outdated entry must force a rescan", store.load("id"))
    }
}
