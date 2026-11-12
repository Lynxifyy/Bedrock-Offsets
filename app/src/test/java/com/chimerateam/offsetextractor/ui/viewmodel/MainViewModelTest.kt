package com.chimerateam.offsetextractor.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.chimerateam.offsetextractor.data.cache.ScanCache
import com.chimerateam.offsetextractor.data.cache.ScanPreferences
import com.chimerateam.offsetextractor.data.native.BuildIdentity
import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.NativeLibraryMetadata
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.PatternSpec
import com.chimerateam.offsetextractor.data.native.ProgressCallback
import com.chimerateam.offsetextractor.data.native.ScanOutcome
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.data.native.SectionMap
import com.chimerateam.offsetextractor.data.native.SymbolReport
import com.chimerateam.offsetextractor.data.native.SymbolTable
import com.chimerateam.offsetextractor.data.native.TypeVTable
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.data.saf.ApkMetadata
import com.chimerateam.offsetextractor.data.saf.ApkOpenResult
import com.chimerateam.offsetextractor.data.saf.ApkSource
import com.chimerateam.offsetextractor.data.saf.DocumentWriter
import com.chimerateam.offsetextractor.data.saf.EngineErrorKind
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
import com.chimerateam.offsetextractor.data.saf.OffsetEngine
import com.chimerateam.offsetextractor.data.saf.SafApkAccess
import com.chimerateam.offsetextractor.data.saf.SafFailure
import com.chimerateam.offsetextractor.data.saf.WriteResult
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
                sizeBytes = 4096
            )
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
            state.result?.vtables?.entries?.get("ClientInstance")?.vtableHex
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
            100 to "Scan complete"
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
        assertTrue(
            "Matching signatures not logged: $messages",
            messages.any {
                it.contains("Matching signatures")
            }
        )
        assertTrue(
            "Verifying prologues not logged: $messages",
            messages.any {
                it.contains("Verifying prologues")
            }
        )
        assertTrue(
            "Scan complete not logged: $messages",
            messages.any { it.contains("Scan complete") }
        )
        // Each step is logged once, not once per percent point.
        assertEquals(1, messages.count { it.contains("Matching signatures") })
    }

    @Test
    fun missingSoFileProducesActionableError() = runTest(dispatcher) {
        val engine = FakeEngine(
            failure = EngineException(EngineErrorKind.MissingSoFile, "missing")
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
            failure = EngineException(EngineErrorKind.InvalidApk, "bad zip")
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
            openResult = ApkOpenResult.Failure(SafFailure.SecurityDenied, "denied")
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
            failure = EngineException(EngineErrorKind.UnsupportedArchitecture, "32-bit")
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
            collected.any { it.message.contains("zero-copy") }
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
            failure = EngineException(EngineErrorKind.InvalidApk, "bad")
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
            result = ExtractedNativeLibrary("/cache/libminecraftpe.so", "deadbeef", 2048)
        )
        val viewModel = newViewModel(engine = engine)

        val collected = mutableListOf<LogEntry>()
        val job = startCollector(this, viewModel, collected)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()
        job.cancel()

        assertTrue(
            "expected a SAF descriptor line",
            collected.any { it.tag == "SAF" && it.message.contains("File Descriptor") }
        )
        assertTrue(
            "expected the hash line",
            collected.any { it.tag == "RUST" && it.message.contains("Hash: deadbeef") }
        )
        assertTrue(
            "expected no error lines",
            collected.none { it.level == LogLevel.Error }
        )
    }

    @Test
    fun selectingAgainClearsTheStaleResultBeforeExtracting() = runTest(dispatcher) {
        val engine = FakeEngine(
            result = ExtractedNativeLibrary("/cache/libminecraftpe.so", "hash", 128)
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
        assertEquals(
            "the cached result must be reused",
            1,
            second.uiState.value.result?.patternHits?.size
        )
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
            viewModel.cacheSizeBytes.value > 0
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
    fun aSuccessfulScanRecordsTheBuildId() = runTest(dispatcher) {
        // The id is what the Settings screen shows, and what the next launch
        // uses to recognise a cached scan, so it must be published.
        val preferences = FakeScanPreferences()
        val viewModel = newViewModel(preferences = preferences)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals("abcdef0123456789", viewModel.lastBuildId.value)
    }

    @Test
    fun theLogIsCappedAndDropsTheOldestEntries() = runTest(dispatcher) {
        val viewModel = newViewModel()
        repeat(MAX_LOG_ENTRIES + 25) { index ->
            viewModel.onExportActionTaken("line $index")
        }

        val entries = viewModel.logEntries.value
        assertEquals(MAX_LOG_ENTRIES, entries.size)
        assertEquals("the oldest entries must be dropped", "line 25", entries.first().message)
        assertEquals("line ${MAX_LOG_ENTRIES + 24}", entries.last().message)
    }

    @Test
    fun clearingTheLogLeavesOnlyTheConfirmation() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onExportActionTaken("something")
        assertEquals(1, viewModel.logEntries.value.size)

        viewModel.clearLog()

        val entries = viewModel.logEntries.value
        assertEquals(1, entries.size)
        assertEquals("Log cleared.", entries.single().message)
    }

    @Test
    fun retainedLogEntriesCarryTheirTagAndLevel() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val entries = viewModel.logEntries.value
        assertTrue("expected a SAF line", entries.any { it.tag == "SAF" })
        assertTrue("expected a RUST line", entries.any { it.tag == "RUST" })
        assertTrue(
            "expected the timestamps to advance",
            entries.map { it.timestampMillis }.distinct().isNotEmpty()
        )
    }

    @Test
    fun aVerificationSummaryIsReportedOnTheLog() = runTest(dispatcher) {
        val engine = FakeEngine(
            scan = scanOutcome().copy(
                verification = VerificationSummary(
                    verified = 1,
                    conflicts = 0,
                    unverified = 0,
                    symbolsAvailable = true
                )
            )
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val entries = viewModel.logEntries.value
        assertTrue(
            "expected the verification line: ${entries.map { it.message }}",
            entries.any { it.message.contains("Offset verification complete") }
        )
        assertEquals(1, viewModel.uiState.value.result?.verification?.verified)
    }

    @Test
    fun aConflictIsLoggedAsAnError() = runTest(dispatcher) {
        val engine = FakeEngine(
            scan = scanOutcome().copy(
                verification = VerificationSummary(
                    verified = 0,
                    conflicts = 2,
                    unverified = 0,
                    symbolsAvailable = true
                )
            )
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val errors = viewModel.logEntries.value.filter { it.level == LogLevel.Error }
        assertTrue(
            "a conflict must be loud: ${errors.map { it.message }}",
            errors.any { it.message.contains("disagree with the symbol table") }
        )
    }

    @Test
    fun aStrippedLibrarySkipsVerificationWithoutAnError() = runTest(dispatcher) {
        val engine = FakeEngine(
            scan = scanOutcome().copy(
                verification = VerificationSummary(
                    verified = 0,
                    conflicts = 0,
                    unverified = 3,
                    symbolsAvailable = false
                )
            )
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val entries = viewModel.logEntries.value
        assertTrue(entries.any { it.message.contains("verification skipped") })
        assertTrue(entries.none { it.level == LogLevel.Error })
    }

    @Test
    fun aMissingTextSectionIsReportedNotCrashed() = runTest(dispatcher) {
        val engine = FakeEngine(
            failure = EngineException(EngineErrorKind.MissingSection, ".text is absent")
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals(ScannerPhase.Error, viewModel.uiState.value.phase)
        assertTrue(
            "expected a section message",
            viewModel.uiState.value.error?.contains("section") == true
        )
    }

    @Test
    fun aStorageFullFailureExplainsItself() = runTest(dispatcher) {
        val engine = FakeEngine(
            failure = EngineException(EngineErrorKind.StorageFull, "no space left")
        )
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val hint = viewModel.uiState.value.errorHint.orEmpty()
        assertTrue("expected a space hint, got: $hint", hint.contains("Free some space"))
    }

    @Test
    fun aFatalErrorFromTheEngineIsCaughtRatherThanCrashing() = runTest(dispatcher) {
        // A JNI-level failure can surface as an Error, not an Exception. The
        // ViewModel must survive it so the app stays usable.
        val engine = FakeEngine(failure = OutOfMemoryError("native allocation failed"))
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals(ScannerPhase.Error, viewModel.uiState.value.phase)
        assertTrue(
            "expected a memory message",
            viewModel.uiState.value.error?.contains("memory") == true
        )
    }

    @Test
    fun aVtableFailureStillYieldsTheOffsetScan() = runTest(dispatcher) {
        // The RTTI pass is the memory-hungry one. When it dies -- an
        // OutOfMemoryError is not an EngineException -- the offset scan it was
        // paired with is already complete and must still be reported, rather
        // than the whole scan being thrown away.
        val engine = FakeEngine(vtableFailure = OutOfMemoryError("relocation table"))
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ScannerPhase.Success, state.phase)
        assertNotNull("the offset scan must survive a failed vtable pass", state.result)
        assertEquals(1, state.result?.patternHits?.size)
        assertTrue(state.result?.vtables?.isEmpty != false)
        assertNull(state.error)
    }

    @Test
    fun rapidSelectionsOnlyRunTheLastScan() = runTest(dispatcher) {
        val engine = FakeEngine(delayMillis = 50)
        val viewModel = newViewModel(engine = engine)

        repeat(5) { viewModel.onApkSelected(uri) }
        advanceUntilIdle()

        // Each selection cancels the previous job, so the engine is entered
        // once per selection but only the last run reaches a terminal state.
        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertNotNull(viewModel.uiState.value.result)
    }

    @Test
    fun memoryPressureRaisesANoticeAndCanBeDismissed() = runTest(dispatcher) {
        val viewModel = newViewModel()
        assertTrue(!viewModel.uiState.value.lowMemory)

        viewModel.onMemoryPressure()

        assertTrue(viewModel.uiState.value.lowMemory)
        assertTrue(
            "the notice must be logged",
            viewModel.logEntries.value.any { it.message.contains("low on memory") }
        )

        viewModel.onLowMemoryNoticeDismissed()
        assertTrue(!viewModel.uiState.value.lowMemory)
    }

    @Test
    fun memoryPressureIsReportedOnlyOnce() = runTest(dispatcher) {
        val viewModel = newViewModel()

        repeat(3) { viewModel.onMemoryPressure() }

        val notices = viewModel.logEntries.value.count { it.message.contains("low on memory") }
        assertEquals("the notice must not repeat", 1, notices)
    }

    @Test
    fun stateSurvivesACollectorBeingReplaced() = runTest(dispatcher) {
        // A rotation replaces the UI collector while the ViewModel lives on, so
        // the result must be readable by the new collector without a rescan.
        val engine = FakeEngine()
        val viewModel = newViewModel(engine = engine)

        viewModel.onApkSelected(uri)
        advanceUntilIdle()

        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertEquals(1, engine.scanCalls)
        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertEquals(1, engine.scanCalls)
    }

    @Test
    fun aBatchScansEveryApkAndSummarisesTheRun() = runTest(dispatcher) {
        val access = FakeSafAccess(
            failFor = setOf("content://test.provider/second.apk")
        )
        val viewModel = newViewModel(access = access)

        viewModel.onApksSelected(
            listOf(
                Uri.parse("content://test.provider/first.apk"),
                Uri.parse("content://test.provider/second.apk"),
                Uri.parse("content://test.provider/third.apk")
            )
        )
        advanceUntilIdle()

        val summary = viewModel.uiState.value.batchSummary
        assertNotNull(summary)
        assertEquals(3, summary?.total)
        assertEquals(2, summary?.succeeded)
        assertEquals(1, summary?.failed)
        assertEquals("Processed 3 APKs. 2 succeeded, 1 failed.", summary?.message)
        assertEquals(3, access.openCalls)
    }

    @Test
    fun aBatchClearsItsProgressWhenItFinishes() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onApksSelected(
            listOf(
                Uri.parse("content://test.provider/first.apk"),
                Uri.parse("content://test.provider/second.apk")
            )
        )
        advanceUntilIdle()

        assertNull("the queue must be cleared once the run ends", viewModel.uiState.value.batch)
    }

    @Test
    fun aBatchNamesTheApkItIsScanning() = runTest(dispatcher) {
        val engine = FakeEngine(delayMillis = 50)
        val viewModel = newViewModel(engine = engine)

        viewModel.onApksSelected(
            listOf(
                Uri.parse("content://test.provider/first.apk"),
                Uri.parse("content://test.provider/second.apk")
            )
        )
        // Stop mid-run, while the first APK is still being scanned, so the
        // dialog state is observable rather than already cleared.
        runCurrent()

        val name = viewModel.uiState.value.batch?.currentName
        assertNotNull("the dialog must name the APK being scanned", name)
        assertTrue("unexpected name: $name", name.orEmpty().contains("minecraft.apk"))

        advanceUntilIdle()
    }

    @Test
    fun aBatchReportsOneFailureWithoutSinkingTheRun() = runTest(dispatcher) {
        val access = FakeSafAccess(
            failFor = setOf("content://test.provider/broken.apk")
        )
        val viewModel = newViewModel(access = access)

        viewModel.onApksSelected(
            listOf(
                Uri.parse("content://test.provider/broken.apk"),
                Uri.parse("content://test.provider/good.apk")
            )
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.batchSummary?.succeeded)
        assertTrue(
            "the run must be logged",
            viewModel.logEntries.value.any { it.message.contains("Processed 2 APKs") }
        )
    }

    @Test
    fun aSingleApkSelectionSkipsTheBatchQueue() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onApksSelected(listOf(uri))
        advanceUntilIdle()

        assertEquals(ScannerPhase.Success, viewModel.uiState.value.phase)
        assertNull("a single APK is not a batch", viewModel.uiState.value.batch)
        assertNull(viewModel.uiState.value.batchSummary)
    }

    @Test
    fun anEmptySelectionIsTreatedAsACancellation() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onApksSelected(emptyList())
        advanceUntilIdle()

        assertEquals(ScannerPhase.Idle, viewModel.uiState.value.phase)
    }

    @Test
    fun cancellingABatchClearsTheQueue() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val engine = FakeEngine(delayMillis = 500)
        val busy = newViewModel(engine = engine)

        busy.onApksSelected(
            listOf(
                Uri.parse("content://test.provider/first.apk"),
                Uri.parse("content://test.provider/second.apk")
            )
        )
        runCurrent()

        assertNotNull("the queue must be visible while running", busy.uiState.value.batch)

        busy.cancelBatch()
        advanceUntilIdle()

        assertNull(busy.uiState.value.batch)
        assertTrue(
            "the cancellation must be logged",
            busy.logEntries.value.any { it.message.contains("Batch cancelled") }
        )
        // A ViewModel with nothing running reports no batch to cancel.
        viewModel.cancelBatch()
        assertNull(viewModel.uiState.value.batch)
    }

    @Test
    fun dismissingTheBatchSummaryClearsIt() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onApksSelected(
            listOf(
                Uri.parse("content://test.provider/first.apk"),
                Uri.parse("content://test.provider/second.apk")
            )
        )
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.batchSummary)

        viewModel.onBatchSummaryShown()

        assertNull(viewModel.uiState.value.batchSummary)
    }

    @Test
    fun progressTrackerClampsOutOfRangeValues() {
        assertEquals(0, ProgressTracker.of(-5, "x").percent)
        assertEquals(100, ProgressTracker.of(250, "x").percent)
    }

    private fun startCollector(
        scope: CoroutineScope,
        viewModel: MainViewModel,
        into: MutableList<LogEntry>
    ): Job = scope.launch {
        viewModel.logs.collect { into.add(it) }
    }

    private fun newViewModel(
        engine: OffsetEngine = FakeEngine(),
        access: SafApkAccess = FakeSafAccess(),
        patterns: List<PatternSpec> = listOf(PatternSpec("prologue", "FD 7B BF A9")),
        writer: DocumentWriter = DocumentWriter { _, _ -> WriteResult.Success },
        cache: ScanCache? = null,
        preferences: ScanPreferences? = null
    ): MainViewModel = MainViewModel(
        safAccess = access,
        engine = engine,
        cacheDir = cacheDir,
        patterns = patterns,
        writer = writer,
        cache = cache,
        preferences = preferences,
        ioDispatcher = dispatcher
    )

    private fun newCache(): ScanCache = ScanCache(File(cacheDir, "scan-cache"))
}

