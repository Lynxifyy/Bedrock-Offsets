package com.chimerateam.offsetextractor.ui.state

import com.chimerateam.offsetextractor.data.native.ExportedSymbol
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
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
    val exportMessage: String? = null,
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
 * @property textSection the executable section, or `null` when unreadable.
 * @property buildId build identifier of the library, used as the cache key.
 * @property buildIdSource where the identifier came from.
 * @property jniSymbolCount number of `Java_` symbols exported by the library.
 * @property patternHits every verified signature match.
 * @property discardedMatches candidates the prologue check threw away.
 * @property peakResidentBytes peak resident set after the scan, when reported.
 * @property vtables RTTI vtables resolved from the library.
 * @property symbols exported JNI symbols, used for symbol cross-checking.
 * @property expectedPatterns every signature the app looked for, so a missing
 *   one can be reported rather than silently omitted.
 */
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
    val symbols: List<ExportedSymbol> = emptyList(),
    val expectedPatterns: List<String> = emptyList(),
    val verification: VerificationSummary = VerificationSummary.NONE,
)
