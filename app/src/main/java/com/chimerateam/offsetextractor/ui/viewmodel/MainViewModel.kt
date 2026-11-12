package com.chimerateam.offsetextractor.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chimerateam.offsetextractor.data.cache.ResultCache
import com.chimerateam.offsetextractor.data.cache.ScanCache
import com.chimerateam.offsetextractor.data.cache.ScanPreferences
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.native.PatternSpec
import com.chimerateam.offsetextractor.data.native.ProgressCallback
import com.chimerateam.offsetextractor.data.native.ScanOutcome
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.data.saf.ApkOpenResult
import com.chimerateam.offsetextractor.data.saf.ApkSource
import com.chimerateam.offsetextractor.data.saf.DocumentWriter
import com.chimerateam.offsetextractor.data.saf.OffsetEngine
import com.chimerateam.offsetextractor.data.saf.SafApkAccess
import com.chimerateam.offsetextractor.data.saf.WriteResult
import com.chimerateam.offsetextractor.ui.error.ErrorMapper
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.log.Logs
import com.chimerateam.offsetextractor.ui.state.BatchProgress
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.state.ScannerUiState
import com.chimerateam.offsetextractor.util.formatBytes
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Origin tag used for lines the ViewModel itself emits. */
private const val TAG_UI = "UI"

/** Origin tag used for lines describing engine work. */
private const val TAG_RUST = "RUST"

/** Number of resolved vtables written to the live log. */
private const val MAX_LOGGED_VTABLES = 10

/**
 * Number of entries retained in the log pane.
 *
 * A long scan emits progress lines continuously, so the list is bounded: the
 * oldest line is dropped once the cap is reached rather than growing without
 * limit on a memory-constrained device.
 */
const val MAX_LOG_ENTRIES = 500

/** Origin tag used for storage-access lines. */
private const val TAG_SAF = "SAF"

