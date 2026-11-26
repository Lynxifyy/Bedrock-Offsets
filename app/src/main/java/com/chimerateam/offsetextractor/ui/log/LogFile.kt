package com.chimerateam.offsetextractor.ui.log

import android.util.Log
import java.io.File

/**
 * Append-only log file behind the live log.
 *
 * The pane keeps the last [MAX_LOG_ENTRIES][com.chimerateam.offsetextractor.ui
 * .viewmodel.MAX_LOG_ENTRIES] lines in memory, which is far less than a scan of
 * a 1 GB library produces. Every line is also appended here, so Copy and Share
 * still carry the beginning of the run after the pane has dropped it.
 *
 * The file is trimmed to its last [maxBytes] when it grows past them, so a
 * long-lived install cannot fill the disk with logs.
 *
 * @param directory directory that receives `live.log`; created on first write.
 * @param maxBytes size above which the file is rewritten with its newest half.
 */
class LogFile(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    /** The file lines are appended to. */
    val file: File get() = File(directory, FILE_NAME)

    /**
     * Appends one line.
     *
     * A logging failure must never fail the scan it is describing, so a write
     * error is reported once and then ignored.
     *
     * @param entry the line to append.
     */
    fun append(entry: LogEntry) {
        runCatching {
            directory.mkdirs()
            val target = file
            if (target.length() > maxBytes) trim(target)
            target.appendText(
                "${LogTimestamp.format(entry.timestampMillis)} ${entry.render()}\n"
            )
        }.onFailure { error ->
            Log.w(TAG, "cannot append to ${file.name}: ${error.message}")
        }
    }

    /**
     * Reads everything the file holds.
     *
     * @return the file's text, or an empty string when nothing was written.
     */
    fun read(): String = runCatching {
        if (file.isFile) file.readText() else ""
    }.getOrDefault("")

    /** Size of the log file in bytes. */
    fun sizeBytes(): Long = if (file.isFile) file.length() else 0L

    /** Deletes the file; used by the storage cleanup. */
    fun delete(): Long {
        val bytes = sizeBytes()
        runCatching { file.delete() }
            .onFailure { Log.w(TAG, "cannot delete ${file.name}: ${it.message}") }
        return bytes
    }

    /**
     * Rewrites [target] with its newest half.
     *
     * @param target file that grew past [maxBytes].
     */
    private fun trim(target: File) {
        val text = target.readText()
        val keep = text.takeLast((maxBytes / 2).toInt())
        val fromNewline = keep.indexOf('\n')
        target.writeText(if (fromNewline >= 0) keep.substring(fromNewline + 1) else keep)
    }

    /** Constants for the on-disk log. */
    companion object {
        private const val TAG = "LogFile"

        /** File name inside the log directory. */
        const val FILE_NAME = "live.log"

        /** Four megabytes of text is roughly a hundred thousand lines. */
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
    }
}
