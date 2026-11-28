package com.chimerateam.offsetextractor.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chimerateam.offsetextractor.data.cache.DEFAULT_CACHE_CAP_MB
import com.chimerateam.offsetextractor.data.cache.DEFAULT_DELETE_APK_AFTER_SCAN
import com.chimerateam.offsetextractor.data.cache.DEFAULT_LOW_MEMORY_WARNINGS
import com.chimerateam.offsetextractor.data.cache.DEFAULT_SLOTS_PER_CLASS
import com.chimerateam.offsetextractor.data.cache.ResultCache
import com.chimerateam.offsetextractor.data.cache.ScanCache
import com.chimerateam.offsetextractor.data.cache.ScanPreferences
import com.chimerateam.offsetextractor.data.cache.clampSlotsPerClass
import com.chimerateam.offsetextractor.data.export.CategoryRule
import com.chimerateam.offsetextractor.data.export.CategoryRulesJson
import com.chimerateam.offsetextractor.data.export.Classifier
import com.chimerateam.offsetextractor.data.export.ExportJobRepository
import com.chimerateam.offsetextractor.data.export.ExportKind
import com.chimerateam.offsetextractor.data.export.ExportStarter
import com.chimerateam.offsetextractor.data.export.InternalTable
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.export.OffsetZipBuilder
import com.chimerateam.offsetextractor.data.native.InspectorState
import com.chimerateam.offsetextractor.data.native.InternalAnalysis
import com.chimerateam.offsetextractor.data.native.InternalOptions
import com.chimerateam.offsetextractor.data.native.InternalPatternSpec
import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.JniReport
import com.chimerateam.offsetextractor.data.native.PatternSpec
import com.chimerateam.offsetextractor.data.native.ProgressCallback
import com.chimerateam.offsetextractor.data.native.ScanOutcome
import com.chimerateam.offsetextractor.data.native.StringAnchorSpec
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.data.saf.ApkOpenResult
import com.chimerateam.offsetextractor.data.saf.ApkSource
import com.chimerateam.offsetextractor.data.saf.DocumentWriter
import com.chimerateam.offsetextractor.data.saf.ExtractedNativeLibrary
import com.chimerateam.offsetextractor.data.saf.OffsetEngine
import com.chimerateam.offsetextractor.data.saf.SafApkAccess
import com.chimerateam.offsetextractor.data.saf.WriteResult
import com.chimerateam.offsetextractor.data.storage.StorageAreaId
import com.chimerateam.offsetextractor.data.storage.StorageManager
import com.chimerateam.offsetextractor.data.storage.StorageUsage
import com.chimerateam.offsetextractor.ui.error.ErrorMapper
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.log.LogFile
import com.chimerateam.offsetextractor.ui.log.LogTimestamp
import com.chimerateam.offsetextractor.ui.log.Logs
import com.chimerateam.offsetextractor.ui.log.ProgressGate
import com.chimerateam.offsetextractor.ui.state.BatchProgress
import com.chimerateam.offsetextractor.ui.state.InternalFailure
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

/**
 * Number of entries retained in the log pane.
 *
 * A long scan emits progress lines continuously, so the list is bounded: the
 * oldest line is dropped once the cap is reached rather than growing without
 * limit on a memory-constrained device.
 */
