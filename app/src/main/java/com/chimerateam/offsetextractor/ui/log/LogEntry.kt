package com.chimerateam.offsetextractor.ui.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Severity of a live-log line. */
enum class LogLevel {
    /** Ordinary progress information. */
    Info,

    /** Something the user should notice. */
    Warning,

    /** An operation failed. */
    Error
}

/**
 * One line in the live log.
 *
 * @property timestampMillis wall-clock time the entry was created.
 * @property level severity used for colour coding.
 * @property tag short origin tag such as `SAF`, `RUST` or `UI`.
 * @property message the line body.
 */
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    /** Formats the entry the way it appears in the log pane. */
    fun render(): String = "[$tag] $message"
}

/** Time formatting for log timestamps, kept out of the data class. */
object LogTimestamp {
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Formats [millis] as a wall-clock timestamp.
     *
     * @param millis epoch milliseconds.
     * @return the timestamp rendered as `HH:mm:ss.SSS`.
     */
    @Synchronized
    fun format(millis: Long): String = formatter.format(Date(millis))
}

/**
 * Factory helpers so call sites stay short and tags stay consistent.
 */
object Logs {
    /**
     * Creates an informational entry.
     *
     * @param tag origin tag.
     * @param message line body.
     * @param nowMillis timestamp override, primarily for tests.
     */
    fun info(tag: String, message: String, nowMillis: Long = System.currentTimeMillis()) =
        LogEntry(nowMillis, LogLevel.Info, tag, message)

    /**
     * Creates a warning entry.
     *
     * @param tag origin tag.
     * @param message line body.
     * @param nowMillis timestamp override, primarily for tests.
     */
    fun warn(tag: String, message: String, nowMillis: Long = System.currentTimeMillis()) =
        LogEntry(nowMillis, LogLevel.Warning, tag, message)

    /**
     * Creates an error entry.
     *
     * @param tag origin tag.
     * @param message line body.
     * @param nowMillis timestamp override, primarily for tests.
     */
    fun error(tag: String, message: String, nowMillis: Long = System.currentTimeMillis()) =
        LogEntry(nowMillis, LogLevel.Error, tag, message)
}
