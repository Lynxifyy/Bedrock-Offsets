package com.chimerateam.offsetextractor.ui.log

import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Records an uncaught exception in the same file the log pane copies.
 *
 * A crash leaves no dialog a report can be built from, and the tablet has no
 * easy way to reach `logcat`, so the last thing an uncaught exception does is
 * append its type, message and stack to `live.log`. The text is one line per
 * stack frame so the pane's Copy action carries it intact.
 *
 * @param directory directory holding `live.log`.
 */
class CrashLog(private val directory: File) {

    private val file: LogFile get() = LogFile(directory)

    /**
     * Installs the handler for this process, chaining to whatever was there.
     *
     * Installing twice is harmless; the handler is replaced, not stacked.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
                .onFailure { failure ->
                    Log.w(TAG, "cannot record the crash: ${failure.message}")
                }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Appends a crash to the log file.
     *
     * @param thread the thread that failed.
     * @param error the throwable that escaped.
     */
    fun record(thread: Thread, error: Throwable) {
        val log = file
        val now = System.currentTimeMillis()
        log.append(
            LogEntry(
                now,
                LogLevel.Error,
                TAG,
                "CRASH on ${thread.name}: ${error.javaClass.name}: ${error.message.orEmpty()}"
            )
        )
        log.append(
            LogEntry(now, LogLevel.Error, TAG, "CRASH at ${Instant.now()} (${buildFingerprint()})")
        )
        for (line in error.stackTraceToString().lineSequence()) {
            if ("\tat " in line || line.startsWith(error.javaClass.name)) {
                log.append(LogEntry(now, LogLevel.Error, TAG, "CRASH $line"))
            }
        }
        log.append(LogEntry(now, LogLevel.Error, TAG, "CRASH end of report"))
    }

    private fun buildFingerprint(): String =
        "${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"

    private companion object {
        const val TAG = "CRASH"
    }
}
