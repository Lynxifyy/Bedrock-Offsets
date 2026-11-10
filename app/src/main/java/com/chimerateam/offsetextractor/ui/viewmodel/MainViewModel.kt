package com.chimerateam.offsetextractor.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chimerateam.offsetextractor.data.saf.ApkOpenResult
import com.chimerateam.offsetextractor.data.saf.ApkSource
import com.chimerateam.offsetextractor.data.native.PatternSpec
import com.chimerateam.offsetextractor.data.native.ProgressCallback
import com.chimerateam.offsetextractor.data.native.ScanOutcome
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.data.saf.OffsetEngine
import com.chimerateam.offsetextractor.data.saf.SafApkAccess
import com.chimerateam.offsetextractor.ui.error.ErrorMapper
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.log.Logs
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.state.ScannerUiState
import com.chimerateam.offsetextractor.data.saf.EngineException
import com.chimerateam.offsetextractor.util.formatBytes
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Origin tag used for lines the ViewModel itself emits. */
private const val TAG_UI = "UI"

/** Origin tag used for lines describing engine work. */
private const val TAG_RUST = "RUST"

/** Number of resolved vtables written to the live log. */
private const val MAX_LOGGED_VTABLES = 10

/** Origin tag used for storage-access lines. */
private const val TAG_SAF = "SAF"

/**
 * Drives APK selection, native library extraction and scan progress.
 *
 * All file work runs on [Dispatchers.IO] under a [SupervisorJob], so a failed
 * extraction surfaces as an error state instead of tearing down the ViewModel
 * or the rest of the app.
 *
 * @param safAccess storage-access implementation used to open APKs.
 * @param engine extraction engine, JNI-backed in production.
 * @param cacheDir directory that receives extracted native libraries.
 * @param patterns signatures the scanner looks for; empty skips the scan.
 * @param ioDispatcher dispatcher for all blocking file work; overridden in
 *   tests so virtual time can drive the coroutines.
 */
