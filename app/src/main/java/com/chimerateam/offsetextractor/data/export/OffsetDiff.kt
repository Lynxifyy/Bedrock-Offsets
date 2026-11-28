package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary

/**
 * How one offset changed between two builds.
 */
enum class DiffKind {
    /** Present in both builds at the same address with the same detail. */
    Matched,

    /** Present in both builds at different addresses. */
    Shifted,

    /** Present only in the newer build. */
    New,

    /** Present only in the older build. */
    Removed,

    /** Present in both builds but the signature or slot metadata changed. */
    BrokenSignature
}

/**
 * One line of a build-to-build comparison.
 *
 * @property kind what changed.
 * @property name the offset name, identical across builds.
 * @property category the row category the name belongs to.
 * @property beforeAddress address in the older build, when present.
 * @property afterAddress address in the newer build, when present.
 * @property delta signed address difference, when both addresses exist.
 * @property detail a short human-readable explanation.
 */
data class DiffEntry(
    val kind: DiffKind,
    val name: String,
    val category: OffsetCategory,
    val beforeAddress: String?,
    val afterAddress: String?,
    val delta: Long?,
    val detail: String
)

/**
 * Compares two scans of the same app.
 *
 * The identity of an offset is its category and name, not its address: an
 * address that moved is the interesting case, not a different offset. Detail
 * text carries the signature, prologue form and slot metadata, so a change
 * there without an address change is reported as [DiffKind.BrokenSignature]
 * rather than silently counted as a match.
 */
object OffsetDiff {

    /**
     * Compares [before] with [after].
     *
     * @param before the older scan.
     * @param after the newer scan.
     * @return every entry in both scans, ordered by kind then name.
     */
    fun compare(before: ScanResultSummary, after: ScanResultSummary): List<DiffEntry> {
        val oldRows = OffsetRows.from(before).associateBy { key(it) }
        val newRows = OffsetRows.from(after).associateBy { key(it) }
        val entries = mutableListOf<DiffEntry>()

        for ((key, old) in oldRows) {
            val new = newRows[key]
            if (new == null) {
                entries += DiffEntry(
                    kind = DiffKind.Removed,
                    name = old.name,
                    category = old.category,
                    beforeAddress = old.addressHex,
                    afterAddress = null,
                    delta = null,
                    detail = "removed in the newer build"
                )
                continue
            }
            val oldAddress = parseAddress(old.addressHex)
            val newAddress = parseAddress(new.addressHex)
            val delta = if (oldAddress != null && newAddress != null) {
                newAddress - oldAddress
            } else {
                null
            }
            entries += when {
                old.detail != new.detail -> DiffEntry(
                    kind = DiffKind.BrokenSignature,
                    name = new.name,
                    category = new.category,
                    beforeAddress = old.addressHex,
                    afterAddress = new.addressHex,
                    delta = delta,
                    detail = "${old.detail} -> ${new.detail}"
                )
                delta == null || delta == 0L -> DiffEntry(
                    kind = DiffKind.Matched,
                    name = new.name,
                    category = new.category,
                    beforeAddress = old.addressHex,
                    afterAddress = new.addressHex,
                    delta = delta,
                    detail = new.detail
                )
                else -> DiffEntry(
                    kind = DiffKind.Shifted,
                    name = new.name,
                    category = new.category,
                    beforeAddress = old.addressHex,
                    afterAddress = new.addressHex,
                    delta = delta,
                    detail = "%+d (0x%x)".format(delta, kotlin.math.abs(delta))
                )
            }
        }
        for ((key, new) in newRows) {
            if (oldRows.containsKey(key)) continue
            entries += DiffEntry(
                kind = DiffKind.New,
                name = new.name,
                category = new.category,
                beforeAddress = null,
                afterAddress = new.addressHex,
                delta = null,
                detail = "new in the newer build"
            )
        }
        return entries.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
    }

    /** Counts entries of [kind]. */
    fun count(entries: List<DiffEntry>, kind: DiffKind): Int = entries.count { it.kind == kind }

    private fun key(row: OffsetRow): String = "${row.category}:${row.name}"

    private fun parseAddress(value: String): Long? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("0x")) return null
        return trimmed.removePrefix("0x").toLongOrNull(16)
    }
}