/** The scan outcome the fake engine returns unless a test supplies its own. */
private fun scanOutcome(): ScanOutcome = ScanOutcome(
    section = ".text",
    scannedBytes = TEXT.size,
    chunks = 1,
    workers = 1,
    hits = listOf(
        PatternHit(
            name = "prologue",
            sectionOffset = 0,
            fileOffset = TEXT.offset,
            address = TEXT.address,
            addressHex = "0x400000"
        )
    ),
    peakResidentBytes = 32L * 1024 * 1024
)

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
    occupiesFile = true
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
    occupiesFile = true
)

/** Deterministic engine double with virtual-time delay and call counting. */
private class FakeEngine(
    private val result: ExtractedNativeLibrary = ExtractedNativeLibrary(
        path = "/cache/libminecraftpe.so",
        sha256 = "test",
        sizeBytes = 1
    ),
    private val failure: Throwable? = null,
    private val delayMillis: Long = 0,
    /** Scan outcome the double returns; `null` builds the default outcome. */
    private val scan: ScanOutcome? = null,
    /** Failure thrown by `resolveVtables` alone; `null` uses the default map. */
    private val vtableFailure: Throwable? = null
) : OffsetEngine {

    var calls = 0
        private set

    var mapCalls = 0
        private set

    override suspend fun extractNativeLibrary(rawFd: Int, destDir: File): ExtractedNativeLibrary {
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
            sections = listOf(TEXT, RODATA)
        )
    }

    var symbolCalls = 0
        private set

    var scanCalls = 0
        private set

    /** Progress reports the double replays to the ViewModel. */
    var progressReports: List<Pair<Int, String>> =
        listOf(0 to "Matching", 55 to "Verifying", 100 to "Done")

    var lastProgress: ProgressCallback? = null
        private set

    override suspend fun scanText(
        library: ExtractedNativeLibrary,
        patterns: List<PatternSpec>,
        progress: ProgressCallback?
    ): ScanOutcome {
        scanCalls++
        lastProgress = progress
        progressReports.forEach { (percent, message) -> progress?.onProgress(percent, message) }
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        failure?.let { throw it }
        return scan ?: ScanOutcome(
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
                    addressHex = "0x400000"
                )
            ),
            peakResidentBytes = 32L * 1024 * 1024
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
                typeInfoAddress = 0x403080
            )
        )
    )

    override suspend fun resolveVtables(library: ExtractedNativeLibrary): VtableMap {
        vtableCalls++
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        vtableFailure?.let { throw it }
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
                textSize = 0
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
                        binding = 1
                    )
                ),
                truncated = false
            )
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
            soname = "libminecraftpe.so"
        )
    }
}

