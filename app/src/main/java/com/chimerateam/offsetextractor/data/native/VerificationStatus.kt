package com.chimerateam.offsetextractor.data.native

import com.google.gson.annotations.SerializedName

/**
 * How a scanned offset agrees with the library's own symbol table.
 *
 * A signature and an exported symbol are independent witnesses to the same
 * address. Comparing them is what separates a checked offset from a plausible
 * one, and it is the only signal available for a target that has no symbol at
 * all.
 */
enum class VerificationStatus {
    /** The signature and an exported symbol with the same name agree. */
    @SerializedName("verified")
    Verified,

    /** An exported symbol with the same name disagrees with the signature. */
    @SerializedName("conflict")
    Conflict,

    /**
     * No exported symbol carries the signature's name, so nothing could be
     * compared. The normal outcome for a stripped library.
     */
    @SerializedName("unverified")
    Unverified;

    /** Whether the offset is safe to act on without further checking. */
    val isTrusted: Boolean get() = this == Verified

    /** Whether the offset disagrees with the library's own symbol table. */
    val isConflict: Boolean get() = this == Conflict

    companion object {
        /** Parses the engine's `snake_case` name, defaulting to [Unverified]. */
        fun fromWire(value: String?): VerificationStatus = when (value) {
            "verified" -> Verified
            "conflict" -> Conflict
            else -> Unverified
        }
    }
}

/**
 * Symbol cross-check totals for one scan.
 *
 * @property verified offsets whose signature agreed with a symbol.
 * @property conflicts offsets whose signature disagreed with a symbol.
 * @property unverified offsets with no symbol to compare against.
 * @property symbolsAvailable whether the library exported any symbol at all.
 */
data class VerificationSummary(
    val verified: Int = 0,
    val conflicts: Int = 0,
    val unverified: Int = 0,
    @SerializedName("symbols_available") val symbolsAvailable: Boolean = false
) {
    /** Total number of offsets considered. */
    val total: Int get() = verified + conflicts + unverified

    /**
     * Fraction of offsets that were verified, in the range `0.0..=1.0`.
     *
     * Zero when nothing was compared, so a stripped library reports no
     * confidence rather than a misleading full score.
     */
    val confidence: Double get() = if (total == 0) 0.0 else verified.toDouble() / total

    /** [confidence] rendered as a whole percentage. */
    val confidencePercent: Int get() = (confidence * 100).toInt()

    companion object {
        /** A summary for a library that exports no symbols. */
        val NONE = VerificationSummary()
    }
}