/** Origin tag used for cache lines. */
private const val TAG_CACHE = "CACHE"

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
    private val writer: DocumentWriter = DocumentWriter { _, _ ->
        WriteResult.Failure("No document writer is configured.")
    },
    private val cache: ScanCache? = null,
    private val preferences: ScanPreferences? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val supervisor = SupervisorJob(viewModelScope.coroutineContext[Job])

    private val _uiState = MutableStateFlow(ScannerUiState())

    /** Current scanner snapshot. */
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Retained log entries in chronological order, capped at [MAX_LOG_ENTRIES]. */
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val resultCache = cache?.let(::ResultCache)

    private val _forceRescan = MutableStateFlow(false)

    /** Whether the next scan ignores a cached result. */
    val forceRescan: StateFlow<Boolean> = _forceRescan.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)

    /** Total size of everything held in the on-disk cache. */
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _lastBuildId = MutableStateFlow("")

    /** Build identifier recorded by the most recent successful scan. */
    val lastBuildId: StateFlow<String> = _lastBuildId.asStateFlow()

    private var activeApk: ApkSource? = null
    private var extractionJob: Job? = null

    init {
        cache?.ensureDirectories()
        refreshCacheSize()
        preferences?.let { store ->
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.forceRescan.collect { force -> _forceRescan.value = force }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.lastBuildId.collect { buildId -> _lastBuildId.value = buildId }
            }
        }
    }

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
                batch = null,
                batchSummary = null
            )
        }

        extractionJob = viewModelScope.launch(supervisor + ioDispatcher) {
            processApk(uri)
        }
    }

    /**
     * Scans several APKs one after another.
     *
     * The queue is sequential because the engine maps an entire library at
     * once; running two scans at the same time would double the peak memory
     * for no gain on a phone. A failure on one APK is counted and the queue
     * moves on, so one bad file cannot sink a batch.
     *
     * @param uris document URIs returned by the multi-document picker.
     */
    fun onApksSelected(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            onSelectionCancelled()
            return
        }
        if (uris.size == 1) {
            onApkSelected(uris.first())
            return
        }

        extractionJob?.cancel()
        _uiState.update {
            it.copy(
                phase = ScannerPhase.Extracting,
                progress = 0,
                progressTask = "Opening APK",
                result = null,
                error = null,
                errorHint = null,
                batchSummary = null,
                batch = BatchProgress(total = uris.size)
            )
        }

        val queue = BatchQueue(
            process = { uri -> processApk(uri) },
            onProgress = { progress ->
                _uiState.update { it.copy(batch = progress) }
                emit(Logs.info(TAG_UI, "Batch ${progress.position} of ${progress.total}."))
            },
            onFailure = { throwable ->
                // processApk reports its own failures; this is the
                // belt-and-braces case where something escaped it.
                val mapped = ErrorMapper.fromThrowable(throwable)
                fail(mapped.message, mapped.hint, tag = TAG_UI)
            },
            describe = { uri ->
                val metadata = safAccess.queryMetadata(uri)
                "${metadata.displayName} (${formatBytes(metadata.sizeBytes)})"
            }
        )

        extractionJob = viewModelScope.launch(supervisor + ioDispatcher) {
            val summary = queue.run(uris)
            _uiState.update { it.copy(batch = null, batchSummary = summary) }
            emit(
                Logs.info(
                    TAG_UI,
                    "Processed ${summary.total} APKs. " +
                        "${summary.succeeded} success, ${summary.failed} error."
                )
            )
        }
    }

    /** Aborts a running batch; the in-flight APK is cancelled with it. */
    fun cancelBatch() {
        if (_uiState.value.batch == null) return
        val job = extractionJob
        extractionJob = null
        // Stop the native scan too: cancelling the coroutine cannot interrupt
        // the blocking JNI call for the APK currently being scanned.
        engine.cancelScan()
        job?.cancel()
        releaseActiveApk()
        emit(Logs.warn(TAG_UI, "Batch cancelled."))
        _uiState.update { it.copy(batch = null) }
    }

    /** Clears the batch summary once the dialog has been dismissed. */
    fun onBatchSummaryShown() {
        _uiState.update { it.copy(batchSummary = null) }
    }

    /**
     * Opens one APK and extracts its library, leaving the result in the state.
     *
     * @param uri document URI of the APK to process.
     * @return whether the APK produced a result.
     */
    private suspend fun processApk(uri: android.net.Uri): Boolean {
        emit(Logs.info(TAG_SAF, "Opening selected APK..."))

        val metadata = safAccess.queryMetadata(uri)
        when (val opened = safAccess.openApk(uri)) {
            is ApkOpenResult.Failure -> {
                val mapped = ErrorMapper.fromSaf(opened.reason, metadata.displayName)
                fail(mapped.message, mapped.hint, tag = TAG_SAF)
                return false
            }

            is ApkOpenResult.Success -> {
                releaseActiveApk()
                activeApk = opened.handle
                emit(Logs.info(TAG_SAF, "Opened APK File Descriptor successfully."))
                emit(
                    Logs.info(
                        TAG_SAF,
                        "${metadata.displayName}: ${opened.handle.lengthBytes} bytes" +
                            if (opened.usedCacheCopy) " (streamed cache copy)" else ""
                    )
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
                        errorHint = null
                    )
                }
                return extractNativeLibrary(opened.handle)
            }
        }
    }

    /** Cancels any running extraction and releases the open descriptor. */
    fun cancel() {
        val job = extractionJob
        extractionJob = null
        // The scan is a blocking JNI call, so cancelling the coroutine alone
        // leaves the native workers running. Signal the engine first so the
        // workers stop at their next check.
        engine.cancelScan()
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

    /**
     * Default file name offered by the save dialog.
     *
     * @return the name keyed by build identifier, or `null` when no scan has
     *   completed.
     */
    fun defaultExportFileName(): String? = _uiState.value.result?.let(OffsetExporter::fileName)

    /**
     * Writes the current result to [uri].
     *
     * A `null` [uri] means the user dismissed the save dialog; that is a
     * normal outcome and only a log line is produced. The write runs on the
     * I/O dispatcher and always closes the stream.
     *
     * @param uri destination document, or `null` when the dialog was dismissed.
     */
    fun onExportDestinationChosen(uri: android.net.Uri?) {
        if (uri == null) {
            emit(Logs.warn(TAG_UI, "Export cancelled."))
            return
        }
        val result = _uiState.value.result
        if (result == null) {
            emit(Logs.warn(TAG_UI, "There is nothing to export yet."))
            return
        }

        // Deliberately not assigned to `extractionJob`: the export is a side
        // action, so cancelling the scan must not cancel it, and vice versa.
        viewModelScope.launch(supervisor + ioDispatcher) {
            val json = OffsetExporter.toJson(result)
            when (val outcome = writer.writeText(uri, json)) {
                is WriteResult.Success -> {
                    emit(Logs.info(TAG_UI, "Exported offsets to $uri"))
                    _uiState.update { it.copy(exportMessage = "Offsets exported.") }
                }

                is WriteResult.Failure -> {
                    emit(Logs.error(TAG_UI, outcome.message))
                    _uiState.update { it.copy(exportMessage = outcome.message) }
                }
            }
        }
    }

    /** Clears the transient export message once it has been shown. */
    fun onExportMessageShown() {
        _uiState.update { it.copy(exportMessage = null) }
    }

    /**
     * Records that the system reported memory pressure.
     *
     * The notice is advisory: the scan continues, because extraction streams
     * from the descriptor and a scan holds only its result set. What the user
     * can usefully do is free the cache, so the notice says that.
     */
    fun onMemoryPressure() {
        if (_uiState.value.lowMemory) return
        emit(
            Logs.warn(
                TAG_UI,
                "The system is low on memory. Clearing the cache frees the most space."
            )
        )
        _uiState.update { it.copy(lowMemory = true) }
    }

    /** Dismisses the low-memory notice. */
    fun onLowMemoryNoticeDismissed() {
        _uiState.update { it.copy(lowMemory = false) }
    }

    /**
     * Turns cached-result reuse on or off.
     *
     * @param force true to always rescan, ignoring a cached document.
     */
    fun setForceRescan(force: Boolean) {
        _forceRescan.value = force
        emit(
            Logs.info(
                TAG_CACHE,
                if (force) {
                    "Force rescan enabled; cached results are ignored."
                } else {
                    "Force rescan disabled."
                }
            )
        )
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setForceRescan(force)
        }
    }

    /** Recomputes the reported cache size. */
    fun refreshCacheSize() {
        _cacheSizeBytes.value = cache?.totalSizeBytes() ?: 0L
    }

    /**
     * Deletes every cached library and document.
     *
     * The cache is only a convenience: a failure to clear it is logged and the
     * app carries on with whatever remains. Clearing rewrites and `fsync`s the
     * extracted libraries, so it runs on the I/O dispatcher; a 300 MB library
     * would otherwise block the main thread long enough to trip an ANR. It is
     * also refused while a scan is active, because the scan reads the very file
     * being shredded.
     */
    fun clearCache() {
        val store = cache
        if (store == null) {
            emit(Logs.warn(TAG_CACHE, "No cache is configured."))
            return
        }
        if (extractionJob?.isActive == true) {
            emit(Logs.warn(TAG_CACHE, "A scan is running; clear the cache afterwards."))
            return
        }
        viewModelScope.launch(supervisor + ioDispatcher) {
            val cleared = store.clear()
            refreshCacheSize()
            if (cleared) {
                emit(Logs.info(TAG_CACHE, "Cache cleared."))
                _uiState.update { it.copy(exportMessage = "Cache cleared.") }
            } else {
                emit(Logs.warn(TAG_CACHE, "Some cached files could not be deleted."))
            }
            preferences?.setLastBuildId("")
        }
    }

    /** Returns the cached result for [buildId], or `null` when it must be rescanned. */
    private fun cachedResultFor(buildId: String): ScanResultSummary? {
        if (_forceRescan.value) return null
        return resultCache?.load(buildId)
    }

    /** Stores [summary] as the cache entry for [buildId]. */
    private fun rememberScan(buildId: String, summary: ScanResultSummary?) {
        if (summary == null) return
        val stored = resultCache?.store(summary) ?: false
        if (stored) {
            refreshCacheSize()
        }
        _lastBuildId.value = buildId
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setLastBuildId(buildId)
        }
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

    private suspend fun extractNativeLibrary(source: ApkSource): Boolean {
        try {
            val extracted = engine.extractNativeLibrary(source.rawFd, cacheDir)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Streaming extraction complete. Hash: ${extracted.sha256}"
                )
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Extracted ${extracted.path.substringAfterLast('/')} " +
                        "(${extracted.sizeBytes} bytes)"
                )
            )

            _uiState.update {
                it.copy(
                    phase = ScannerPhase.Scanning,
                    progress = 100,
                    progressTask = "Mapping native library"
                )
            }

            val mapped = engine.mapLibrary(extracted)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Mapped ${mapped.sizeBytes} byte .so file with zero-copy. " +
                        "Arch: ${mapped.architecture}."
                )
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Entry point ${mapped.entryPoint}, ${mapped.sectionCount} sections"
                )
            )

            val sections = engine.describeSections(extracted)
            val text = sections.text
            emit(
                Logs.info(
                    TAG_RUST,
                    "Section ${text.name} [${text.hex(text.address)}.." +
                        "${text.hex(text.address + text.size)}] file " +
                        "${text.hex(text.offset)} delta ${text.hex(text.delta)}"
                )
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Image base ${sections.hex(sections.imageBase)}, " +
                        "${sections.sections.size} sections mapped"
                )
            )

            val symbols = engine.describeSymbols(extracted)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Build ID extracted. Found ${symbols.symbols.jni.size} dynamic symbols."
                )
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Build ID ${symbols.identity.buildId} " +
                        "(${symbols.identity.source}, ${symbols.symbols.defined} defined)"
                )
            )

            cachedResultFor(symbols.identity.buildId)?.let { cached ->
                emit(Logs.info(TAG_CACHE, "Loaded offsets from previous scan."))
                _uiState.update {
                    it.copy(
                        phase = ScannerPhase.Success,
                        progress = 100,
                        progressTask = "Loaded from cache",
                        result = cached.copy(
                            displayName = source.metadata.displayName,
                            sizeBytes = extracted.sizeBytes,
                            sha256 = extracted.sha256
                        ),
                        error = null,
                        errorHint = null
                    )
                }
                return true
            }

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
                        verification = scan?.verification ?: VerificationSummary.NONE
                    ),
                    error = null,
                    errorHint = null
                )
            }

            reportVerification(scan?.verification)

            rememberScan(symbols.identity.buildId, _uiState.value.result)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val mapped = ErrorMapper.fromThrowable(throwable, source.metadata.displayName)
            fail(mapped.message, mapped.hint, tag = TAG_RUST)
            return false
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
        extracted: com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
    ): VtableMap = try {
        // Logged before the call so a process death is pinned to this phase in
        // the live log rather than inferred from where the log stops.
        emit(Logs.info(TAG_RUST, "Resolving RTTI vtables..."))
        val map = engine.resolveVtables(extracted)
        if (map.isEmpty) {
            emit(Logs.info(TAG_RUST, "No RTTI vtables present in this library."))
        } else {
            emit(
                Logs.info(
                    TAG_RUST,
                    "Resolved ${map.size} RTTI vtables from ${map.source}."
                )
            )
            map.entries.values.take(MAX_LOGGED_VTABLES).forEach { vtable ->
                emit(
                    Logs.info(
                        TAG_RUST,
                        "Resolved ${vtable.name} vtable at ${vtable.vtableHex}"
                    )
                )
            }
        }
        map
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        // The RTTI pass is the memory-hungry one, and a failure there is not
        // fatal: the offset scan is already complete and is reported on its own.
        // Catching only EngineException would let an OutOfMemoryError discard a
        // good scan, so every throwable is absorbed here.
        emit(Logs.warn(TAG_RUST, "Vtable resolution skipped: ${throwable.message}"))
        VtableMap.EMPTY
    }

    /**
     * Scans the extracted library for every configured signature.
     *
     * @param extracted the library to scan.
     * @return the scan outcome, or `null` when no patterns are configured.
     */
    private suspend fun scanForOffsets(
        extracted: com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
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
                "Parallel scan complete. Found ${outcome.hits.size} pattern matches."
            )
        )
        if (outcome.truncated) {
            emit(
                Logs.warn(
                    TAG_RUST,
                    "Some signatures matched far more often than expected; " +
                        "the hit list was capped. Tighten the loose signatures."
                )
            )
        }
        emit(
            Logs.info(
                TAG_RUST,
                "Verified ${outcome.hits.size} valid function prologues " +
                    "(${outcome.discarded} discarded)."
            )
        )
        outcome.peakResidentBytes?.let { peak ->
            emit(Logs.info(TAG_RUST, "Peak resident set ${formatBytes(peak)}"))
        }
        return outcome
    }

    /**
     * Writes the symbol cross-check result to the live log.
     *
     * A conflict is logged as an error, because acting on an offset that the
     * library's own symbol table contradicts is worse than having no offset.
     */
    private fun reportVerification(summary: VerificationSummary?) {
        if (summary == null) return
        if (!summary.symbolsAvailable) {
            emit(
                Logs.info(
                    TAG_RUST,
                    "Offset verification skipped: the library exports no symbols."
                )
            )
            return
        }
        emit(
            Logs.info(
                TAG_RUST,
                "Offset verification complete. ${summary.verified} verified, " +
                    "${summary.conflicts} conflicts, ${summary.confidencePercent}% confidence."
            )
        )
        if (summary.conflicts > 0) {
            emit(
                Logs.error(
                    TAG_RUST,
                    "${summary.conflicts} offset(s) disagree with the symbol table; " +
                        "treat them as stale."
                )
            )
        }
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
                errorHint = hint
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
        _logEntries.update { existing ->
            val appended = existing + entry
            if (appended.size <= MAX_LOG_ENTRIES) {
                appended
            } else {
                appended.takeLast(MAX_LOG_ENTRIES)
            }
        }
    }

    /** Discards every retained log entry. */
    fun clearLog() {
        _logEntries.value = emptyList()
        emit(Logs.info(TAG_UI, "Log cleared."))
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
            writer: DocumentWriter,
            cache: ScanCache,
            preferences: ScanPreferences,
            patterns: List<PatternSpec> = emptyList()
        ): MainViewModel = MainViewModel(
            safAccess = safAccess,
            engine = engine,
            cacheDir = cacheDir,
            patterns = patterns,
            writer = writer,
            cache = cache,
            preferences = preferences
        )
    }
}
