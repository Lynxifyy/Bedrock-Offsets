package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.VerificationStatus
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary

/** Grouping of a row in the results list. */
enum class OffsetCategory {
    /** An RTTI virtual table. */
    VTable,

    /** A signature match. */
    Pattern,

    /** An exported dynamic symbol. */
    Symbol
}

/**
 * One line of the results list.
 *
 * @property category group this row belongs to.
 * @property name target name, for example `ClientInstance`.
 * @property addressHex the address rendered as `0x`-prefixed hex, or `-` when
 *   the target was not found.
 * @property detail short supporting text, for example the prologue form.
 * @property missing whether the target was expected but not found. Missing rows
 *   are highlighted so an out-of-date signature pack is obvious.
 * @property status how the offset agrees with the library's symbol table.
 */
data class OffsetRow(
    val category: OffsetCategory,
    val name: String,
    val addressHex: String,
    val detail: String,
    val missing: Boolean = false,
    val status: VerificationStatus = VerificationStatus.Unverified
) {
    /** Whether [query] matches this row, ignoring case. */
    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) {
            return true
        }
        return name.contains(needle, ignoreCase = true) ||
            addressHex.contains(needle, ignoreCase = true) ||
            detail.contains(needle, ignoreCase = true)
    }
}

/**
 * Flattens a scan summary into the rows the results list renders.
 *
 * Rows are grouped in category order, so the list can be walked top to bottom
 * and each group appears in one block. Every configured signature is listed
 * even when it did not match, because a missing signature is a result too: it
 * means the pattern pack no longer fits the build.
 */
object OffsetRows {

    /** Placeholder shown where an address would be. */
    const val MISSING_ADDRESS = "-"

    /** Rows for [summary], ordered by category. */
    fun from(summary: ScanResultSummary): List<OffsetRow> = buildList {
        summary.vtables.entries.values.sortedBy { it.vtableAddress }.forEach { vtable ->
            add(
                OffsetRow(
                    category = OffsetCategory.VTable,
                    name = vtable.name,
                    addressHex = vtable.vtableHex,
                    detail = "type_info ${hex(vtable.typeInfoAddress)}"
                )
            )
        }

        val hitsByName = summary.patternHits.groupBy { it.name }
        summary.expectedPatterns.forEach { name ->
            val hit = hitsByName[name]?.minByOrNull { it.address }
            if (hit == null) {
                add(
                    OffsetRow(
                        category = OffsetCategory.Pattern,
                        name = name,
                        addressHex = MISSING_ADDRESS,
                        detail = "no match in this build",
                        missing = true
                    )
                )
            } else {
                add(
                    OffsetRow(
                        category = OffsetCategory.Pattern,
                        name = name,
                        addressHex = hit.addressHex,
                        detail = hit.prologueKind ?: "not decoded",
                        status = hit.verificationStatus
                    )
                )
            }
        }

        // Hits from a pack the app no longer ships still belong in the report.
        hitsByName.keys.filterNot { it in summary.expectedPatterns }.sorted().forEach { name ->
            hitsByName.getValue(name).forEach { hit ->
                add(
                    OffsetRow(
                        category = OffsetCategory.Pattern,
                        name = name,
                        addressHex = hit.addressHex,
                        detail = hit.prologueKind ?: "not decoded",
                        status = hit.verificationStatus
                    )
                )
            }
        }

        summary.symbols.sortedBy { it.address }.forEach { symbol ->
            add(
                OffsetRow(
                    category = OffsetCategory.Symbol,
                    name = symbol.name,
                    addressHex = symbol.addressHex,
                    detail = "${symbol.size} bytes"
                )
            )
        }
    }

    /** Formats [value] as `0x`-prefixed lower-case hex. */
    fun hex(value: Long): String = "0x%x".format(value)
}