/** In-memory [ScanPreferences] with observable writes. */
private class FakeScanPreferences : ScanPreferences {

    private val buildIds = kotlinx.coroutines.flow.MutableStateFlow("")
    private val rescan = kotlinx.coroutines.flow.MutableStateFlow(false)

    override val lastBuildId: kotlinx.coroutines.flow.Flow<String> = buildIds
    override val forceRescan: kotlinx.coroutines.flow.Flow<Boolean> = rescan

    override suspend fun setLastBuildId(buildId: String) {
        buildIds.value = buildId
    }

    override suspend fun setForceRescan(force: Boolean) {
        rescan.value = force
    }
}

/** Storage-access double returning a handle that records its own closure. */
private class FakeSafAccess(
    private val openResult: ApkOpenResult? = null,
    private val metadata: ApkMetadata = ApkMetadata(
        uri = Uri.parse("content://test.provider/minecraft.apk"),
        displayName = "minecraft.apk",
        sizeBytes = 1_024
    ),
    /** URIs that must fail to open, so a batch can contain a bad APK. */
    private val failFor: Set<String> = emptySet()
) : SafApkAccess {

    var lastHandle: FakeHandle? = null
        private set

    var openCalls = 0
        private set

    override fun queryMetadata(uri: Uri): ApkMetadata = metadata.copy(uri = uri)

    override fun openApk(uri: Uri): ApkOpenResult {
        openCalls++
        if (uri.toString() in failFor) {
            return ApkOpenResult.Failure(SafFailure.NotFound, "not found")
        }
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
