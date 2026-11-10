package com.chimerateam.offsetextractor.data.native

import com.google.gson.annotations.SerializedName

/**
 * A signature the scanner looks for, written the way an offset hunter writes it.
 *
 * @property name stable identifier used in results and logs.
 * @property pattern space-separated hex bytes, with `??` for a wildcard.
 */
data class PatternSpec(
    val name: String,
    val pattern: String
)

/**
 * One verified hit from the scanner.
 *
 * @property name pattern that matched.
 * @property sectionOffset offset of the match inside the scanned section.
 * @property fileOffset offset of the match inside the file.
 * @property address virtual address the match would have once loaded.
 * @property addressHex the address rendered as `0x`-prefixed hex.
 * @property prologueKind which prologue form the bytes decode to, if verified.
 * @property prologueInstruction the decoded first instruction, if verified.
 * @property verificationStatus how the address agrees with the symbol table.
 */
data class PatternHit(
    val name: String,
    @SerializedName("section_offset") val sectionOffset: Long,
    @SerializedName("file_offset") val fileOffset: Long,
    val address: Long,
    @SerializedName("address_hex") val addressHex: String,
    @SerializedName("prologue_kind") val prologueKind: String? = null,
    @SerializedName("prologue_instruction") val prologueInstruction: String? = null,
    @SerializedName("verification_status") val verificationStatus: VerificationStatus =
        VerificationStatus.Unverified
) {
    /** Whether the hit decoded to a recognisable function prologue. */
    val isVerified: Boolean get() = prologueKind != null
}

/**
 * Result of scanning one section.
 *
 * @property section section that was scanned.
 * @property scannedBytes number of bytes scanned.
 * @property chunks number of chunks the section was split into.
 * @property workers number of workers the pool actually used.
 * @property hits every hit, sorted by address.
 * @property peakResidentBytes peak resident set after the scan, when reported.
 * @property discarded candidates the prologue check threw away.
 * @property verified whether the prologue check was applied.
 * @property verification symbol cross-check totals, when the check has run.
 */
data class ScanOutcome(
    val section: String,
    @SerializedName("scanned_bytes") val scannedBytes: Long,
    val chunks: Int,
    val workers: Int,
    val hits: List<PatternHit>,
    @SerializedName("peak_resident_bytes") val peakResidentBytes: Long? = null,
    val discarded: Int = 0,
    val verified: Boolean = false,
    val verification: VerificationSummary? = null
) {
    /** Number of hits that came from [name]. */
    fun countOf(name: String): Int = hits.count { it.name == name }
}

/** JSON payload wrapper matching the engine's `ScanPayload` serialisation. */
internal data class ScanOutcomePayload(
    val section: String?,
    @SerializedName("scanned_bytes") val scannedBytes: Long?,
    val chunks: Int?,
    val workers: Int?,
    val hits: List<PatternHit>?,
    @SerializedName("peak_resident_bytes") val peakResidentBytes: Long?,
    val discarded: Int?,
    val verified: Boolean?,
    val verification: VerificationSummary?
)