const val MAX_LOG_ENTRIES = 2_000

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
    private val internalPatterns: List<InternalPatternSpec> = emptyList(),
    private val stringAnchors: List<StringAnchorSpec> = emptyList(),
    private val writer: DocumentWriter = DocumentWriter { _, _ ->
        WriteResult.Failure("No document writer is configured.")
    },
    private val cache: ScanCache? = null,
    private val preferences: ScanPreferences? = null,
    private var categories: List<CategoryRule> = emptyList(),
    private val exportStarter: ExportStarter? = null,
    private val storage: StorageManager? = null,
    private val logFile: LogFile? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val supervisor = SupervisorJob(viewModelScope.coroutineContext[Job])

    private val _uiState = MutableStateFlow(ScannerUiState())

    /** Current scanner snapshot. */
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _inspector = MutableStateFlow(InspectorState())

    /**
     * State of the right-hand inspector pane.
     *
     * Loading is explicit: the engine answers the two queries on a background
     * thread, and the pane must not keep showing the previous address while a
     * new one is being inspected.
     */
    val inspector: StateFlow<InspectorState> = _inspector.asStateFlow()

    private val history = ArrayDeque<ScanResultSummary>()

    private val _scanHistory = MutableStateFlow<List<ScanResultSummary>>(emptyList())

    /**
     * Completed scans made in this session, newest first.
     *
     * The diff screen compares two of them, so scanning one APK and then the
     * other is enough to get a build-to-build comparison without a second file
     * picker or a cache round-trip.
     */
    val scanHistory: StateFlow<List<ScanResultSummary>> = _scanHistory.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())

    private val scanIdState = MutableStateFlow("")

    /**
     * Lines of the scan that is on screen, in chronological order.
     *
     * Every entry is stamped with the scan it belongs to and the list is
     * emptied when a scan starts, so a live log can never sit next to a result
     * from another run.
     */
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private var scanCounter = 0

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

    private val _includeAllFunctions = MutableStateFlow(true)

    /** Whether the internal pass enumerates every function. */
    val includeAllFunctions: StateFlow<Boolean> = _includeAllFunctions.asStateFlow()

    private val _generateSignatures = MutableStateFlow(true)

    /** Whether auto-generated signatures are produced. */
    val generateSignatures: StateFlow<Boolean> = _generateSignatures.asStateFlow()

    private val _includeCombined = MutableStateFlow(false)

    /** Whether the ZIP also contains one combined internal document. */
    val includeCombined: StateFlow<Boolean> = _includeCombined.asStateFlow()

    private val _maxSlotsPerClass = MutableStateFlow(DEFAULT_SLOTS_PER_CLASS)

    /** Maximum virtual slots read per class. */
    val maxSlotsPerClass: StateFlow<Int> = _maxSlotsPerClass.asStateFlow()

    private val _themeMode = MutableStateFlow("System")

    /** Persisted theme mode name. */
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _lowMemoryWarnings = MutableStateFlow(DEFAULT_LOW_MEMORY_WARNINGS)

    /** Whether the advisory low-memory notice is shown. */
    val lowMemoryWarnings: StateFlow<Boolean> = _lowMemoryWarnings.asStateFlow()

    /** When the notice was last logged and shown. */
    private var lastMemoryNoticeMillis = Long.MIN_VALUE

    private val _storageUsage = MutableStateFlow(StorageUsage(emptyList(), 0L))

    /** What the app holds on disk, refreshed after every job. */
    val storageUsage: StateFlow<StorageUsage> = _storageUsage.asStateFlow()

    private val _deleteApkAfterScan = MutableStateFlow(DEFAULT_DELETE_APK_AFTER_SCAN)

    /** Whether a downloaded APK is deleted once its library is extracted. */
    val deleteApkAfterScan: StateFlow<Boolean> = _deleteApkAfterScan.asStateFlow()

    private val _cacheCapMb = MutableStateFlow(DEFAULT_CACHE_CAP_MB)

    /** Hard cap on cached data in megabytes; zero disables it. */
    val cacheCapMb: StateFlow<Int> = _cacheCapMb.asStateFlow()

    private val _reduceMotion = MutableStateFlow(false)

    /** Whether animations are reduced to instant transitions. */
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    private val _storageNotice = MutableStateFlow<String?>(null)

    /** One-line storage message the UI shows once, then dismisses. */
    val storageNotice: StateFlow<String?> = _storageNotice.asStateFlow()

    /** APK the APKs page downloaded for the current selection, if any. */
    private var downloadedApk: File? = null

    private var activeApk: ApkSource? = null
    private var extractionJob: Job? = null

    /** The library extracted for the current selection, kept for a Part 2 retry. */
    private var lastExtracted: ExtractedNativeLibrary? = null

    /** `versionName` read from the APK manifest, when it carried one. */
    private var lastApkVersion: String = ""

    /** Detail of the last failed internal pass, or `null`. */
    private var lastInternalError: String? = null

    /** Failure of the last internal pass, used to decide whether a retry can work. */
    private var lastInternalFailure: Throwable? = null

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
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.includeAllFunctions.collect { _includeAllFunctions.value = it }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.generateSignatures.collect { _generateSignatures.value = it }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.maxSlotsPerClass.collect { _maxSlotsPerClass.value = it }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.themeMode.collect { _themeMode.value = it }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.lowMemoryWarnings.collect { _lowMemoryWarnings.value = it }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.deleteApkAfterScan.collect { _deleteApkAfterScan.value = it }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.cacheCapMb.collect { _cacheCapMb.value = it }
            }
            viewModelScope.launch(supervisor + ioDispatcher) {
                store.reduceMotion.collect { _reduceMotion.value = it }
            }
        }
        // A crash leaves its stack in `live.log` but nothing on screen, and the
        // tablet has no easy way to reach `logcat`. Reading the previous run's
        // crash back into the pane is what makes it visible, and Copy log then
        // carries it off the device.
        viewModelScope.launch(supervisor + ioDispatcher) {
            val previous = runCatching { logFile?.read().orEmpty() }.getOrDefault("")
            val crashes = previous.lineSequence().filter { "CRASH" in it }.take(40).toList()
            if (crashes.isNotEmpty()) {
                // Added to the pane only: re-emitting them would spill the same
                // crash into `live.log` again on every start.
                val lines = listOf("Previous run crashed; Copy log carries the report.") + crashes
                _logEntries.value = _logEntries.value + lines.map { line ->
                    Logs.error(TAG_UI, line.trim())
                }
            }
        }

        // A previous process may have died mid-download or mid-ZIP. Sweeping on
        // start is what stops those leftovers from becoming the reason the next
        // scan runs out of space.
        viewModelScope.launch(supervisor + ioDispatcher) {
            storage?.let { manager ->
                val freed = manager.sweep(
                    keepTablePath = keepTablePath(),
                    keepApks = _deleteApkAfterScan.value
                )
                if (freed > 0L) {
                    emit(Logs.info(TAG_CACHE, "Freed ${formatBytes(freed)} of leftover data."))
                }
                refreshStorageUsage()
            }
        }
    }

    /**
     * Table the current result still needs, or the newest one on disk.
     *
     * The newest table is kept because the cached result of the previous scan
     * points at it: deleting it would turn a cache hit into an export that can
     * never be built.
     */
    private fun keepTablePath(): String? =
        _uiState.value.result?.internal?.path?.takeIf { it.isNotBlank() }
            ?: storage?.newestTable()?.absolutePath

    /** Recomputes the storage list and the cache size. */
    private fun refreshStorageUsage() {
        val manager = storage ?: return
        val usage = manager.usage(keepTablePath())
        _storageUsage.value = usage
        _cacheSizeBytes.value = usage.totalBytes
    }

    /** Recomputes the storage list from the UI thread. */
    fun refreshStorage() {
        viewModelScope.launch(supervisor + ioDispatcher) { refreshStorageUsage() }
    }

    /**
     * Records whether animations are reduced.
     *
     * @param reduce the new preference.
     */
    fun setReduceMotion(reduce: Boolean) {
        _reduceMotion.value = reduce
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setReduceMotion(reduce)
        }
    }

    /** Clears the one-line storage message once it has been shown. */
    fun onStorageNoticeShown() {
        _storageNotice.value = null
    }

    /**
     * Records whether downloaded APKs are deleted after extraction.
     *
     * @param delete the new preference.
     */
    fun setDeleteApkAfterScan(delete: Boolean) {
        _deleteApkAfterScan.value = delete
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setDeleteApkAfterScan(delete)
            if (delete) {
                val freed = storage?.deleteDownloadedApks() ?: 0L
                if (freed > 0L) {
                    emit(Logs.info(TAG_CACHE, "Deleted downloaded APKs (${formatBytes(freed)})."))
                }
                refreshStorageUsage()
            }
        }
    }

    /**
     * Records the cache cap.
     *
     * @param megabytes cap in megabytes, zero to disable it.
     */
    fun setCacheCapMb(megabytes: Int) {
        val clamped = megabytes.coerceIn(0, 64 * 1024)
        _cacheCapMb.value = clamped
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setCacheCapMb(clamped)
            val freed = storage?.enforceCap(clamped * 1024L * 1024L, keepTablePath()) ?: 0L
            if (freed > 0L) {
                emit(Logs.info(TAG_CACHE, "Trimmed ${formatBytes(freed)} to stay under the cap."))
            }
            refreshStorageUsage()
        }
    }

    /**
     * Deletes everything the current result does not need.
     *
     * @return bytes reclaimed, reported through [storageNotice].
     */
    fun freeUpSpace() {
        if (isBusy()) {
            _storageNotice.value = "Finish the running job first."
            return
        }
        viewModelScope.launch(supervisor + ioDispatcher) {
            val freed = storage?.freeUpSpace(keepTablePath()) ?: 0L
            refreshStorageUsage()
            _storageNotice.value = "Freed ${formatBytes(freed)}."
            emit(Logs.info(TAG_CACHE, "Freed ${formatBytes(freed)}."))
        }
    }

    /**
     * Deletes one storage group.
     *
     * @param id the group to clear.
     */
    fun deleteStorageArea(id: StorageAreaId) {
        if (isBusy()) {
            _storageNotice.value = "Finish the running job first."
            return
        }
        viewModelScope.launch(supervisor + ioDispatcher) {
            val freed = storage?.deleteArea(id, keepTablePath()) ?: 0L
            refreshStorageUsage()
            _storageNotice.value = "Deleted ${formatBytes(freed)}."
        }
    }

    /**
     * Deletes one file from the storage list.
     *
     * @param file the file to remove.
     */
    fun deleteStorageFile(file: File) {
        if (isBusy()) {
            _storageNotice.value = "Finish the running job first."
            return
        }
        viewModelScope.launch(supervisor + ioDispatcher) {
            val freed = storage?.delete(file) ?: 0L
            refreshStorageUsage()
            if (freed > 0L) _storageNotice.value = "Deleted ${formatBytes(freed)}."
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
        beginScan()

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
     * Scans an APK that the catalogue tab downloaded into the app cache.
     *
     * The file is opened directly, so this path does not need a content URI.
     * Everything after opening is the same extraction and scan flow as a
     * picker-selected APK.
     *
     * @param file downloaded APK on disk.
     */
    fun onApkFileSelected(file: File) {
        extractionJob?.cancel()
        beginScan()
        downloadedApk = file
        // The APK plus its library, shards and archive is about two gigabytes
        // for a 1 GB build. Refusing up front with the real numbers beats a
        // half-written table and a raw ENOSPC from the middle of the pipeline.
        val check = StorageManager.preflight(
            StorageManager.estimateBytes(file.length()),
            file.parentFile ?: cacheDir
        )
        if (!check.enough) {
            emit(Logs.warn(TAG_CACHE, "Not enough space: ${check.message}"))
            _storageNotice.value = check.message
            _uiState.update {
                it.copy(
                    phase = ScannerPhase.Error,
                    progress = 0,
                    progressTask = "",
                    result = null,
                    error = check.message,
                    errorHint = "Free up space in Settings > Storage, then try again.",
                    internalAvailable = false
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                phase = ScannerPhase.Extracting,
                progress = 0,
                progressTask = "Opening downloaded APK",
                result = null,
                error = null,
                errorHint = null,
                batch = null,
                batchSummary = null
            )
        }
        extractionJob = viewModelScope.launch(supervisor + ioDispatcher) {
            when (val opened = safAccess.openFile(file)) {
                is ApkOpenResult.Failure -> {
                    val mapped = ErrorMapper.fromSaf(opened.reason, file.name)
                    fail(mapped.message, mapped.hint, tag = TAG_SAF)
                }

                is ApkOpenResult.Success -> {
                    releaseActiveApk()
                    activeApk = opened.handle
                    emit(
                        Logs.info(
                            TAG_SAF,
                            "Opened downloaded ${file.name} (${opened.handle.lengthBytes} bytes)."
                        )
                    )
                    _uiState.update {
                        it.copy(
                            phase = ScannerPhase.Extracting,
                            progress = 0,
                            progressTask = "Extracting native library",
                            selectedApk = opened.handle.metadata,
                            usedCacheCopy = opened.usedCacheCopy,
                            result = null,
                            error = null,
                            errorHint = null
                        )
                    }
                    extractNativeLibrary(opened.handle)
                }
            }
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

        val writtenFiles = mutableListOf<String>()
        val queue = BatchQueue(
            process = { uri ->
                val ok = processApk(uri)
                if (ok) {
                    _uiState.value.result?.let { result ->
                        writtenFiles += saveBatchDocuments(result)
                    }
                }
                ok
            },
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
            val summary = queue.run(uris).copy(files = writtenFiles.toList())
            _uiState.update { it.copy(batch = null, batchSummary = summary) }
            storage?.let { manager ->
                manager.sweep(keepTablePath = keepTablePath(), keepApks = _deleteApkAfterScan.value)
                refreshStorageUsage()
            }
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
        viewModelScope.launch(supervisor + ioDispatcher) {
            storage?.enforceCap(_cacheCapMb.value * 1024L * 1024L, keepTablePath())
            refreshStorageUsage()
        }
    }

    /**
     * Default file name offered by the save dialog.
     *
     * @param kind which document is being exported.
     * @return the name built from the APK version, or `null` when no scan has
     *   completed.
     */
    fun defaultExportFileName(kind: ExportKind = ExportKind.Jni): String? =
        _uiState.value.result?.let { OffsetExporter.fileNameFor(kind, it) }

    /**
     * Writes the current result to [uri].
     *
     * A `null` [uri] means the user dismissed the save dialog; that is a
     * normal outcome and only a log line is produced. The write runs on the
     * I/O dispatcher and always closes the stream. Both documents are streamed;
     * the internal table is never first built as one `String`.
     *
     * @param uri destination document, or `null` when the dialog was dismissed.
     * @param kind which document to write.
     */
    fun onExportDestinationChosen(uri: android.net.Uri?, kind: ExportKind = ExportKind.Jni) {
        if (uri == null) {
            emit(Logs.warn(TAG_UI, "Export cancelled."))
            return
        }
        val result = _uiState.value.result
        if (result == null) {
            emit(Logs.warn(TAG_UI, "There is nothing to export yet."))
            return
        }
        if (kind == ExportKind.Internal && (!result.internalResolved || result.internal == null)) {
            emit(Logs.warn(TAG_UI, "The internal offsets are not ready yet."))
            _uiState.update { it.copy(exportMessage = "Internal offsets are not ready.") }
            return
        }

        // Deliberately not assigned to `extractionJob`: the export is a side
        // action, so cancelling the scan must not cancel it, and vice versa.
        viewModelScope.launch(supervisor + ioDispatcher) {
            val outcome = writeDocument(uri, kind, result)
            when (outcome) {
                is WriteResult.Success -> {
                    val name = OffsetExporter.fileNameFor(kind, result)
                    emit(Logs.info(TAG_UI, "Saved $name"))
                    _uiState.update { it.copy(exportMessage = "Saved $name") }
                }

                is WriteResult.Failure -> {
                    emit(Logs.error(TAG_UI, outcome.message))
                    _uiState.update { it.copy(exportMessage = outcome.message) }
                }
            }
        }
    }

    /** Default file name offered by the ZIP save dialog. */
    fun defaultZipFileName(): String? =
        _uiState.value.result?.let { OffsetExporter.zipFileName(it) }

    /**
     * Hands the current result to the foreground export service.
     *
     * The ViewModel does not own the job: it only records the request and lets
     * the service publish progress through `ExportJobRepository`.
     *
     * @param uri destination document, or `null` when the dialog was dismissed.
     */
    fun onZipDestinationChosen(uri: android.net.Uri?) {
        if (uri == null) {
            emit(Logs.warn(TAG_UI, "Download cancelled."))
            return
        }
        val result = _uiState.value.result
        if (result == null) {
            emit(Logs.warn(TAG_UI, "There is nothing to export yet."))
            return
        }
        if (!result.internalResolved || result.internal == null) {
            emit(Logs.warn(TAG_UI, "The internal offsets are not ready yet."))
            _uiState.update { it.copy(exportMessage = "Internal offsets are not ready.") }
            return
        }
        val problem = InternalTable.problem(result)
        if (problem != null) {
            emit(Logs.error(TAG_UI, problem))
            _uiState.update {
                it.copy(exportMessage = problem, internalAvailable = false)
            }
            return
        }
        val starter = exportStarter
        if (starter == null) {
            emit(Logs.warn(TAG_UI, "The download service is not available."))
            _uiState.update { it.copy(exportMessage = "The download service is not available.") }
            return
        }
        starter.start(uri, result, categories, _includeCombined.value)
        val name = OffsetExporter.zipFileName(result)
        emit(Logs.info(TAG_UI, "Started $name"))
        _uiState.update { it.copy(exportMessage = "Preparing $name") }
    }

    /**
     * Writes [kind] to [uri], streaming in production and falling back to a
     * single string only for a test double that cannot stream.
     */
    private fun writeDocument(
        uri: android.net.Uri,
        kind: ExportKind,
        result: ScanResultSummary
    ): WriteResult {
        val streamed = writer.writeStream(uri) { out ->
            when (kind) {
                ExportKind.Jni -> OffsetExporter.writeJni(result, out)
                ExportKind.Internal -> OffsetExporter.writeInternal(result, out)
            }
        }
        if (streamed is WriteResult.Failure &&
            streamed.message.contains("does not support streaming", ignoreCase = true)
        ) {
            val text = java.io.StringWriter().also { buffer ->
                when (kind) {
                    ExportKind.Jni -> OffsetExporter.writeJni(result, buffer)
                    ExportKind.Internal -> OffsetExporter.writeInternal(result, buffer)
                }
            }.toString()
            return writer.writeText(uri, text)
        }
        return streamed
    }

    /**
     * Writes both documents for one batch item into the cache export directory.
     *
     * @param result the completed scan.
     * @return the file names written, for the batch summary.
     */
    private fun saveBatchDocuments(result: ScanResultSummary): List<String> {
        val directory = cache?.documentDir ?: File(cacheDir, "documents")
        directory.mkdirs()
        val written = mutableListOf<String>()
        val name = OffsetExporter.zipFileName(result)
        val work = File(cacheDir, "batch-${result.buildId.ifBlank { result.sha256.take(8) }}")
        runCatching {
            OffsetZipBuilder(Classifier(categories), work)
                .build(result, File(directory, name))
            written += name
        }.onFailure {
            emit(Logs.warn(TAG_UI, "Batch ZIP export failed: ${it.message}"))
        }.also {
            work.deleteRecursively()
        }
        if (result.internalResolved) {
            runCatching {
                val name = OffsetExporter.internalFileName(result)
                File(directory, name).bufferedWriter().use { writer ->
                    OffsetExporter.writeInternal(result, writer)
                }
                written += name
            }.onFailure {
                emit(Logs.warn(TAG_UI, "Batch internal export failed: ${it.message}"))
            }
        }
        return written
    }

    /** Clears the transient export message once it has been shown. */
    fun onExportMessageShown() {
        _uiState.update { it.copy(exportMessage = null) }
    }

    /**
     * Records that the system reported memory pressure.
     *
     * The notice is advisory and off by default: it cannot change how much
     * memory the app uses, and Android reports trim levels on a healthy device
     * often enough that showing it every time trains the user to dismiss it
     * without reading it.
     *
     * When the setting is on, the notice is shown at most once per
     * [MEMORY_NOTICE_INTERVAL_MS] and never while a scan or an export runs: the
     * only action it offers ("Clear cache") is refused during a scan and would
     * delete the internal table an export needs.
     */
    fun onMemoryPressure() {
        if (!_lowMemoryWarnings.value || isBusy()) {
            return
        }
        if (_uiState.value.lowMemory) return
        val now = clock()
        if (lastMemoryNoticeMillis != Long.MIN_VALUE &&
            now - lastMemoryNoticeMillis < MEMORY_NOTICE_INTERVAL_MS
        ) {
            return
        }
        lastMemoryNoticeMillis = now
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
     * Turns the advisory low-memory notice on or off.
     *
     * @param enabled whether the notice may be shown.
     */
    fun setLowMemoryWarnings(enabled: Boolean) {
        _lowMemoryWarnings.value = enabled
        emit(
            Logs.info(
                TAG_UI,
                if (enabled) {
                    "Low-memory warnings enabled."
                } else {
                    "Low-memory warnings disabled."
                }
            )
        )
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setLowMemoryWarnings(enabled)
        }
    }

    /** Whether a scan, an internal pass or an export is using the cache. */
    private fun isBusy(): Boolean =
        extractionJob?.isActive == true || ExportJobRepository.progress.value.isRunning

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

    /**
     * Turns the "include all functions" setting on or off.
     *
     * @param include whether the internal pass enumerates every function.
     */
    fun setIncludeAllFunctions(include: Boolean) {
        _includeAllFunctions.value = include
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setIncludeAllFunctions(include)
        }
    }

    /**
     * Turns auto-generated signatures on or off.
     *
     * @param generate whether signatures are produced.
     */
    fun setGenerateSignatures(generate: Boolean) {
        _generateSignatures.value = generate
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setGenerateSignatures(generate)
        }
    }

    /**
     * Records the selected theme mode.
     *
     * @param mode one of `System`, `Light`, `Dark` or `Amoled`.
     */
    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setThemeMode(mode)
        }
    }

    /**
     * Replaces the classifier rules after an import or a reset.
     *
     * @param rules the new ordered rules.
     */
    fun setCategories(rules: List<CategoryRule>) {
        categories = rules
    }

    /** Records whether the ZIP also contains one combined internal document. */
    fun setIncludeCombined(include: Boolean) {
        _includeCombined.value = include
    }

    /**
     * Changes the per-class slot cap.
     *
     * The value is persisted and sent to the engine unchanged: the engine's own
     * hard limit is the only ceiling, so a chip the Settings screen offers is
     * never silently reduced.
     *
     * @param slots maximum slots per class, clamped to the supported range.
     */
    fun setMaxSlotsPerClass(slots: Int) {
        val clamped = clampSlotsPerClass(slots)
        _maxSlotsPerClass.value = clamped
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setMaxSlotsPerClass(clamped)
        }
    }

    /** Recomputes the reported cache size and the storage list. */
    fun refreshCacheSize() {
        if (storage != null) {
            refreshStorageUsage()
        } else {
            _cacheSizeBytes.value = cache?.totalSizeBytes() ?: 0L
        }
    }

    /**
     * Loads the inspector pane for [address].
     *
     * The query is explicit and user-driven, so it may walk the whole library;
     * the result is cached only in [inspector] and never in the scan cache.
     *
     * @param address virtual address of the selected row.
     */
    fun inspectAddress(address: Long) {
        val extracted = lastExtracted
        if (extracted == null) {
            _inspector.value = InspectorState(
                address = address,
                error = "The extracted library is no longer available."
            )
            return
        }
        _inspector.value = InspectorState(loading = true, address = address)
        viewModelScope.launch(supervisor + ioDispatcher) {
            runCatching {
                val inspection = engine.inspectAddress(extracted, address)
                val disassembly = engine.disassemble(
                    extracted,
                    address,
                    DISASSEMBLY_PREVIEW_BYTES
                )
                inspection to disassembly
            }.onSuccess { (inspection, disassembly) ->
                _inspector.value = InspectorState(
                    address = address,
                    inspection = inspection,
                    disassembly = disassembly
                )
            }.onFailure { error ->
                _inspector.value = InspectorState(
                    address = address,
                    error = error.message ?: "The engine could not inspect that address."
                )
            }
        }
    }

    /** Clears the inspector pane, for example when a new scan starts. */
    fun clearInspector() {
        _inspector.value = InspectorState()
    }

    /**
     * Drops the engine's shared mapping of the extracted library.
     *
     * Called before the file is deleted or the cache is cleared. A mapping that
     * outlives the file faults with `SIGBUS` on the next access, which cannot be
     * caught, so this is a safety step rather than an optimisation.
     */
    private fun releaseMappedCache() {
        runCatching { engine.clearMappedCache() }
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
            releaseMappedCache()
            val cleared = if (storage != null) {
                storage.freeUpSpace(keepTablePath = null)
                store.clear()
            } else {
                store.clear()
            }
            refreshCacheSize()
            if (cleared) {
                emit(Logs.info(TAG_CACHE, "Cache cleared."))
                _uiState.update {
                    it.copy(
                        exportMessage = "Cache cleared. The internal data must be " +
                            "extracted again before a download.",
                        internalAvailable = false
                    )
                }
            } else {
                emit(Logs.warn(TAG_CACHE, "Some cached files could not be deleted."))
            }
            preferences?.setLastBuildId("")
        }
    }

    /**
     * Returns the cached result for [buildId], or `null` when it must be rescanned.
     *
     * An entry whose vtable or internal pass did not finish is ignored: caching
     * it would make every later scan of the same build reuse the failure and
     * never retry.
     */
    private fun cachedResultFor(
        buildId: String,
        engineVersion: String,
        imageBase: Long
    ): ScanResultSummary? {
        if (_forceRescan.value) return null
        val cached = resultCache?.load(buildId, engineVersion, imageBase) ?: return null
        // Part 1 is the expensive half and it is usable on its own, so a cache
        // entry is accepted as soon as the signatures and vtables are there.
        // A missing Part 2 is not "the cache is incomplete": the caller
        // re-runs that pass and keeps everything else.
        if (!cached.vtablesResolved) {
            emit(Logs.warn(TAG_CACHE, "Cached result has no vtables; scanning again."))
            return null
        }
        if (cached.internalResolved && InternalTable.problem(cached) != null) {
            emit(Logs.warn(TAG_CACHE, "Cached internal output is unusable; scanning again."))
            return null
        }
        return cached
    }

    /**
     * Stores [summary] as the cache entry for [buildId].
     *
     * Only a complete result is stored; a partial one would be reused forever
     * and hide the failed pass.
     */
    private fun rememberScan(buildId: String, summary: ScanResultSummary?) {
        if (summary == null) return
        recordHistory(summary)
        // Part 1 is stored even when Part 2 failed. Refusing to store it threw
        // away 68 verified signatures and 6386 vtables because one later pass
        // hit an error, and left the user with nothing to retry from.
        if (!summary.vtablesResolved) {
            emit(Logs.warn(TAG_CACHE, "Result not cached: the vtable pass did not finish."))
            return
        }
        val stored = resultCache?.store(summary) ?: false
        if (stored) {
            refreshCacheSize()
        }
        _lastBuildId.value = buildId
        viewModelScope.launch(supervisor + ioDispatcher) {
            preferences?.setLastBuildId(buildId)
        }
    }

    /**
     * Adds [summary] to the session history.
     *
     * A re-scan of the same build replaces its earlier entry rather than
     * appearing twice, which would make the diff picker list the same build on
     * both sides.
     */
    private fun recordHistory(summary: ScanResultSummary) {
        history.removeAll { it.buildId == summary.buildId && it.sha256 == summary.sha256 }
        history.addFirst(summary)
        while (history.size > SCAN_HISTORY_LIMIT) {
            history.removeLast()
        }
        _scanHistory.value = history.toList()
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
            return extractNativeLibraryInner(source)
        } finally {
            deleteDownloadedApk()
            refreshStorageUsage()
        }
    }

    /**
     * Deletes the APK this selection downloaded, when the setting is on.
     *
     * Runs on every exit path: success, failure and cancellation. Only a file
     * the APKs page put in the cache is ever deleted; a document the user
     * picked belongs to them and is not ours to remove.
     */
    private fun deleteDownloadedApk() {
        val file = downloadedApk ?: return
        downloadedApk = null
        if (!_deleteApkAfterScan.value) return
        val freed = storage?.delete(file) ?: runCatching {
            if (file.isFile) {
                val bytes = file.length()
                if (file.delete()) bytes else 0L
            } else {
                0L
            }
        }.getOrDefault(0L)
        if (freed > 0L) {
            emit(Logs.info(TAG_CACHE, "Deleted the downloaded APK (${formatBytes(freed)})."))
        }
    }

    /**
     * Deletes the extracted library once nothing needs it any more.
     *
     * The internal table holds every offset the export writes, so after the
     * internal pass succeeded the library is a 300 MB copy of nothing. A failed
     * internal pass keeps it, because "Retry" re-runs that pass from the same
     * file without touching the APK again.
     *
     * @param extracted the library of the finished scan.
     * @param keep whether a retry still needs it.
     */
    private fun releaseLibrary(extracted: ExtractedNativeLibrary?, keep: Boolean) {
        if (keep) return
        lastExtracted = null
        extracted ?: return
        // The engine keeps the last library mapped across JNI calls; the file
        // must not disappear from under that mapping.
        releaseMappedCache()
        val freed = storage?.delete(File(extracted.path)) ?: runCatching {
            val file = File(extracted.path)
            if (file.isFile) {
                val bytes = file.length()
                if (file.delete()) bytes else 0L
            } else {
                0L
            }
        }.getOrDefault(0L)
        if (freed > 0L) {
            emit(Logs.info(TAG_CACHE, "Released the extracted library (${formatBytes(freed)})."))
        }
    }

    private suspend fun extractNativeLibraryInner(source: ApkSource): Boolean {
        try {
            var current = engine.extractNativeLibrary(source.rawFd, cacheDir)
            lastExtracted = current
            lastApkVersion = readApkVersion(source)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Streaming extraction complete. Hash: ${current.sha256}"
                )
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Extracted ${current.path.substringAfterLast('/')} " +
                        "(${current.sizeBytes} bytes)"
                )
            )

            _uiState.update {
                it.copy(
                    phase = ScannerPhase.Scanning,
                    progress = 100,
                    progressTask = "Mapping native library"
                )
            }

            current = ensureExtractedFile(source, current)
            val mapped = engine.mapLibrary(current)
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

            current = ensureExtractedFile(source, current)
            val sections = engine.describeSections(current)
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

            current = ensureExtractedFile(source, current)
            val symbols = engine.describeSymbols(current)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Build ID: ${symbols.identity.buildId} (from ${symbols.identity.source})"
                )
            )
            val definedSymbols = symbols.symbols.defined
            val jniSymbols = symbols.symbols.jni.size
            emit(Logs.info(TAG_RUST, "Symbols: $definedSymbols defined, $jniSymbols JNI exports"))

            val cached = cachedResultFor(
                symbols.identity.buildId,
                sections.engineVersion,
                sections.imageBase
            )
            if (cached != null && cached.internalResolved) {
                emit(Logs.info(TAG_CACHE, "Loaded offsets from previous scan."))
                _uiState.update {
                    it.copy(
                        phase = ScannerPhase.Success,
                        progress = 100,
                        progressTask = "Loaded from cache",
                        result = cached.copy(
                            displayName = source.metadata.displayName,
                            sizeBytes = current.sizeBytes,
                            sha256 = current.sha256,
                            apkSizeBytes = source.metadata.sizeBytes,
                            apkVersion = lastApkVersion,
                            textSection = cached.textSection ?: sections.text,
                            imageBase = sections.imageBase
                        ),
                        internalAvailable = InternalTable.problem(cached) == null,
                        error = null,
                        errorHint = null
                    )
                }
                _uiState.value.result?.let(::recordHistory)
                releaseLibrary(current, keep = false)
                return true
            }

            // A cached scan whose Part 2 failed still carries every Part 1
            // result. Reusing it re-runs only the internal pass, which is the
            // difference between "one pass failed" and "the whole scan is
            // lost": the 300 MB library and the signature scan are not redone.
            val resumed = cached
            if (resumed != null) {
                emit(
                    Logs.info(
                        TAG_CACHE,
                        "Reusing the cached signatures and vtables; " +
                            "re-running only the internal extraction."
                    )
                )
            }

            current = ensureExtractedFile(source, current)
            val scan = if (resumed == null) scanForOffsets(current) else null
            current = ensureExtractedFile(source, current)
            val vtables = if (resumed == null) {
                resolveVtables(current)
            } else {
                VtableResolution(resumed.vtables, resumed.vtableError)
            }
            current = ensureExtractedFile(source, current)
            val internal = analyzeInternal(current)

            val summary = ScanResultSummary(
                displayName = source.metadata.displayName,
                sha256 = current.sha256,
                sizeBytes = current.sizeBytes,
                architecture = mapped.architecture,
                entryPoint = mapped.entryPoint,
                sectionCount = mapped.sectionCount,
                textSection = text,
                buildId = symbols.identity.buildId,
                buildIdSource = symbols.identity.source,
                jniSymbolCount = symbols.symbols.jni.size,
                patternHits = resumed?.patternHits ?: scan?.hits.orEmpty(),
                discardedMatches = resumed?.discardedMatches ?: (scan?.discarded ?: 0),
                peakResidentBytes = scan?.peakResidentBytes ?: internal?.peakResidentBytes,
                vtables = resumed?.vtables ?: vtables.map,
                vtablesResolved = resumed?.vtablesResolved ?: (vtables.error == null),
                vtableError = resumed?.vtableError ?: vtables.error,
                internal = internal?.summary,
                internalResolved = internal != null,
                internalError = lastInternalError,
                symbols = resumed?.symbols ?: symbols.symbols.jni,
                expectedPatterns = resumed?.expectedPatterns ?: patterns.map { it.name },
                verification = resumed?.verification
                    ?: scan?.verification
                    ?: VerificationSummary.NONE,
                apkSizeBytes = source.metadata.sizeBytes,
                apkVersion = lastApkVersion,
                imageBase = sections.imageBase,
                engineVersion = sections.engineVersion,
                jni = resumed?.jni ?: scan?.jni
            )

            _uiState.update {
                it.copy(
                    phase = ScannerPhase.Success,
                    progress = 100,
                    progressTask = "Mapping complete",
                    result = summary,
                    internalAvailable = summary.internalResolved &&
                        InternalTable.problem(summary) == null,
                    internalRetryable = InternalFailure.isRetryable(lastInternalFailure),
                    error = null,
                    errorHint = null
                )
            }

            reportVerification(scan?.verification)
            rememberScan(symbols.identity.buildId, summary)
            // A batch builds each item's archive right after its scan, but the
            // next item may re-extract into the same directory; the sweep at
            // the end of the run releases the libraries in one place.
            releaseLibrary(current, keep = internal == null || _uiState.value.batch != null)
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
     * Confirms the extracted file is still on disk at the recorded size.
     *
     * The cache can be cleared, or the file can be replaced, between two engine
     * calls. Every call after extraction therefore checks first and re-extracts
     * once from the still-open descriptor, which is what turns a transient
     * `cannot open` into a successful pass instead of a silently skipped one.
     */
    private suspend fun ensureExtractedFile(
        source: ApkSource,
        extracted: ExtractedNativeLibrary
    ): ExtractedNativeLibrary {
        val file = File(extracted.path)
        if (file.isFile && file.length() == extracted.sizeBytes) {
            return extracted
        }
        emit(
            Logs.warn(
                TAG_RUST,
                "Extracted library is ${describeFile(file)}; re-extracting once."
            )
        )
        val again = engine.extractNativeLibrary(source.rawFd, cacheDir)
        lastExtracted = again
        val check = File(again.path)
        if (!check.isFile || check.length() != again.sizeBytes) {
            throw com.chimerateam.offsetextractor.data.saf.EngineException(
                com.chimerateam.offsetextractor.data.saf.EngineErrorKind.InvalidElf,
                "the extracted library disappeared before it could be mapped"
            )
        }
        emit(
            Logs.info(
                TAG_RUST,
                "Re-extracted ${check.name} (${check.length()} bytes)."
            )
        )
        return again
    }

    /** Describes a file for the re-extraction log line. */
    private fun describeFile(file: File): String = when {
        !file.exists() -> "missing"
        file.length() != 0L -> "${file.length()} bytes"
        else -> "empty"
    }

    /** Reads `versionName` from the APK manifest, tolerating a failure. */
    private suspend fun readApkVersion(source: ApkSource): String = try {
        engine.readApkVersion(source.rawFd).orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        emit(
            Logs.warn(
                TAG_RUST,
                "APK version unavailable: ${throwable::class.simpleName}: ${throwable.message}"
            )
        )
        ""
    }

    /** Resolves the RTTI vtables and records whether the pass completed. */
    private suspend fun resolveVtables(extracted: ExtractedNativeLibrary): VtableResolution = try {
        // Logged before the call so a process death is pinned to this phase in
        // the live log rather than inferred from where the log stops.
        emit(Logs.info(TAG_RUST, "Resolving RTTI vtables..."))
        val map = engine.resolveVtables(extracted)
        if (map.isEmpty) {
            emit(Logs.info(TAG_RUST, "No RTTI vtables present in this library."))
        } else {
            // One line for the whole pass: thousands of per-class lines pushed
            // the interesting ones out of the pane and cost a recomposition
            // each. The map itself is in the result and in the archive.
            emit(
                Logs.info(
                    TAG_RUST,
                    "Resolved ${map.size} RTTI vtables from ${map.source}."
                )
            )
        }
        VtableResolution(map, null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        // The RTTI pass is the memory-hungry one, and a failure there is not
        // fatal: the offset scan is already complete and is reported on its own.
        // The exception class and the full message are both logged, because the
        // message alone hid an OS errno in the field.
        val detail = "${throwable::class.simpleName}: ${throwable.message ?: "no detail"}"
        emit(Logs.warn(TAG_RUST, "Vtable resolution skipped: $detail"))
        VtableResolution(VtableMap.EMPTY, detail)
    }

    /**
     * Runs the internal C++ extraction.
     *
     * A failure is reported and remembered, but it never discards the JNI scan
     * that already finished.
     */
    private suspend fun analyzeInternal(extracted: ExtractedNativeLibrary): InternalAnalysis? {
        lastInternalError = null
        val options = InternalOptions(
            includeAllFunctions = includeAllFunctions.value,
            generateSignatures = generateSignatures.value,
            maxSlotsPerClass = maxSlotsPerClass.value,
            internalPatterns = internalPatterns,
            stringAnchors = stringAnchors,
            // The engine classifies as it streams, so the export copies its
            // category documents instead of parsing 890,000 rows twice.
            categories = if (categories.isEmpty()) {
                null
            } else {
                com.google.gson.JsonParser.parseString(
                    CategoryRulesJson.encode(categories)
                )
            },
            sourceHeader = CategoryRulesJson.sourceHeader(
                version = lastApkVersion.orEmpty().ifBlank { "unknown" },
                apkName = _uiState.value.selectedApk?.displayName.orEmpty(),
                sha256 = extracted.sha256,
                architecture = "arm64-v8a",
                buildId = _lastBuildId.value
            )
        )
        val output = cache?.internalDir ?: File(cacheDir, "internal")
        return try {
            emit(Logs.info(TAG_RUST, "Extracting internal C++ offsets..."))
            val reporter = progressReporter()
            val analysis = engine.analyzeInternal(extracted, options, output, reporter)
            val summary = analysis.summary
            requireInternalOutput(summary)
            emit(
                Logs.info(
                    TAG_RUST,
                    "Internal: ${summary.classes} classes, ${summary.vtableSlots} slots, " +
                        "${summary.functions} functions (${summary.named} named, " +
                        "${summary.autoNamed} sub_*)."
                )
            )
            emit(
                Logs.info(
                    TAG_RUST,
                    "Pass D/E: ${summary.verified} verified, ${summary.ambiguous} ambiguous, " +
                        "${summary.notFound} not found; ${summary.signatures} signatures."
                )
            )
            analysis
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val detail = "${throwable::class.simpleName}: ${throwable.message ?: "no detail"}"
            lastInternalFailure = throwable
            lastInternalError = InternalFailure.describe(throwable) ?: detail
            emit(Logs.warn(TAG_RUST, "Internal extraction failed: $detail"))
            null
        }
    }

    /**
     * Confirms the engine's output is on disk with the size it reported.
     *
     * The table is the only copy of the internal offsets: a pass that reports
     * 883,574 functions but leaves nothing on disk would otherwise produce a
     * "successful" scan and a ZIP with no internal data in it.
     *
     * Two shapes are valid. When the engine classifies as it writes — the
     * normal case, because the app always ships category rules — the rows live
     * in one document per category and there is no single table at all. Only a
     * scan without category rules produces the NDJSON table. Checking `path`
     * unconditionally is what turned a finished category pass into
     * "The internal offsets file is gone (. 0 bytes)".
     *
     * @param summary the engine's summary.
     * @throws java.io.IOException when nothing usable was written.
     */
    private fun requireInternalOutput(summary: InternalSummary) {
        if (summary.shards.isNotEmpty()) {
            var bytes = 0L
            for (shard in summary.shards) {
                val file = File(shard.path)
                val onDisk = file.length()
                if (!file.isFile || onDisk <= 0L) {
                    throw java.io.IOException(
                        "The ${shard.label} offsets file is gone " +
                            "(${shard.path}, $onDisk bytes). Re-run the internal extraction."
                    )
                }
                if (shard.bytes > 0L && onDisk != shard.bytes) {
                    throw java.io.IOException(
                        "The ${shard.label} offsets file is truncated (${shard.path}, " +
                            "$onDisk bytes, ${shard.bytes} expected). " +
                            "Re-run the internal extraction."
                    )
                }
                bytes += onDisk
            }
            emit(
                Logs.info(
                    TAG_RUST,
                    "Internal tables: ${summary.shards.size} category files, " +
                        "${formatBytes(bytes)} ${summary.rows} rows"
                )
            )
            return
        }

        val path = summary.path
        val file = File(path)
        val bytes = file.length()
        if (path.isBlank() || !file.isFile || bytes <= 0L) {
            throw java.io.IOException(
                "The internal offsets file is gone ($path, $bytes bytes, " +
                    "${summary.rows} rows expected). Re-run the internal extraction."
            )
        }
        if (summary.tableBytes > 0L && bytes != summary.tableBytes) {
            throw java.io.IOException(
                "The internal offsets file is truncated ($path, $bytes bytes, " +
                    "${summary.tableBytes} expected). Re-run the internal extraction."
            )
        }
        emit(
            Logs.info(
                TAG_RUST,
                "Internal table: ${file.name} ${formatBytes(bytes)} " +
                    "${summary.rows} rows"
            )
        )
    }

    /**
     * Re-runs only Part 2, reusing the extracted library when it is still valid.
     *
     * This is what the "Internal extraction failed. Retry" button calls: the APK
     * is not re-extracted unless its library is actually gone.
     */
    fun retryInternalExtraction() {
        val result = _uiState.value.result
        if (result == null) {
            emit(Logs.warn(TAG_UI, "There is no scan to extend."))
            return
        }
        // A deterministic failure fails the same way every time. Offering the
        // button anyway is what produced a retry loop with no exit; the reason
        // is shown instead.
        if (!InternalFailure.isRetryable(lastInternalFailure)) {
            val reason = result.internalError ?: "The internal pass cannot be retried."
            emit(Logs.warn(TAG_UI, reason))
            _uiState.update {
                it.copy(exportMessage = reason, internalRetryable = false)
            }
            return
        }
        val extracted = lastExtracted
        if (extracted == null || !File(extracted.path).isFile) {
            emit(Logs.warn(TAG_UI, "The extracted library is gone; select the APK again."))
            _uiState.update { it.copy(exportMessage = "Select the APK again to retry.") }
            return
        }
        viewModelScope.launch(supervisor + ioDispatcher) {
            _uiState.update { it.copy(progressTask = "Extracting internal offsets", progress = 0) }
            val analysis = analyzeInternal(extracted)
            val current = _uiState.value.result ?: return@launch
            val updated = current.copy(
                internal = analysis?.summary ?: current.internal,
                internalResolved = analysis != null,
                internalError = lastInternalError
            )
            _uiState.update {
                it.copy(
                    result = updated,
                    internalAvailable = updated.internalResolved &&
                        InternalTable.problem(updated) == null,
                    internalRetryable = InternalFailure.isRetryable(lastInternalFailure),
                    progress = 100,
                    progressTask = if (analysis != null) {
                        "Internal offsets ready"
                    } else {
                        "Internal extraction failed"
                    }
                )
            }
            if (analysis != null) {
                rememberScan(updated.buildId, updated)
            } else {
                _uiState.update { it.copy(exportMessage = "Internal extraction failed.") }
            }
        }
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
        val reporter = progressReporter()
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
        reportJniCoverage(outcome.jni)
        return outcome
    }

    /**
     * Writes the JNI coverage block to the live log.
     *
     * The union is what the archive holds, so the counts are the honest answer
     * to "did the app find everything": a pattern that found nothing while its
     * symbol exists is named, because that pattern needs updating.
     *
     * @param report the engine's union report, or `null` when it sent none.
     */
    private fun reportJniCoverage(report: JniReport?) {
        if (report == null) return
        val coverage = report.coverage
        emit(
            Logs.info(
                TAG_RUST,
                "JNI coverage: ${coverage.exports} exports, ${coverage.entryPoints} lifecycle, " +
                    "${coverage.registered} registered, ${coverage.patternsMatched} of " +
                    "${coverage.patterns} patterns, ${coverage.union} offsets in total."
            )
        )
        if (coverage.patternsStale.isNotEmpty()) {
            emit(
                Logs.warn(
                    TAG_RUST,
                    "${coverage.patternsStale.size} stale signature(s) replaced by their " +
                        "symbol: ${coverage.patternsStale.joinToString()}."
                )
            )
        }
        if (coverage.truncated) {
            emit(Logs.warn(TAG_RUST, "A JNI source hit its cap; the union is incomplete."))
        }
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

    /**
     * Builds the callback the engine reports progress through.
     *
     * A stage on a large library reports thousands of times, and every report
     * used to update the screen state, write a log line and append it to the log
     * file. The pane then spent its time laying out rows while the scan waited,
     * which is what made a running scan look frozen. The gate keeps the bar
     * moving and the log readable instead.
     *
     * @return the callback to hand to the engine.
     */
    private fun progressReporter(): ProgressCallback {
        val gate = ProgressGate(
            forward = { percent, message, writeLine ->
                _uiState.update { it.copy(progress = percent, progressTask = message) }
                if (writeLine) emit(Logs.info(TAG_RUST, message))
            }
        )
        return ProgressCallback { percent, message -> gate.report(percent, message) }
    }

    private fun emit(entry: LogEntry) {
        val stamped = if (entry.scanId.isEmpty()) entry.copy(scanId = scanIdState.value) else entry
        logFile?.append(stamped)
        _logEntries.update { existing ->
            val appended = existing + stamped
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
     * Every line of the current run, including the ones the pane dropped.
     *
     * @return the spilled log when one is configured, otherwise the retained
     *   entries.
     */
    fun logText(): String {
        val spilled = logFile?.read().orEmpty()
        if (spilled.isNotBlank()) return spilled
        return _logEntries.value.joinToString("\n") { entry ->
            "${LogTimestamp.format(entry.timestampMillis)} ${entry.render()}"
        }
    }

    /**
     * Starts a new scan: new identifier, and the previous result cleared.
     *
     * The result, the log lines and the export job all carry the same
     * identifier, so the screen can never show one scan's offsets beside
     * another scan's progress.
     */
    private fun beginScan() {
        scanIdState.value = "scan-" + clock() + "-" + (++scanCounter)
        _logEntries.update { existing -> existing.filter { it.scanId == scanIdState.value } }
        // The file backs Copy and Share, so it holds one run: keeping older
        // runs would hand the user a log that does not describe the result on
        // screen.
        logFile?.delete()
    }

    /** Result of the vtable pass: the map plus any failure detail. */
    private data class VtableResolution(val map: VtableMap, val error: String?)

    /**
     * Creates the production ViewModel for an Activity or composable.
     *
     * @param safAccess storage-access implementation.
     * @param engine extraction engine.
     * @param cacheDir directory that receives extracted native libraries.
     * @param patterns signatures the scanner looks for; empty skips the scan.
     */
    companion object {

        /** Minimum gap between two low-memory notices. */
        private const val MEMORY_NOTICE_INTERVAL_MS = 60_000L

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
            patterns: List<PatternSpec> = emptyList(),
            internalPatterns: List<InternalPatternSpec> = emptyList(),
            stringAnchors: List<StringAnchorSpec> = emptyList(),
            categories: List<CategoryRule> = emptyList(),
            exportStarter: ExportStarter? = null,
            storage: StorageManager? = null,
            logFile: LogFile? = null
        ): MainViewModel = MainViewModel(
            safAccess = safAccess,
            engine = engine,
            cacheDir = cacheDir,
            patterns = patterns,
            internalPatterns = internalPatterns,
            stringAnchors = stringAnchors,
            writer = writer,
            cache = cache,
            preferences = preferences,
            categories = categories,
            exportStarter = exportStarter,
            storage = storage,
            logFile = logFile
        )
    }
}

/**
 * Bytes decoded for the inspector's disassembly preview.
 *
 * 256 bytes is 64 instructions: enough for a full prologue and a few branches
 * without turning a row tap into a visible wait.
 */
private const val DISASSEMBLY_PREVIEW_BYTES = 256

/** Completed scans kept for the diff screen. */
private const val SCAN_HISTORY_LIMIT = 5
