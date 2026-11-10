package com.chimerateam.offsetextractor.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.chimerateam.offsetextractor.data.saf.ApkMetadata
import com.chimerateam.offsetextractor.data.saf.ApkOpenResult
import com.chimerateam.offsetextractor.data.saf.ApkSource
import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
import com.chimerateam.offsetextractor.data.saf.OffsetEngine
import com.chimerateam.offsetextractor.data.cache.ScanCache
import com.chimerateam.offsetextractor.data.cache.ScanPreferences
import com.chimerateam.offsetextractor.data.saf.DocumentWriter
import com.chimerateam.offsetextractor.data.saf.SafApkAccess
import com.chimerateam.offsetextractor.data.saf.WriteResult
import com.chimerateam.offsetextractor.data.saf.SafFailure
import com.chimerateam.offsetextractor.data.native.NativeLibraryMetadata
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.data.native.BuildIdentity
import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.SectionMap
import com.chimerateam.offsetextractor.data.native.TypeVTable
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.PatternSpec
import com.chimerateam.offsetextractor.data.native.ProgressCallback
import com.chimerateam.offsetextractor.data.native.ScanOutcome
import com.chimerateam.offsetextractor.data.native.SymbolReport
import com.chimerateam.offsetextractor.data.native.SymbolTable
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.log.LogLevel
import com.chimerateam.offsetextractor.ui.state.ProgressTracker
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [MainViewModel] covering the success path, each failure class,
 * cancellation, retry and log tagging.
 *
 * The engine and storage-access layer are replaced by fakes, but the ViewModel
 * logic under test is the real implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val cacheDir: File =
        ApplicationProvider.getApplicationContext<Context>().cacheDir
    private val uri: Uri = Uri.parse("content://test.provider/minecraft.apk")

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun successfulExtractionReachesSuccessWithResult() = runTest(dispatcher) {
        val engine = FakeEngine(
            result = ExtractedNativeLibrary(
                path = "/cache/libminecraftpe.so",
                sha256 = "abc123",
                sizeBytes = 4096,
            ),
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Success, state.phase)
        assertEquals(100, state.progress)
        assertNotNull(state.result)
        assertEquals("abc123", state.result?.sha256)
        assertEquals(4096L, state.result?.sizeBytes)
        assertEquals("AArch64", state.result?.architecture)
        assertEquals("0x4000", state.result?.entryPoint)
        assertEquals(33, state.result?.sectionCount)
        assertEquals(0x400000L, state.result?.textSection?.address)
        assertEquals(0x7a0000L, state.result?.textSection?.size)
        assertEquals(0x3fd000L, state.result?.textSection?.delta)
        assertNull(state.error)
        assertEquals(1, engine.calls)
        assertEquals(1, engine.mapCalls)
        assertEquals(1, engine.sectionCalls)
        assertEquals(1, engine.symbolCalls)
        assertEquals(1, engine.scanCalls)
        assertEquals(1, state.result?.patternHits?.size)
        assertEquals("prologue", state.result?.patternHits?.first()?.name)
        assertEquals(0, state.result?.discardedMatches)
        assertEquals(32L * 1024 * 1024, state.result?.peakResidentBytes)
        assertEquals(1, engine.vtableCalls)
        assertNotNull("the ViewModel must pass a progress callback", engine.lastProgress)
        assertEquals("ClientInstance", state.result?.vtables?.entries?.keys?.first())
        assertEquals(
            "0x403000",
            state.result?.vtables?.entries?.get("ClientInstance")?.vtableHex,
        )
        assertEquals("abcdef0123456789", state.result?.buildId)
        assertEquals(1, state.result?.jniSymbolCount)
    }

    @Test
    fun progressReportsUpdateTheStateAndTheLog() = runTest(dispatcher) {
        val engine = FakeEngine()
        // Ten reports, the count the engine sends on a real scan.
        engine.progressReports = listOf(
            0 to "Matching signatures",
            7 to "Matching signatures",
            14 to "Matching signatures",
            21 to "Matching signatures",
            28 to "Matching signatures",
            35 to "Matching signatures",
            42 to "Matching signatures",
            49 to "Matching signatures",
            55 to "Verifying prologues",
            100 to "Scan complete",
        )
        val viewModel = newViewModel(engine = engine)
        val logs = mutableListOf<LogEntry>()
        val collector = launch { viewModel.logs.collect { logs += it } }

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        collector.cancel()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Success, state.phase)
        assertEquals(100, state.progress)
        assertEquals(10, engine.progressReports.size)
        val messages = logs.map { it.message }
        assertTrue("Matching signatures not logged: $messages", messages.any { it.contains("Matching signatures") })
        assertTrue("Verifying prologues not logged: $messages", messages.any { it.contains("Verifying prologues") })
        assertTrue("Scan complete not logged: $messages", messages.any { it.contains("Scan complete") })
        // Each step is logged once, not once per percent point.
        assertEquals(1, messages.count { it.contains("Matching signatures") })
    }

    @Test
    fun missingSoFileProducesActionableError() = runTest(dispatcher) {
        val engine = FakeEngine(
            failure = EngineException(EngineErrorKind.MissingSoFile, "missing"),
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Error, state.phase)
        assertTrue(state.error!!.contains("libminecraftpe.so"))
        assertNotNull(state.errorHint)
    }

    @Test
    fun unreadableArchiveIsReportedNotCrashed() = runTest(dispatcher) {
        val engine = FakeEngine(
            failure = EngineException(EngineErrorKind.InvalidApk, "bad zip"),
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Error, state.phase)
        assertTrue(state.error!!.contains("could not be parsed"))
    }

    @Test
    fun safFailureIsSurfacedWithoutStartingTheEngine() = runTest(dispatcher) {
        val engine = FakeEngine()
        val access = FakeSafAccess(
            openResult = ApkOpenResult.Failure(SafFailure.SecurityDenied, "denied"),
        )
        val viewModel = newViewModel(engine = engine, access = access)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Error, state.phase)
        assertTrue(state.error!!.contains("denied read access"))
        assertEquals(0, engine.calls)
    }

    @Test
    fun unsupportedArchitectureSurfacesAsAnError() = runTest(dispatcher) {
        val engine = FakeEngine(
            failure = EngineException(EngineErrorKind.UnsupportedArchitecture, "32-bit"),
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Error, state.phase)
        assertTrue(state.error!!.contains("Only arm64-v8a"))
    }

    @Test
    fun mappingLogLineIsEmittedAfterExtraction() = runTest(dispatcher) {
        val engine = FakeEngine()
        val viewModel = newViewModel(engine = engine)

        val collected = mutableListOf<LogEntry>()
        val job = startCollector(this, viewModel, collected)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        job.cancel()

        assertTrue(
            "expected the zero-copy mapping line",
            collected.any { it.message.contains("zero-copy") },
        )
    }

    @Test
    fun textSectionBoundariesAreLogged() = runTest(dispatcher) {
        val engine = FakeEngine()
        val viewModel = newViewModel(engine = engine)

        val collected = mutableListOf<LogEntry>()
        val job = startCollector(this, viewModel, collected)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        job.cancel()

        val boundary = collected.firstOrNull { it.message.contains("Section .text") }
        assertNotNull("expected a .text boundary line", boundary)
        assertTrue(boundary!!.message.contains("0x400000"))
        assertTrue(boundary.message.contains("0xba0000"))
    }

    @Test
    fun selectionCancelledWhileIdleReturnsToIdle() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onSelectionCancelled()
        advanceUntilIdle()

        assertEquals(ScannerPhase.Idle, viewModel.uiState.value.phase)
    }

    @Test
    fun unexpectedEngineThrowableStillYieldsAnErrorState() = runTest(dispatcher) {
        val engine = FakeEngine(failure = IllegalStateException("boom"))
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Error, state.phase)
        assertTrue(state.error!!.contains("Unexpected failure"))
    }

    @Test
    fun cancelDuringExtractionReleasesTheDescriptorAndResets() = runTest(dispatcher) {
        val engine = FakeEngine(delayMillis = 10_000)
        val access = FakeSafAccess()
        val viewModel = newViewModel(engine = engine, access = access)

        viewModel.onApkSelected(uri)
        // Run only the work that is ready now, without advancing past the
        // engine's delay, so the scan is still in flight.
        runCurrent()
        assertEquals(ScannerPhase.Extracting, viewModel.uiState.value.phase)

        viewModel.cancel()
        advanceUntilIdle()

        assertEquals(ScannerPhase.Idle, viewModel.uiState.value.phase)
        assertTrue("descriptor must be released", access.lastHandle!!.closed)
    }

    @Test
    fun retryReturnsToIdleAndClearsTheError() = runTest(dispatcher) {
        val engine = FakeEngine(
            failure = EngineException(EngineErrorKind.InvalidApk, "bad"),
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        assertEquals(ScannerPhase.Error, viewModel.uiState.value.phase)

        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Idle, state.phase)
        assertNull(state.error)
        assertNull(state.errorHint)
    }

    @Test
    fun logsCarryTagsAndTheHashLineIsEmitted() = runTest(dispatcher) {
        val engine = FakeEngine(
            result = ExtractedNativeLibrary("/cache/libminecraftpe.so", "deadbeef", 2048),
        )
        val viewModel = newViewModel(engine = engine)

        val collected = mutableListOf<LogEntry>()
        val job = startCollector(this, viewModel, collected)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        job.cancel()

        assertTrue(
            "expected a SAF descriptor line",
            collected.any { it.tag == "SAF" && it.message.contains("File Descriptor") },
        )
        assertTrue(
            "expected the hash line",
            collected.any { it.tag == "RUST" && it.message.contains("Hash: deadbeef") },
        )
        assertTrue(
            "expected no error lines",
            collected.none { it.level == LogLevel.Error },
        )
    }

    @Test
    fun selectingAgainClearsTheStaleResultBeforeExtracting() = runTest(dispatcher) {
        val engine = FakeEngine(
            result = ExtractedNativeLibrary("/cache/libminecraftpe.so", "hash", 128),
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.result)

        viewModel.onApkSelected(uri)
        // The second extraction has not run yet, so no stale result may remain.
        assertNull("stale result must be cleared", viewModel.uiState.value.result)
    }

    @Test
    fun errorIsAlsoReportedOnTheLogStream() = runTest(dispatcher) {
        val engine = FakeEngine(failure = EngineException(EngineErrorKind.InvalidApk, "bad"))
        val viewModel = newViewModel(engine = engine)

        viewModel.logs.test {
            viewModel.onApkSelected(uri)
            advanceUntilIdle()

            val errors = mutableListOf<LogEntry>()
            while (true) {
                val next = awaitItem()
                if (next.level == LogLevel.Error) errors.add(next)
                if (next.tag == "RUST" && next.level == LogLevel.Error) break
            }
            assertTrue(errors.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSuccessfulExportWritesTheDocumentAndReportsIt() = runTest(dispatcher) {
        var writtenUri: android.net.Uri? = null
        var writtenText = ""
        val writer = DocumentWriter { uri, text ->
            writtenUri = uri
            writtenText = text
            WriteResult.Success
        }
        val viewModel = newViewModel(writer = writer)
        val logs = mutableListOf<LogEntry>()
        val collector = startCollector(this, viewModel, logs)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val destination = android.net.Uri.parse("content://downloads/offsets.json")
        viewModel.onExportDestinationChosen(destination)
        advanceUntilIdle()
        collector.cancel()

        assertEquals(destination, writtenUri)
        assertTrue("expected the JSON document", writtenText.contains("schemaVersion"))
        assertEquals("Offsets exported.", viewModel.uiState.value.exportMessage)
        assertTrue(logs.any { it.message.startsWith("Exported offsets to") })
    }

    @Test
    fun aDismissedSaveDialogIsNotAnError() = runTest(dispatcher) {
        var writeCalls = 0
        val writer = DocumentWriter { _, _ ->
            writeCalls++
            WriteResult.Success
        }
        val viewModel = newViewModel(writer = writer)
        val logs = mutableListOf<LogEntry>()
        val collector = startCollector(this, viewModel, logs)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        viewModel.onExportDestinationChosen(null)
        advanceUntilIdle()
        collector.cancel()

        assertEquals(0, writeCalls)
        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.exportMessage)
        assertTrue(logs.any { it.message.contains("Export cancelled") })
    }

    @Test
    fun aFailedExportSurfacesTheReasonAndKeepsTheResult() = runTest(dispatcher) {
        val writer = DocumentWriter { _, _ -> WriteResult.Failure("Storage is full.") }
        val viewModel = newViewModel(writer = writer)
        val logs = mutableListOf<LogEntry>()
        val collector = startCollector(this, viewModel, logs)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        viewModel.onExportDestinationChosen(android.net.Uri.parse("content://x/y.json"))
        advanceUntilIdle()
        collector.cancel()

        assertEquals("Storage is full.", viewModel.uiState.value.exportMessage)
        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertNotNull("the result must survive a failed export", viewModel.uiState.value.result)
        assertTrue(logs.any { it.level == LogLevel.Error })
    }

    @Test
    fun theExportMessageIsClearedOnceShown() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        viewModel.onExportDestinationChosen(android.net.Uri.parse("content://x/y.json"))
        advanceUntilIdle()
        assertEquals("Offsets exported.", viewModel.uiState.value.exportMessage)

        viewModel.onExportMessageShown()
        assertNull(viewModel.uiState.value.exportMessage)
    }

    @Test
    fun theDefaultExportNameIsKeyedByBuildId() = runTest(dispatcher) {
        val viewModel = newViewModel()
        assertNull("no name before a scan", viewModel.defaultExportFileName())

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals("offsets_abcdef0123456789.json", viewModel.defaultExportFileName())
    }

    @Test
    fun aSecondScanOfTheSameBuildLoadsFromTheCache() = runTest(dispatcher) {
        val cache = newCache()
        val first = newViewModel(cache = cache)
        first.onApkSelected(uri)
        advanceUntilIdle()
        assertEquals(1, first.uiState.value.result?.patternHits?.size)

        val engine = FakeEngine()
        val second = newViewModel(engine = engine, cache = cache)
        second.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals(ScannerPhase.Success, second.uiState.value.phase)
        assertEquals("a cache hit must skip the scan", 0, engine.scanCalls)
        assertEquals("the cached result must be reused", 1, second.uiState.value.result?.patternHits?.size)
    }

    @Test
    fun forceRescanIgnoresTheCache() = runTest(dispatcher) {
        val cache = newCache()
        newViewModel(cache = cache).onApkSelected(uri)
        advanceUntilIdle()

        val engine = FakeEngine()
        val viewModel = newViewModel(engine = engine, cache = cache)
        viewModel.setForceRescan(true)
        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals("a forced scan must run the engine", 1, engine.scanCalls)
    }

    @Test
    fun clearingTheCacheRemovesStoredDocuments() = runTest(dispatcher) {
        val cache = newCache()
        val viewModel = newViewModel(cache = cache)
        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        assertTrue("expected a stored document", cache.totalSizeBytes() > 0)

        viewModel.clearCache()
        advanceUntilIdle()

        assertEquals(0L, cache.totalSizeBytes())
        assertEquals(0L, viewModel.cacheSizeBytes.value)
    }

    @Test
    fun theReportedCacheSizeGrowsAfterAScan() = runTest(dispatcher) {
        val cache = newCache()
        val viewModel = newViewModel(cache = cache)
        assertEquals(0L, viewModel.cacheSizeBytes.value)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertTrue(
            "the cache size must reflect the stored document",
            viewModel.cacheSizeBytes.value > 0,
        )
    }

    @Test
    fun aCorruptCacheEntryFallsBackToScanning() = runTest(dispatcher) {
        val cache = newCache()
        cache.ensureDirectories()
        cache.writeDocument("abcdef0123456789", "{ not json")

        val engine = FakeEngine()
        val viewModel = newViewModel(engine = engine, cache = cache)
        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertEquals("a corrupt entry must not be trusted", 1, engine.scanCalls)
    }

    @Test
    fun aScanWithoutACacheStillSucceeds() = runTest(dispatcher) {
        val engine = FakeEngine()
        val viewModel = newViewModel(engine = engine, cache = null)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertEquals(1, engine.scanCalls)
        assertEquals(0L, viewModel.cacheSizeBytes.value)
    }

    @Test
    fun progressTrackerClampsOutOfRangeValues() {
        assertEquals(0, ProgressTracker.of(-5, "x").percent)
        assertEquals(100, ProgressTracker.of(250, "x").percent)
    }

    private fun startCollector(
        scope: CoroutineScope,
        viewModel: MainViewModel,
        into: MutableList<LogEntry>,
    ): Job = scope.launch {
        viewModel.logs.collect { into.add(it) }
    }

    private fun newViewModel(
        engine: OffsetEngine = FakeEngine(),
        access: SafApkAccess = FakeSafAccess(),
        patterns: List<PatternSpec> = listOf(PatternSpec("prologue", "FD 7B BF A9")),
        writer: DocumentWriter = DocumentWriter { _, _ -> WriteResult.Success },
        cache: ScanCache? = null,
        preferences: ScanPreferences? = null,
    ): MainViewModel = MainViewModel(
        safAccess = access,
        engine = engine,
        cacheDir = cacheDir,
        patterns = patterns,
        writer = writer,
        cache = cache,
        preferences = preferences,
        ioDispatcher = dispatcher,
    )

    private fun newCache(): ScanCache = ScanCache(File(cacheDir, "scan-cache"))
}