class MainViewModel(
    private val safAccess: SafApkAccess,
    private val engine: OffsetEngine,
    private val cacheDir: File,
    private val patterns: List<PatternSpec> = emptyList(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val supervisor = SupervisorJob(viewModelScope.coroutineContext[Job])

    private val _uiState = MutableStateFlow(ScannerUiState())
    /** Current scanner snapshot. */
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _logs = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = LOG_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Live-log stream; buffered so a slow collector cannot block the engine. */
    val logs: SharedFlow<LogEntry> = _logs.asSharedFlow()

    private var activeApk: ApkSource? = null
    private var extractionJob: Job? = null

    /**
     * Opens [uri] and starts extracting its native library.
     *
     * Re-selecting while a scan is running cancels the previous job first, so
     * rapid taps cannot open several descriptors at once.
     *
     * @param uri document URI returned by the SAF picker.
     */
    fun onApkSelected(uri: android.net.Uri) {
        extractionJob?.cancel()

        // Fail-closed: clear any previous result before the new work starts so
        // the UI never shows offsets belonging to a different APK.
        _uiState.update {
            it.copy(
                phase = ScannerPhase.Extracting,
                progress = 0,
                progressTask = "Opening APK",
                result = null,
                error = null,
                errorHint = null,
            )
        }

        extractionJob = viewModelScope.launch(supervisor + ioDispatcher) {
            emit(Logs.info(TAG_SAF, "Opening selected APK..."))

            val metadata = safAccess.queryMetadata(uri)
            when (val opened = safAccess.openApk(uri)) {
                is ApkOpenResult.Failure -> {
                    val mapped = ErrorMapper.fromSaf(opened.reason, metadata.displayName)
                    fail(mapped.message, mapped.hint, tag = TAG_SAF)
                    return@launch
                }

                is ApkOpenResult.Success -> {
                    releaseActiveApk()
                    activeApk = opened.handle
                    emit(Logs.info(TAG_SAF, "Opened APK File Descriptor successfully."))
                    emit(
                        Logs.info(
                            TAG_SAF,
                            "${metadata.displayName}: ${opened.handle.lengthBytes} bytes" +
                                if (opened.usedCacheCopy) " (streamed cache copy)" else "",
                        ),
                    )
                    _uiState.update {
                        it.copy(
                            phase = ScannerPhase.Extracting,
                            progress = 0,
                            progressTask = "Extracting native library",
                            selectedApk = metadata,
                            usedCacheCopy = opened.usedCacheCopy,
                            result = null,
                            error = null,
                            errorHint = null,
                        )
                    }
                    extractNativeLibrary(opened.handle)
                }
            }
        }
    }

    /** Cancels any running extraction and releases the open descriptor. */
    fun cancel() {
        val job = extractionJob
        extractionJob = null
        job?.cancel()
        releaseActiveApk()
        emit(Logs.warn(TAG_UI, "Cancelled; descriptor released."))
        _uiState.update {
            ScannerUiState(phase = ScannerPhase.Idle)
        }
    }

    /**
     * Records that an export action ran, so the log shows what the user did.
     *
     * @param message short description of the action that completed.
     */
    fun onExportActionTaken(message: String) {
        emit(Logs.info(TAG_UI, message))
    }

    /** Records that the user dismissed the picker without choosing a file. */
    fun onSelectionCancelled() {
        emit(Logs.info(TAG_SAF, "Selection cancelled."))
        if (activeApk == null) {
            _uiState.update {
                it.copy(phase = ScannerPhase.Idle, progress = 0, progressTask = "")
            }
        }
    }

    /** Clears the error state and returns the scanner to idle. */
    fun retry() {
        _uiState.update {
            it.copy(phase = ScannerPhase.Idle, error = null, errorHint = null, progress = 0)
        }
        emit(Logs.info(TAG_UI, "Ready to try again."))
    }

    override fun onCleared() {
        super.onCleared()
        extractionJob = null
        releaseActiveApk()
    }

    private suspend fun extractNativeLibrary(source: ApkSource) {
        try {
            val extracted = engine.extractNativeLibrary(source.rawFd, cacheDir)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Streaming extraction complete. Hash: ${extracted.sha256}",
                ),
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Extracted ${extracted.path.substringAfterLast('/')} " +
                        "(${extracted.sizeBytes} bytes)",
                ),
            )

            _uiState.update {
                it.copy(
                    phase = ScannerPhase.Scanning,
                    progress = 100,
                    progressTask = "Mapping native library",
                )
            }

            val mapped = engine.mapLibrary(extracted)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Mapped ${mapped.sizeBytes} byte .so file with zero-copy. " +
                        "Arch: ${mapped.architecture}.",
                ),
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Entry point ${mapped.entryPoint}, ${mapped.sectionCount} sections",
                ),
            )

            val sections = engine.describeSections(extracted)
            val text = sections.text
            emit(
                Logs.info(
                    TAG_RUST,
                    "Section ${text.name} [${text.hex(text.address)}.." +
                        "${text.hex(text.address + text.size)}] file " +
                        "${text.hex(text.offset)} delta ${text.hex(text.delta)}",
                ),
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Image base ${sections.hex(sections.imageBase)}, " +
                        "${sections.sections.size} sections mapped",
                ),
            )

            val symbols = engine.describeSymbols(extracted)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Build ID extracted. Found ${symbols.symbols.jni.size} dynamic symbols.",
                ),
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Build ID ${symbols.identity.buildId} " +
                        "(${symbols.identity.source}, ${symbols.symbols.defined} defined)",
                ),
            )

            val scan = scanForOffsets(extracted)
            val vtables = resolveVtables(extracted)

            _uiState.update {
                it.copy(
                    phase = ScannerPhase.Success,
                    progress = 100,
                    progressTask = "Mapping complete",
                    result = ScanResultSummary(
                        displayName = source.metadata.displayName,
                        sha256 = extracted.sha256,
                        sizeBytes = extracted.sizeBytes,
                        architecture = mapped.architecture,
                        entryPoint = mapped.entryPoint,
                        sectionCount = mapped.sectionCount,
                        textSection = text,
                        buildId = symbols.identity.buildId,
                        buildIdSource = symbols.identity.source,
                        jniSymbolCount = symbols.symbols.jni.size,
                        patternHits = scan?.hits.orEmpty(),
                        discardedMatches = scan?.discarded ?: 0,
                        peakResidentBytes = scan?.peakResidentBytes,
                        vtables = vtables,
                        symbols = symbols.symbols.jni,
                        expectedPatterns = patterns.map { it.name },
                    ),
                    error = null,
                    errorHint = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val mapped = ErrorMapper.fromThrowable(throwable, source.metadata.displayName)
            fail(mapped.message, mapped.hint, tag = TAG_RUST)
        }
    }

    /**
     * Resolves the RTTI vtables the library declares.
     *
     * A library with no C++ RTTI yields an empty map, which is reported rather
     * than treated as a failure: the offset scan is still useful on its own.
     *
     * @param extracted the library to inspect.
     * @return the resolved vtables, or an empty map when the pass fails.
     */
    private suspend fun resolveVtables(
        extracted: com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary,
    ): VtableMap = try {
        val map = engine.resolveVtables(extracted)
        if (map.isEmpty) {
            emit(Logs.info(TAG_RUST, "No RTTI vtables present in this library."))
        } else {
            emit(
                Logs.info(
                    TAG_RUST,
                    "Resolved ${map.size} RTTI vtables from ${map.source}.",
                ),
            )
            map.entries.values.take(MAX_LOGGED_VTABLES).forEach { vtable ->
                emit(
                    Logs.info(
                        TAG_RUST,
                        "Resolved ${vtable.name} vtable at ${vtable.vtableHex}",
                    ),
                )
            }
        }
        map
    } catch (failure: EngineException) {
        emit(Logs.warn(TAG_RUST, "Vtable resolution skipped: ${failure.message}"))
        VtableMap.EMPTY
    }

    /**
     * Scans the extracted library for every configured signature.
     *
     * @param extracted the library to scan.
     * @return the scan outcome, or `null` when no patterns are configured.
     */
    private suspend fun scanForOffsets(
        extracted: com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary,
    ): ScanOutcome? {
        if (patterns.isEmpty()) {
            emit(Logs.warn(TAG_RUST, "No signatures configured; skipping the scan."))
            return null
        }

        _uiState.update { it.copy(progressTask = "Scanning executable section") }

        // The engine reports from its own worker threads, so the callback only
        // touches thread-safe state: the state flow and the log flow.
        val lastStep = java.util.concurrent.atomic.AtomicReference("")
        val reporter = ProgressCallback { percent, message ->
            _uiState.update { it.copy(progress = percent, progressTask = message) }
            if (lastStep.getAndSet(message) != message) {
                emit(Logs.info(TAG_RUST, message))
            }
        }
        val outcome = engine.scanText(extracted, patterns, reporter)
        emit(
            Logs.info(
                TAG_RUST,
                "Parallel scan complete. Found ${outcome.hits.size} pattern matches.",
            ),
        )
        emit(
            Logs.info(
                TAG_RUST,
                "Verified ${outcome.hits.size} valid function prologues " +
                    "(${outcome.discarded} discarded).",
            ),
        )
        outcome.peakResidentBytes?.let { peak ->
            emit(Logs.info(TAG_RUST, "Peak resident set ${formatBytes(peak)}"))
        }
        return outcome
    }

    /** Formats [value] as a `0x`-prefixed hexadecimal string. */
    private fun com.chimerateam.offsetextractor.data.native.SectionMap.hex(value: Long): String =
        "0x%x".format(value)

    private suspend fun fail(message: String, hint: String?, tag: String) {
        emit(Logs.error(tag, message))
        _uiState.update {
            it.copy(
                phase = ScannerPhase.Error,
                progress = 0,
                progressTask = "",
                error = message,
                errorHint = hint,
            )
        }
    }

    private fun releaseActiveApk() {
        activeApk?.let { source ->
            runCatching { source.close() }
                .onFailure { emit(Logs.warn(TAG_SAF, "Descriptor close reported: ${it.message}")) }
        }
        activeApk = null
    }

    private fun emit(entry: LogEntry) {
        _logs.tryEmit(entry)
    }

    /**
     * Creates the production ViewModel for an Activity or composable.
     *
     * @param safAccess storage-access implementation.
     * @param engine extraction engine.
     * @param cacheDir directory that receives extracted native libraries.
 * @param patterns signatures the scanner looks for; empty skips the scan.
     */
    companion object {
        private const val LOG_BUFFER = 64

        /**
         * Builds a ViewModel wired to the real engine.
         *
         * @param safAccess storage-access implementation used to open APKs.
         * @param engine extraction engine, JNI-backed in production.
         * @param cacheDir directory that receives extracted native libraries.
 * @param patterns signatures the scanner looks for; empty skips the scan.
         * @return a ready-to-use ViewModel.
         */
        fun create(
            safAccess: SafApkAccess,
            engine: OffsetEngine,
            cacheDir: File,
            patterns: List<PatternSpec> = emptyList(),
        ): MainViewModel = MainViewModel(safAccess, engine, cacheDir, patterns)
    }
}
