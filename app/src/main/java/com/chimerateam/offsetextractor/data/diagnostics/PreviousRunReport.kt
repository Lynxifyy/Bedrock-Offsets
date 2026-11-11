package com.chimerateam.offsetextractor.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Describes how a previous process of this app ended.
 *
 * A native crash or a low-memory kill takes the process down without running
 * any Kotlin, so the live log can only explain it on the *next* launch. Android
 * records why each process died from API 30; this reads that record once and
 * turns it into log lines.
 */
object PreviousRunReport {

    /** Name of the preferences file holding the last reported exit. */
    private const val PREFS = "previous_run_report"

    /** Key of the timestamp already reported, so a line is shown only once. */
    private const val KEY_LAST_REPORTED = "last_reported_timestamp"

    /**
     * Returns one line describing the most recent abnormal exit.
     *
     * @param context any context; the application context is used.
     * @return a single-element list, or an empty list when there is no
     *   abnormal exit or it has already been reported.
     */
    fun collect(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return collectFrom(collectOnApi30(context))
    }

    /**
     * Builds the report for one exit record.
     *
     * @param report the exit record to describe, or null when there is none.
     * @return a single-element list, or an empty list when the exit was normal.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectFrom(report: ExitRecord?): List<String> {
        val latest = report ?: return emptyList()
        if (latest.reason in expectedReasons()) return emptyList()

        val residentMb = latest.rss / KB_PER_MB
        val detail = latest.description?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return listOf(
            "Previous run ended: ${reasonName(latest.reason)} " +
                "(status ${latest.status}, resident $residentMb MB).$detail"
        )
    }

    /** The exit record to describe, already filtered for repeat reports. */
    private class ExitRecord(
        val timestamp: Long,
        val reason: Int,
        val status: Int,
        val rss: Long,
        val description: String?
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectOnApi30(context: Context): ExitRecord? {
        val app = context.applicationContext
        val manager = app.getSystemService(ActivityManager::class.java) ?: return null
        val latest = runCatching {
            manager.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null

        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reported = preferences.getLong(KEY_LAST_REPORTED, 0L)
        if (latest.timestamp <= reported) return null
        preferences.edit().putLong(KEY_LAST_REPORTED, latest.timestamp).apply()

        return ExitRecord(
            timestamp = latest.timestamp,
            reason = latest.reason,
            status = latest.status,
            rss = latest.rss,
            description = latest.description
        )
    }

    /** Exit reasons that are a normal end of the process, not a failure. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun expectedReasons(): Set<Int> = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE
    )

    /** Human-readable name for a platform exit reason. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialisation failure"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        else -> "reason code $reason"
    }

    /** Bytes in a megabyte, matching how the live log renders sizes. */
    private const val KB_PER_MB = 1024L
}
