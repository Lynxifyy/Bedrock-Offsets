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
import com.chimerateam.offsetextractor.data.saf.SafApkAccess
import com.chimerateam.offsetextractor.data.saf.SafFailure
import com.chimerateam.offsetextractor.data.native.NativeLibraryMetadata
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
        assertNull(state.error)
        assertEquals(1, engine.calls)
        assertEquals(1, engine.mapCalls)
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
        engine: OffsetEngine,
        access: SafApkAccess = FakeSafAccess(),
    ): MainViewModel = MainViewModel(
        safAccess = access,
        engine = engine,
        cacheDir = cacheDir,
        ioDispatcher = dispatcher,
    )
}

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
