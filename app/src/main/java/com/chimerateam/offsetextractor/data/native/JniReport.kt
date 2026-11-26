package com.chimerateam.offsetextractor.data.native

import com.google.gson.annotations.SerializedName

/**
 * One JNI offset in the engine's union.
 *
 * @property javaName method name, as a symbol, a registered table entry or a
 *   pattern names it.
 * @property signature JNI signature, present when a `RegisterNatives` table
 *   supplied one.
 * @property className owning class, present when a table's descriptor was
 *   found nearby.
 * @property address virtual address of the implementation.
 * @property addressHex [address] as `0x`-prefixed hex.
 * @property source which source produced this entry.
 * @property confidence how much the address can be trusted.
 */
data class JniOffsetEntry(
    @SerializedName("java_name") val javaName: String,
    val signature: String? = null,
    @SerializedName("class") val className: String? = null,
    val address: Long,
    @SerializedName("address_hex") val addressHex: String,
    val source: String,
    val confidence: String
)

/**
 * What one signature pattern did during the scan.
 *
 * @property name pattern name.
 * @property status one of `matched`, `ambiguous`, `not_found`, `symbol_only`.
 * @property hits number of hits the pattern produced.
 */
data class JniPatternStatus(
    val name: String,
    val status: String,
    val hits: Int
)

/**
 * Counts describing how complete the JNI half of an archive is.
 *
 * @property exports exported `Java_*` symbols found.
 * @property entryPoints `JNI_*` lifecycle exports found.
 * @property registered natives found in `RegisterNatives` tables.
 * @property patterns patterns that were looked for.
 * @property patternsMatched patterns with exactly one hit.
 * @property patternsAmbiguous patterns with more than one hit.
 * @property patternsNotFound patterns with no hit and no symbol.
 * @property patternsStale patterns whose function the symbol table names, so
 *   the pattern itself needs updating.
 * @property union entries in the union.
 * @property truncated whether any source hit its own cap.
 */
data class JniCoverage(
    val exports: Int = 0,
    @SerializedName("entry_points") val entryPoints: Int = 0,
    val registered: Int = 0,
    val patterns: Int = 0,
    @SerializedName("patterns_matched") val patternsMatched: Int = 0,
    @SerializedName("patterns_ambiguous") val patternsAmbiguous: Int = 0,
    @SerializedName("patterns_not_found") val patternsNotFound: Int = 0,
    @SerializedName("patterns_stale") val patternsStale: List<String> = emptyList(),
    val union: Int = 0,
    val truncated: Boolean = false
)

/**
 * The complete JNI set, built by the engine from every source.
 *
 * @property offsets every JNI offset, ordered by name then address.
 * @property patterns one status per pattern that was looked for.
 * @property coverage counts over every source.
 */
data class JniReport(
    val offsets: List<JniOffsetEntry> = emptyList(),
    val patterns: List<JniPatternStatus> = emptyList(),
    val coverage: JniCoverage = JniCoverage()
) {
    /** Offset entries whose name starts with `Java_`. */
    val exportedCount: Int get() = offsets.count { it.source == "symbol" }
}
