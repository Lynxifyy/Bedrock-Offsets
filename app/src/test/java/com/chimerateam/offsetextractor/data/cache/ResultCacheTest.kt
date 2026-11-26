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
        expectedPatterns = listOf("clientInstance"),
        vtablesResolved = true,
        internalResolved = true,
        internal = com.chimerateam.offsetextractor.data.native.InternalSummary(
            classes = 1,
            vtableSlots = 2,
            functions = 3,
            named = 1,
            path = "/cache/internal/abc.ndjson"
        ),
        apkSizeBytes = 8192,
        apkVersion = "1.21.50"
    )

    @Test
    fun aStoredScanRoundTripsWithEveryFieldIntact() {
        val store = ResultCache(ScanCache(cacheDir))

        assertTrue(store.store(summary))
        val loaded = store.load(summary.buildId, summary.engineVersion, summary.imageBase)

        assertNotNull(loaded)
        assertEquals(summary.buildId, loaded?.buildId)
        assertEquals(summary.displayName, loaded?.displayName)
        assertEquals(summary.textSection?.delta, loaded?.textSection?.delta)
        assertEquals(summary.patternHits, loaded?.patternHits)
        assertEquals(summary.discardedMatches, loaded?.discardedMatches)
        assertEquals(summary.vtables.entries.keys, loaded?.vtables?.entries?.keys)
        assertEquals(summary.symbols, loaded?.symbols)
        assertEquals(summary.expectedPatterns, loaded?.expectedPatterns)
        assertEquals(true, loaded?.vtablesResolved)
        assertEquals(true, loaded?.internalResolved)
        assertEquals(summary.internal?.path, loaded?.internal?.path)
        assertEquals(summary.apkSizeBytes, loaded?.apkSizeBytes)
        assertEquals(summary.apkVersion, loaded?.apkVersion)
    }

    @Test
    fun anUnknownBuildIdIsAMiss() {
        val store = ResultCache(ScanCache(cacheDir))

        assertNull(store.load("nothing-here", "1.0.12", 0))
    }

    @Test
    fun aResultIsNotReusedWhenTheLoadBaseChanges() {
        // The 26.60 fix moved the base from the first allocated section
        // (0x2463f0) to the first PT_LOAD (0). A device that cached a scan
        // before that fix would otherwise keep serving the shifted offsets.
        val store = ResultCache(ScanCache(cacheDir))
        assertTrue(store.store(summary.copy(imageBase = 0)))

        assertNull(
            "an entry derived from another base must be a miss",
            store.load(summary.buildId, summary.engineVersion, 0x2463f0)
        )
        assertNotNull(store.load(summary.buildId, summary.engineVersion, 0))
    }

    @Test
    fun aResultIsNotReusedByAnotherEngineVersion() {
        val store = ResultCache(ScanCache(cacheDir))
        assertTrue(store.store(summary.copy(engineVersion = "1.0.12")))

        assertNull(store.load(summary.buildId, "1.0.13", summary.imageBase))
        assertNotNull(store.load(summary.buildId, "1.0.12", summary.imageBase))
    }

    @Test
    fun theCacheKeyCarriesBuildEngineAndBase() {
        val key = ResultCache.keyFor("410ac215", "1.0.12", 0)

        assertEquals("410ac215-e1_0_12-b0", key)
        assertEquals(
            "different engines must not collide",
            false,
            ResultCache.keyFor("410ac215", "1.0.12", 0) ==
                ResultCache.keyFor("410ac215", "10.1.2", 0)
        )
    }

    @Test
    fun aSummaryWithoutABuildIdIsNotStored() {
        val store = ResultCache(ScanCache(cacheDir))

        assertTrue(!store.store(summary.copy(buildId = "")))
        assertNull(store.load("", "", 0))
    }

    @Test
    fun aCorruptDocumentIsAMiss() {
        val scanCache = ScanCache(cacheDir)
        scanCache.writeDocument(ResultCache.keyFor("id", "1.0.12", 0), "{ not json")
        val store = ResultCache(scanCache)

        assertNull(store.load("id", "1.0.12", 0))
    }

    @Test
    fun aDocumentStoredForAnotherBuildIsAMiss() {
        val scanCache = ScanCache(cacheDir)
        scanCache.writeDocument(
            ResultCache.keyFor("id", "1.0.12", 0),
            """{"schemaVersion":1,"summary":{"buildId":"other"}}"""
        )
        val store = ResultCache(scanCache)

        assertNull(store.load("id", "1.0.12", 0))
    }

    @Test
    fun anOlderSchemaVersionIsAMiss() {
        val scanCache = ScanCache(cacheDir)
        scanCache.writeDocument(
            ResultCache.keyFor("id", "1.0.12", 0),
            """{"schemaVersion":0,"summary":{"buildId":"id"}}"""
        )
        val store = ResultCache(scanCache)

        assertNull(
            "an outdated entry must force a rescan",
            store.load("id", "1.0.12", 0)
        )
    }
}
