package com.chimerateam.offsetextractor.ui.state

import androidx.compose.runtime.Immutable

/**
 * Progress of a multi-APK run.
 *
 * The queue is processed one APK at a time on purpose: the engine maps a whole
 * library at once, so running two scans in parallel would double the peak
 * memory for no throughput gain on a phone.
 *
 * @property total number of APKs in the run.
 * @property completed APKs finished, whether or not they succeeded.
 * @property succeeded APKs that produced a result.
 * @property failed APKs that raised an error.
 * @property currentName name of the APK being processed, once known.
 */
@Immutable
data class BatchProgress(
    val total: Int,
    val completed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val currentName: String? = null,
) {
    /** One-based index of the APK being processed, for display. */
    val position: Int get() = (completed + 1).coerceAtMost(total.coerceAtLeast(1))

    /** Short description shown above the progress bar, for example `2 of 3 APKs`. */
    val label: String get() = "$position of $total APKs"
}

/**
 * Outcome of a finished multi-APK run.
 *
 * @property total number of APKs attempted.
 * @property succeeded APKs that produced a result.
 * @property failed APKs that raised an error.
 */
@Immutable
data class BatchSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) {
    /** One-line description of the run. */
    val message: String
        get() = "Processed $total APKs. $succeeded succeeded, $failed failed."
}
