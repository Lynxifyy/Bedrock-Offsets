package com.chimerateam.offsetextractor.ui.state

import com.chimerateam.offsetextractor.data.saf.ApkMetadata

/**
 * Everything the UI needs to render the scanner destination.
 *
 * A single immutable snapshot avoids the inconsistent combinations that
 * separate booleans would allow.
 *
 * @property phase which screen-level state is active.
 * @property progress completion of the active task, in the range 0..100.
 * @property progressTask short description of the active task.
 * @property selectedApk the APK currently held open, if any.
 * @property usedCacheCopy whether the open descriptor points at a cache copy.
 * @property result summary of the last successful extraction, if any.
 * @property error user-facing failure message, if any.
 * @property errorHint actionable follow-up shown under the error message.
 */
data class ScannerUiState(
    val phase: ScannerPhase = ScannerPhase.Idle,
    val progress: Int = 0,
    val progressTask: String = "",
    val selectedApk: ApkMetadata? = null,
    val usedCacheCopy: Boolean = false,
    val result: ScanResultSummary? = null,
    val error: String? = null,
    val errorHint: String? = null,
)

/** Screen-level phases of the scanner. */
enum class ScannerPhase {
    /** Nothing selected yet. */
    Idle,

    /** A native library is being pulled out of the APK. */
    Extracting,

    /** Offsets are being located in the mapped library. */
    Scanning,

    /** A scan finished successfully. */
    Success,

    /** The last operation failed; the UI offers a retry. */
    Error,
}

/**
 * Summary of a completed extraction, shown until the next run.
 *
 * @property displayName name of the APK the offsets came from.
 * @property sha256 hash of the extracted native library.
 * @property sizeBytes size of the extracted native library.
 * @property architecture target architecture reported by the ELF header.
 * @property entryPoint entry point of the mapped library.
 * @property sectionCount number of section headers in the mapped library.
 */
data class ScanResultSummary(
    val displayName: String,
    val sha256: String,
    val sizeBytes: Long,
    val architecture: String,
    val entryPoint: String,
    val sectionCount: Int,
)
