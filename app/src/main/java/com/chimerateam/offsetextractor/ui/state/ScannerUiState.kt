package com.chimerateam.offsetextractor.ui.state

import androidx.compose.runtime.Immutable
import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.JniReport
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.data.native.VtableMap
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
 * @property exportMessage transient confirmation shown after an export, or
 *   `null` when there is nothing to announce.
 * @property lowMemory whether the system has reported memory pressure. The UI
 *   shows a warning and offers to free the cache; the app never guesses that
 *   the scan itself caused it.
 * @property internalAvailable whether the internal table behind [result] is
 *   still on disk. A cleared table keeps the result visible but disables the
 *   download, because an archive without it would be worthless.
 * @property batch progress of a multi-APK run, or `null` when only one APK is
 *   being processed.
 * @property batchSummary outcome of the last multi-APK run, shown once and
 *   then dismissed.
 */
@Immutable
data class ScannerUiState(
    val phase: ScannerPhase = ScannerPhase.Idle,
    val progress: Int = 0,
    val progressTask: String = "",
    val selectedApk: ApkMetadata? = null,
    val usedCacheCopy: Boolean = false,
    val result: ScanResultSummary? = null,
    val error: String? = null,
    val errorHint: String? = null,
    val exportMessage: String? = null,
    val lowMemory: Boolean = false,
    val internalAvailable: Boolean = false,
    val batch: BatchProgress? = null,
    val batchSummary: BatchSummary? = null
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
    Error
}

/**
 * Summary of a completed extraction, shown until the next run.
 *
 * @property displayName name of the APK the offsets came from.
 * @property sha256 hash of the extracted native library.
 * @property sizeBytes size of the extracted native library.
 * @property apkSizeBytes size of the APK itself, when the provider reported it.
 * @property apkVersion version label used in the exported file names.
 * @property architecture target architecture reported by the ELF header.
 * @property entryPoint entry point of the mapped library.
 * @property sectionCount number of section headers in the mapped library.
 * @property textSection the executable section, or `null` when unreadable.
 * @property imageBase lowest load address of any allocated section.
 * @property buildId build identifier of the library, used as the cache key.
 * @property buildIdSource where the identifier came from.
 * @property jniSymbolCount number of `Java_` symbols exported by the library.
 * @property patternHits every verified signature match.
 * @property discardedMatches candidates the prologue check threw away.
 * @property peakResidentBytes peak resident set after the scan, when reported.
 * @property vtables RTTI vtables resolved from the library.
 * @property vtablesResolved whether the vtable pass completed rather than being
 *   skipped after an error.
 * @property vtableError detail of a failed vtable pass, or `null`.
 * @property internal counts of the internal C++ extraction, when it ran.
 * @property internalResolved whether the internal pass completed.
 * @property internalError detail of a failed internal pass, or `null`.
 * @property symbols exported JNI symbols, used for symbol-cross checking.
 * @property expectedPatterns every signature the app looked for, so a missing
 *   one can be reported rather than silently omitted.
 */
@Immutable
data class ScanResultSummary(
    val displayName: String,
    val sha256: String,
    val sizeBytes: Long,
    val architecture: String,
    val entryPoint: String,
    val sectionCount: Int,
    val textSection: SectionInfo? = null,
    val buildId: String = "",
    val buildIdSource: String = "",
    val jniSymbolCount: Int = 0,
    val patternHits: List<PatternHit> = emptyList(),
    val discardedMatches: Int = 0,
    val peakResidentBytes: Long? = null,
    val vtables: VtableMap = VtableMap.EMPTY,
    val vtablesResolved: Boolean = false,
    val vtableError: String? = null,
    val internal: InternalSummary? = null,
    val internalResolved: Boolean = false,
    val internalError: String? = null,
    val symbols: List<ExportedSymbol> = emptyList(),
    val expectedPatterns: List<String> = emptyList(),
    val verification: VerificationSummary = VerificationSummary.NONE,
    val apkSizeBytes: Long = 0,
    val apkVersion: String = "",
    val imageBase: Long = 0,
    val jni: JniReport? = null
) {
    /**
     * Whether every optional pass finished, so this result may be cached and
     * reused without hiding a failure.
     */
    val isComplete: Boolean
        get() = vtablesResolved && internalResolved
}