/** The `.text` section used by the fake engine. */
private val TEXT = SectionInfo(
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
    occupiesFile = true,
)

/** The `.rodata` section used by the fake engine. */
private val RODATA = SectionInfo(
    name = ".rodata",
    kind = "rodata",
    address = 0xba0000,
    size = 0x2000,
    offset = 0x7a3000,
    align = 16,
    delta = 0x3fd000,
    allocated = true,
    executable = false,
    writable = false,
    occupiesFile = true,
)

/** Deterministic engine double with virtual-time delay and call counting. */
private class FakeEngine(
    private val result: ExtractedNativeLibrary = ExtractedNativeLibrary(
        path = "/cache/libminecraftpe.so",
        sha256 = "test",
        sizeBytes = 1,
    ),
    private val failure: Throwable? = null,
    private val delayMillis: Long = 0,
) : OffsetEngine {

    var calls = 0
        private set

    var mapCalls = 0
        private set

    override suspend fun extractNativeLibrary(
        rawFd: Int,
        destDir: File,
    ): ExtractedNativeLibrary {
        calls++
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        failure?.let { throw it }
        return result
    }

    var sectionCalls = 0
        private set

    override suspend fun describeSections(library: ExtractedNativeLibrary): SectionMap {
        sectionCalls++
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        failure?.let { throw it }
        return SectionMap(
            imageBase = TEXT.address,
            text = TEXT,
            sections = listOf(TEXT, RODATA),
        )
    }

    var symbolCalls = 0
        private set

    var scanCalls = 0
        private set

    /** Progress reports the double replays to the ViewModel. */
    var progressReports: List<Pair<Int, String>> = listOf(0 to "Matching", 55 to "Verifying", 100 to "Done")

    var lastProgress: ProgressCallback? = null
        private set

    override suspend fun scanText(
        library: ExtractedNativeLibrary,
        patterns: List<PatternSpec>,
        progress: ProgressCallback?,
    ): ScanOutcome {
        scanCalls++
        lastProgress = progress
        progressReports.forEach { (percent, message) -> progress?.onProgress(percent, message) }
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        failure?.let { throw it }
        return ScanOutcome(
            section = ".text",
            scannedBytes = TEXT.size,
            chunks = 1,
            workers = 1,
            hits = listOf(
                PatternHit(
                    name = patterns.firstOrNull()?.name ?: "unknown",
                    sectionOffset = 0,
                    fileOffset = TEXT.offset,
                    address = TEXT.address,
                    addressHex = "0x400000",
                ),
            ),
            peakResidentBytes = 32L * 1024 * 1024,
        )
    }

    var vtableCalls = 0
        private set

    /** Vtable map the double returns; set to `null` to simulate a failure. */
    var vtableResult: VtableMap? = VtableMap(
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
                typeInfoAddress = 0x403080,
            ),
        ),
    )

    override suspend fun resolveVtables(library: ExtractedNativeLibrary): VtableMap {
        vtableCalls++
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        failure?.let { throw it }
        return vtableResult ?: throw EngineException(EngineErrorKind.InvalidElf, "no vtables")
    }

    override suspend fun describeSymbols(library: ExtractedNativeLibrary): SymbolReport {
        symbolCalls++
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        failure?.let { throw it }
        return SymbolReport(
            identity = BuildIdentity(
                buildId = "abcdef0123456789",
                source = "note",
                textSize = 0,
            ),
            symbols = SymbolTable(
                total = 42,
                defined = 7,
                jni = listOf(
                    ExportedSymbol(
                        name = "Java_com_example_NativeLib_start",
                        address = 0x410000,
                        addressHex = "0x410000",
                        size = 32,
                        binding = 1,
                    ),
                ),
                truncated = false,
            ),
        )
    }

    override suspend fun mapLibrary(library: ExtractedNativeLibrary): NativeLibraryMetadata {
        mapCalls++
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        failure?.let { throw it }
        return NativeLibraryMetadata(
            path = library.path,
            sha256 = library.sha256,
            sizeBytes = library.sizeBytes,
            architecture = "AArch64",
            bits = 64,
            endianness = "little",
            elfType = "DYN",
            entryPoint = "0x4000",
            sectionCount = 33,
            programHeaderCount = 9,
            soname = "libminecraftpe.so",
        )
    }
}

/** Storage-access double returning a handle that records its own closure. */
private class FakeSafAccess(
    private val openResult: ApkOpenResult? = null,
    private val metadata: ApkMetadata = ApkMetadata(
        uri = Uri.parse("content://test.provider/minecraft.apk"),
        displayName = "minecraft.apk",
        sizeBytes = 1_024,
    ),
) : SafApkAccess {

    var lastHandle: FakeHandle? = null
        private set

    override fun queryMetadata(uri: Uri): ApkMetadata = metadata

    override fun openApk(uri: Uri): ApkOpenResult {
        openResult?.let { return it }
        val handle = FakeHandle(metadata)
        lastHandle = handle
        return ApkOpenResult.Success(handle, usedCacheCopy = false)
    }
}

/** Minimal [ApkSource] that records closure. */
private class FakeHandle(override val metadata: ApkMetadata) : ApkSource {
    override val rawFd: Int = 42
    override val lengthBytes: Long = metadata.sizeBytes
    override val usedCacheCopy: Boolean = false

    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
