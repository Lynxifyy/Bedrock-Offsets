package com.chimerateam.offsetextractor.export

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chimerateam.offsetextractor.MainActivity
import com.chimerateam.offsetextractor.data.export.CategoryRule
import com.chimerateam.offsetextractor.data.export.Classifier
import com.chimerateam.offsetextractor.data.export.ExportJobRepository
import com.chimerateam.offsetextractor.data.export.ExportJobStore
import com.chimerateam.offsetextractor.data.export.ExportProgress
import com.chimerateam.offsetextractor.data.export.ExportStage
import com.chimerateam.offsetextractor.data.export.ExportState
import com.chimerateam.offsetextractor.data.export.InternalTable
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.export.OffsetZipBuilder
import com.chimerateam.offsetextractor.data.export.PersistedExportJob
import com.chimerateam.offsetextractor.data.storage.StorageManager
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.util.formatBytes
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a download from start to finish.
 *
 * The work is deliberately outside `viewModelScope`: closing the activity,
 * swiping the app away or rotating must not truncate the archive. The service
 * keeps its own [CoroutineScope] and a foreground notification, so Android
 * keeps the process alive for the duration.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastNotificationMillis = 0L
    private var lastPersistMillis = 0L

    private val store by lazy { ExportJobStore(File(filesDir, JOB_FILE)) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            ExportJobRepository.cancel()
            store.load()?.let { store.save(it.copy(state = ExportState.Cancelled.name)) }
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val destination = intent?.getStringExtra(EXTRA_DESTINATION)
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME)
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()
        val retryOnly = intent?.getBooleanExtra(EXTRA_RETRY_ONLY, false) ?: false
        if (destination.isNullOrBlank() || fileName.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildProgressNotification(
                fileName = fileName,
                stage = if (retryOnly) ExportStage.Saving else ExportStage.Building,
                bytesDone = 0,
                bytesTotal = 0,
                fraction = 0f
            )
        )

        job = scope.launch {
            runJob(
                destination = Uri.parse(destination),
                fileName = fileName,
                jobId = jobId,
                retryOnly = retryOnly
            )
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("UsableSpace")
    private suspend fun runJob(
        destination: Uri,
        fileName: String,
        jobId: String,
        retryOnly: Boolean
    ) {
        val exports = File(cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val zip = File(exports, fileName)
        val partial = File(exports, "$fileName.part")
        var zipFiles = 0
        var zipRows = 0
        var zipBytes = 0L
        var stage = if (retryOnly) ExportStage.Saving else ExportStage.Building
        try {
            if (!retryOnly) {
                val summary = ExportJobRepository.pendingSummary
                    ?: throw IOException("The scan is no longer available; run it again.")
                val needed = StorageManager.estimateArchiveBytes(summary.sizeBytes)
                val check = StorageManager.preflight(needed, exports)
                if (!check.enough) {
                    throw StorageManager.noSpaceException(check)
                }
                // The table can be cleared between the scan and the tap on
                // Download; an archive without it must never be written.
                InternalTable.require(summary)
                val categories = ExportJobRepository.pendingCategories
                val work = File(exports, "categories-$jobId").apply { mkdirs() }
                persist(
                    jobId,
                    fileName,
                    destination,
                    ExportStage.Building,
                    0,
                    0,
                    ExportState.Running,
                    zipPath = null
                )
                val builder = OffsetZipBuilder(Classifier(categories), work) { line ->
                    android.util.Log.i(TAG_ZIP, line)
                }
                val result = builder.build(
                    summary = summary,
                    target = partial,
                    includeCombined = ExportJobRepository.includeCombined
                ) { fraction, _ ->
                    val overall = fraction * BUILD_SHARE
                    publish(
                        stage = ExportStage.Building,
                        bytesDone = partial.length(),
                        bytesTotal = 0,
                        fraction = overall,
                        fileName = fileName,
                        destination = destination,
                        jobId = jobId
                    )
                    notifyProgress(fileName, ExportStage.Building, overall, "Building ZIP…")
                    persist(
                        jobId,
                        fileName,
                        destination,
                        ExportStage.Building,
                        partial.length(),
                        0,
                        ExportState.Running,
                        zipPath = null
                    )
                }
                if (!result.verified) {
                    throw IOException("The archive failed verification; nothing was saved.")
                }
                zipFiles = result.fileCount
                zipRows = result.internalRows
                zipBytes = result.bytes
                if (zip.exists()) zip.delete()
                stage = ExportStage.Saving
                if (!partial.renameTo(zip)) {
                    throw IOException("Could not finalise the archive in the cache.")
                }
                work.deleteRecursively()
                persist(
                    jobId,
                    fileName,
                    destination,
                    ExportStage.Saving,
                    zip.length(),
                    zip.length(),
                    ExportState.Running,
                    zip.absolutePath
                )
            } else {
                val persisted = store.load()
                if (persisted?.zipPath == null || !File(persisted.zipPath).isFile) {
                    throw IOException("The cached archive is gone; run the scan again.")
                }
            }

            if (!zip.isFile) {
                throw IOException("The cached archive is missing; run the scan again.")
            }

            val total = zip.length()
            var done = 0L
            val output = contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("The chosen location returned no output stream.")
            zip.inputStream().use { input ->
                BufferedOutputStream(output, COPY_BUFFER_BYTES).use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val overall = BUILD_SHARE + SAVE_SHARE * (done.toFloat() / total)
                        publish(
                            stage = ExportStage.Saving,
                            bytesDone = done,
                            bytesTotal = total,
                            fraction = overall,
                            fileName = fileName,
                            destination = destination,
                            jobId = jobId
                        )
                        notifyProgress(fileName, ExportStage.Saving, overall, "Saving…")
                        persist(
                            jobId,
                            fileName,
                            destination,
                            ExportStage.Saving,
                            done,
                            total,
                            ExportState.Running,
                            zip.absolutePath
                        )
                    }
                    out.flush()
                }
            }
            runCatching {
                contentResolver.openFileDescriptor(destination, "rw")?.use { descriptor ->
                    descriptor.fileDescriptor.sync()
                }
            }

            android.util.Log.i(
                TAG_ZIP,
                "ZIP: saved $total bytes to $fileName ($zipFiles files, $zipRows rows)"
            )
            ExportJobRepository.complete(
                zipPath = zip.absolutePath,
                files = zipFiles,
                internalRows = zipRows,
                archiveBytes = zipBytes
            )
            store.save(
                PersistedExportJob(
                    jobId = jobId,
                    zipName = fileName,
                    destinationUri = destination.toString(),
                    stage = ExportStage.Saving.name,
                    bytesDone = total,
                    bytesTotal = total,
                    state = ExportState.Done.name,
                    zipPath = zip.absolutePath
                )
            )
            // The user's copy is written and synced, so the cache copy is now
            // dead weight: one archive is a fifth of a gigabyte on the largest
            // build. An interrupted copy keeps it, which is what makes Retry
            // resume instead of rescanning.
            runCatching { if (zip.isFile) zip.delete() }
            store.clear()
            ExportJobRepository.publish(
                ExportJobRepository.snapshot().copy(zipPath = null)
            )
            notifyFinished(fileName, success = true)
        } catch (cancelled: CancellationException) {
            deleteDestination(destination)
            ExportJobRepository.cancel()
            store.load()?.let { store.save(it.copy(state = ExportState.Cancelled.name)) }
            notifyFinished(fileName, success = false, message = "Download cancelled")
        } catch (outOfMemory: OutOfMemoryError) {
            deleteDestination(destination)
            runCatching { partial.delete() }
            // The heap numbers are what turns "it crashed" into a diagnosis:
            // the reported 77 MB allocation failed with 6 MB free of a 399 MB
            // footprint, so the log line has to carry the same three values.
            val runtime = Runtime.getRuntime()
            android.util.Log.e(
                TAG_ZIP,
                "Out of memory while building $fileName at stage $stage " +
                    "(used ${formatBytes(runtime.totalMemory() - runtime.freeMemory())}, " +
                    "free ${formatBytes(runtime.freeMemory())}, " +
                    "max ${formatBytes(runtime.maxMemory())})",
                outOfMemory
            )
            ExportJobRepository.fail(
                "The archive could not be built because the app ran out of memory. " +
                    "Close other apps and try again.",
                interrupted = false
            )
            store.clear()
            notifyFinished(
                fileName,
                success = false,
                message = "the app ran out of memory"
            )
        } catch (error: Throwable) {
            deleteDestination(destination)
            // A partial archive is never resumable: the ZIP central directory
            // only exists once the stream is closed.
            runCatching { partial.delete() }
            val noSpace = StorageManager.isNoSpace(error)
            val message = if (noSpace) {
                StorageManager.noSpaceMessage(
                    StorageManager.estimateArchiveBytes(zip.length()),
                    runCatching { exports.usableSpace }.getOrDefault(0L)
                )
            } else {
                val friendly = com.chimerateam.offsetextractor.ui.error.ErrorMapper
                    .fromExportFailure(error)
                listOfNotNull(friendly.message, friendly.hint).joinToString(" ")
            }
            android.util.Log.e(TAG_ZIP, "Export failed at stage $stage: $message", error)
            ExportJobRepository.fail(message, interrupted = !noSpace && zip.isFile)
            if (!noSpace && zip.isFile) {
                store.load()?.let { store.save(it.copy(state = ExportState.Interrupted.name)) }
            } else {
                store.clear()
            }
            notifyFinished(fileName, success = false, message = message)
        }
    }

    private fun publish(
        stage: ExportStage,
        bytesDone: Long,
        bytesTotal: Long,
        fraction: Float,
        fileName: String,
        destination: Uri,
        jobId: String
    ) {
        ExportJobRepository.publish(
            ExportProgress(
                stage = stage,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                fraction = fraction.coerceIn(0f, 1f),
                fileName = fileName,
                state = ExportState.Running,
                destinationUri = destination.toString(),
                zipPath = null,
                jobId = jobId
            )
        )
    }

    private fun persist(
        jobId: String,
        fileName: String,
        destination: Uri,
        stage: ExportStage,
        done: Long,
        total: Long,
        state: ExportState,
        zipPath: String?
    ) {
        val now = System.currentTimeMillis()
        if (state == ExportState.Running && now - lastPersistMillis < PERSIST_INTERVAL_MILLIS) {
            return
        }
        lastPersistMillis = now
        val summary = ExportJobRepository.pendingSummary
        store.save(
            PersistedExportJob(
                jobId = jobId,
                zipName = fileName,
                destinationUri = destination.toString(),
                stage = stage.name,
                bytesDone = done,
                bytesTotal = total,
                state = state.name,
                zipPath = zipPath,
                version = summary?.let(OffsetExporter::versionLabel).orEmpty()
            )
        )
    }

    private fun deleteDestination(destination: Uri) {
        if (destination.scheme == "file") {
            destination.path?.let { path -> runCatching { File(path).delete() } }
            return
        }
        runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }
            .recoverCatching { contentResolver.delete(destination, null, null) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Download progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress of the BEDROCK_OFFSETS archive download"
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(
        fileName: String,
        stage: ExportStage,
        bytesDone: Long,
        bytesTotal: Long,
        fraction: Float,
        text: String? = null
    ): Notification {
        val cancel = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = text ?: if (stage == ExportStage.Building) "Building ZIP…" else "Saving…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(
                if (bytesTotal > 0) {
                    "$body ${formatBytes(bytesDone)} / ${formatBytes(bytesTotal)}"
                } else {
                    body
                }
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(1000, (fraction * 1000).toInt().coerceIn(0, 1000), false)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun notifyProgress(
        fileName: String,
        stage: ExportStage,
        fraction: Float,
        text: String
    ) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationMillis < NOTIFICATION_INTERVAL_MILLIS) return
        lastNotificationMillis = now
        val progress = ExportJobRepository.snapshot()
        val notification = buildProgressNotification(
            fileName = fileName,
            stage = stage,
            bytesDone = progress.bytesDone,
            bytesTotal = progress.bytesTotal,
            fraction = fraction,
            text = text
        )
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun notifyFinished(fileName: String, success: Boolean, message: String? = null) {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            success -> "Saved $fileName — tap to open"
            message != null -> "Download failed: $message — tap to retry"
            else -> "Download failed — tap to retry"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(open)
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }
    companion object {
        /** Tag used for the ZIP stage lines. */
        private const val TAG_ZIP = "OffsetZip"

        /** Channel used for both progress and the final result. */
        const val CHANNEL_ID = "export_progress"

        /** Action that cancels the running job. */
        const val ACTION_CANCEL = "com.chimerateam.offsetextractor.export.CANCEL"

        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_CANCEL = 4202
        private const val REQUEST_OPEN = 4203
        private const val JOB_FILE = "export-job.json"
        private const val EXPORT_DIRECTORY = "exports"
        private const val EXTRA_DESTINATION = "destination"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_RETRY_ONLY = "retry_only"
        private const val BUILD_SHARE = 0.8f
        private const val SAVE_SHARE = 0.2f
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val NOTIFICATION_INTERVAL_MILLIS = 100L
        private const val PERSIST_INTERVAL_MILLIS = 500L

        /**
         * Starts a fresh export.
         *
         * @param context any context.
         * @param summary the completed scan.
         * @param categories classifier rules.
         * @param includeCombined whether to add the combined document.
         * @param destination the document the user chose.
         * @param fileName name of the ZIP.
         */
        fun start(
            context: Context,
            summary: ScanResultSummary,
            categories: List<CategoryRule>,
            includeCombined: Boolean,
            destination: Uri,
            fileName: String
        ) {
            val jobId = UUID.randomUUID().toString()
            ExportJobRepository.prepare(
                summary = summary,
                categories = categories,
                includeCombined = includeCombined,
                jobId = jobId,
                fileName = fileName,
                destinationUri = destination.toString()
            )
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra(EXTRA_DESTINATION, destination.toString())
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_JOB_ID, jobId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Retries Stage 2 for the persisted job.
         *
         * @param context any context.
         */
        fun retry(context: Context) {
            val store = ExportJobStore(File(context.filesDir, JOB_FILE))
            val job = store.load() ?: return
            ExportJobRepository.restore(job)
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra(EXTRA_DESTINATION, job.destinationUri)
                putExtra(EXTRA_FILE_NAME, job.zipName)
                putExtra(EXTRA_JOB_ID, job.jobId)
                putExtra(EXTRA_RETRY_ONLY, true)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Discards the persisted job and its cached archive.
         *
         * @param context any context.
         */
        fun discard(context: Context) {
            val store = ExportJobStore(File(context.filesDir, JOB_FILE))
            store.load()?.zipPath?.let { path -> runCatching { File(path).delete() } }
            store.clear()
            ExportJobRepository.clear()
            runCatching {
                context.stopService(Intent(context, ExportService::class.java))
            }
        }

        /**
         * Cancels the running job.
         *
         * @param context any context.
         */
        fun cancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ExportService::class.java).setAction(ACTION_CANCEL)
                )
            }
        }

        /** Reads the persisted job, used to restore the interrupted card. */
        fun loadJob(context: Context): PersistedExportJob? =
            ExportJobStore(File(context.filesDir, JOB_FILE)).load()
    }
}
