package com.chimerateam.offsetextractor.data.export

import android.net.Uri
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which half of the export is running. */
enum class ExportStage {
    /** Building the ZIP in the cache. */
    Building,

    /** Copying the cached ZIP to the user's chosen document. */
    Saving
}

/** Lifecycle of one export job. */
enum class ExportState {
    /** Nothing has been started. */
    Idle,

    /** Work is in progress. */
    Running,

    /** The ZIP was written and verified. */
    Done,

    /** The job failed and cannot be resumed without a rescan. */
    Failed,

    /** The copy was interrupted; the cached ZIP is intact and retryable. */
    Interrupted,

    /** The user cancelled. */
    Cancelled
}

/**
 * Progress of the active export.
 *
 * @property stage which half is running.
 * @property bytesDone bytes copied in the current stage.
 * @property bytesTotal bytes expected in the current stage.
 * @property fraction overall completion in 0..1.
 * @property fileName name of the ZIP.
 * @property state lifecycle state.
 * @property error user-facing failure detail, when any.
 * @property destinationUri the user's document, as text.
 * @property zipPath cached ZIP path, when it exists.
 * @property jobId identifier used by the persisted job.
 * @property files files written to the archive, zero until it is built.
 * @property internalRows internal records the archive holds.
 * @property archiveBytes size of the finished archive.
 */
data class ExportProgress(
    val stage: ExportStage = ExportStage.Building,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val fraction: Float = 0f,
    val fileName: String = "",
    val state: ExportState = ExportState.Idle,
    val error: String? = null,
    val destinationUri: String? = null,
    val zipPath: String? = null,
    val jobId: String = "",
    val files: Int = 0,
    val internalRows: Int = 0,
    val archiveBytes: Long = 0L
) {
    /** Whether a job is running. */
    val isRunning: Boolean get() = state == ExportState.Running

    /** Whether the finished file is available. */
    val isFinished: Boolean get() = state == ExportState.Done
}

/**
 * Starts the foreground export.
 *
 * An interface rather than a direct service call so the ViewModel can be tested
 * without Android's service machinery.
 */
fun interface ExportStarter {
    /**
     * @param destination the document the user chose.
     * @param summary the completed scan.
     * @param categories classifier rules.
     * @param includeCombined whether to add the combined document.
     */
    fun start(
        destination: Uri,
        summary: ScanResultSummary,
        categories: List<CategoryRule>,
        includeCombined: Boolean
    )
}

/** A job as persisted between process lifetimes. */
data class PersistedExportJob(
    val jobId: String,
    val zipName: String,
    val destinationUri: String,
    val stage: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val state: String,
    val zipPath: String? = null,
    val version: String = ""
)

/**
 * The single source of truth for export progress.
 *
 * The foreground service owns the work; the ViewModel and the UI only observe
 * [progress]. The payload needed to build the archive lives here because the
 * service runs in the same process and keeps it alive.
 */
object ExportJobRepository {

    private val _progress = MutableStateFlow(ExportProgress())

    /** Latest progress snapshot. */
    val progress: StateFlow<ExportProgress> = _progress.asStateFlow()

    @Volatile
    var pendingSummary: ScanResultSummary? = null
        private set

    @Volatile
    var pendingCategories: List<CategoryRule> = emptyList()
        private set

    @Volatile
    var includeCombined: Boolean = false
        private set

    /**
     * Records a job about to be started by the service.
     *
     * @param summary the scan to export.
     * @param categories the classifier rules.
     * @param includeCombined whether a combined file is requested.
     * @param jobId identifier of the persisted job.
     * @param fileName name of the ZIP.
     * @param destinationUri the user's document.
     */
    fun prepare(
        summary: ScanResultSummary,
        categories: List<CategoryRule>,
        includeCombined: Boolean,
        jobId: String,
        fileName: String,
        destinationUri: String
    ) {
        pendingSummary = summary
        pendingCategories = categories
        this.includeCombined = includeCombined
        _progress.value = ExportProgress(
            stage = ExportStage.Building,
            fileName = fileName,
            state = ExportState.Running,
            destinationUri = destinationUri,
            jobId = jobId
        )
    }

    /** Replaces the progress snapshot. */
    fun publish(progress: ExportProgress) {
        _progress.value = progress
    }

    /**
     * Marks the job finished.
     *
     * @param zipPath cached archive that was saved.
     * @param files files the archive holds, including the manifest.
     * @param internalRows internal records it carries.
     * @param archiveBytes size of the archive.
     */
    fun complete(zipPath: String, files: Int = 0, internalRows: Int = 0, archiveBytes: Long = 0L) {
        _progress.value = _progress.value.copy(
            stage = ExportStage.Saving,
            fraction = 1f,
            state = ExportState.Done,
            zipPath = zipPath,
            error = null,
            files = files,
            internalRows = internalRows,
            archiveBytes = archiveBytes
        )
        dropPayload()
    }

    /**
     * Marks the job failed or interrupted.
     *
     * @param message user-facing detail.
     * @param interrupted whether the cached ZIP survives for a retry.
     */
    fun fail(message: String, interrupted: Boolean) {
        _progress.value = _progress.value.copy(
            state = if (interrupted) ExportState.Interrupted else ExportState.Failed,
            error = message
        )
        dropPayload()
    }

    /** Marks the job cancelled by the user. */
    fun cancel() {
        _progress.value = _progress.value.copy(state = ExportState.Cancelled)
        dropPayload()
    }

    /** Restores an interrupted job so the Scanner tab can offer a retry. */
    fun restore(job: PersistedExportJob) {
        _progress.value = ExportProgress(
            stage = if (job.stage == ExportStage.Saving.name) {
                ExportStage.Saving
            } else {
                ExportStage.Building
            },
            bytesDone = job.bytesDone,
            bytesTotal = job.bytesTotal,
            fraction = if (job.bytesTotal > 0) {
                job.bytesDone.toFloat() / job.bytesTotal
            } else {
                0f
            },
            fileName = job.zipName,
            state = if (job.state == ExportState.Running.name) {
                ExportState.Interrupted
            } else {
                runCatching { ExportState.valueOf(job.state) }
                    .getOrDefault(ExportState.Interrupted)
            },
            destinationUri = job.destinationUri,
            zipPath = job.zipPath,
            jobId = job.jobId
        )
    }

    /** Drops the current job, used by Discard. */
    fun clear() {
        dropPayload()
        _progress.value = ExportProgress()
    }

    /**
     * Releases the scan payload.
     *
     * The scan is only needed while building; keeping it alive after the job
     * ends pins the whole internal table in memory.
     */
    private fun dropPayload() {
        pendingSummary = null
        pendingCategories = emptyList()
        includeCombined = false
    }

    /** Current snapshot, read by the service when it starts. */
    fun snapshot(): ExportProgress = _progress.value
}

/**
 * Persists the active job as a small JSON file.
 *
 * @param file destination, normally in `filesDir`.
 */
class ExportJobStore(private val file: File) {

    private val gson = Gson()

    /** Writes [job], replacing any previous record. */
    fun save(job: PersistedExportJob) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(job))
        }
    }

    /** Reads the last record, or `null` when there is none. */
    fun load(): PersistedExportJob? = runCatching {
        if (!file.isFile) return@runCatching null
        gson.fromJson(JsonParser.parseString(file.readText()), PersistedExportJob::class.java)
    }.getOrNull()

    /** Removes the record. */
    fun clear() {
        runCatching { file.delete() }
    }
}
