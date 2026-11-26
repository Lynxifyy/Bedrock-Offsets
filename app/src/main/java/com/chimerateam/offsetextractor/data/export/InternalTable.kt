package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import java.io.File
import java.io.IOException

/**
 * Thrown when a scan promises internal rows but the NDJSON table behind them
 * cannot be used.
 *
 * This is deliberately fatal for an export: an archive without the internal
 * data looks complete, verifies against its own manifest and is worthless to a
 * client, so the export must fail instead of shipping it.
 */
class InternalTableMissingException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Thrown when the table holds a different number of rows than the scan
 * reported.
 *
 * A mismatch means the table was truncated or replaced, so its category files
 * would silently disagree with `stats` in the index.
 */
class InternalCountMismatchException(message: String) : IOException(message)

/**
 * A usable internal table.
 *
 * @property file the NDJSON file.
 * @property bytes its size on disk.
 * @property rows classes plus functions plus data symbols it must contain.
 */
data class InternalTableFile(
    val file: File,
    val bytes: Long,
    val rows: Int
)

/**
 * Validates the internal table a [ScanResultSummary] points at.
 *
 * The table is written by the engine into app-private storage that survives a
 * cache trim, but it can still disappear: the user can clear it, a retried scan
 * can replace it, and an older cached result can outlive its file. Every step
 * that consumes it therefore validates it first instead of assuming it exists.
 */
object InternalTable {

    /**
     * Rows the summary claims the table holds.
     *
     * @param summary the scan result.
     * @return classes plus functions plus data symbols, zero when the internal
     *   pass never ran.
     */
    fun expectedRows(summary: ScanResultSummary): Int {
        val internal = summary.internal ?: return 0
        return internal.classes + internal.functions + internal.dataSymbols
    }

    /**
     * Whether an export for [summary] must contain internal data.
     *
     * @param summary the scan result.
     * @return true when the internal pass reported at least one row.
     */
    fun isRequired(summary: ScanResultSummary): Boolean = expectedRows(summary) > 0

    /**
     * Whether [summary] was supposed to carry internal data at all.
     *
     * A pass that finished but recorded no rows counts: a result cached by an
     * older version deserialises with every count at zero, and treating that as
     * "nothing to export" is exactly how an archive with an empty
     * `InternalCPP/` was written and reported as a success.
     *
     * @param summary the scan result.
     * @return true when the archive must contain internal data.
     */
    fun isPromised(summary: ScanResultSummary): Boolean =
        summary.internalResolved || expectedRows(summary) > 0

    /**
     * Returns the validated table, or `null` when the summary claims no rows.
     *
     * @param summary the scan result.
     * @return the table to split, or `null` for a JNI-only export.
     * @throws InternalTableMissingException when rows are claimed but the file
     *   is missing, empty or shorter than the engine reported.
     */
    fun require(summary: ScanResultSummary): InternalTableFile? {
        val internal = summary.internal
        if (internal != null && internal.engineVersion.isNotEmpty() &&
            !internal.isFrom(summary.engineVersion, summary.imageBase)
        ) {
            // A table written by another engine, or against another load base,
            // carries `sub_<hex>` names and vtable offsets that disagree with
            // this scan's image base. Reusing it is exactly the 0x2463f0 shift.
            throw InternalTableMissingException(
                "The internal offsets were written by engine " + internal.engineVersion +
                    " at base 0x" + internal.imageBase.toString(16) + " but this scan is " +
                    summary.engineVersion + " at base 0x" + summary.imageBase.toString(16) +
                    ". Re-run the internal extraction."
            )
        }
        val rows = expectedRows(summary)
        if (rows == 0) {
            if (summary.internalResolved) {
                throw InternalTableMissingException(
                    "The internal extraction finished without recording any rows. " +
                        "Re-run the internal extraction."
                )
            }
            return null
        }
        // The engine classifies as it writes, so a normal scan has no single
        // table at all: the rows live in the category documents, which
        // [requireShards] validates. Treating `path` as mandatory here rejected
        // every such scan as "the internal offsets file is gone (. 0 bytes)".
        if (!internal?.shards.isNullOrEmpty()) {
            requireShards(summary)
            return null
        }
        val path = internal?.path.orEmpty()
        if (path.isBlank()) {
            throw InternalTableMissingException(
                "The internal offsets file is gone (no path recorded, $rows rows expected). " +
                    "Re-run the internal extraction."
            )
        }
        val file = File(path)
        if (!file.isFile) {
            throw InternalTableMissingException(
                "The internal offsets file is gone ($path, ${file.length()} bytes). " +
                    "Re-run the internal extraction."
            )
        }
        val bytes = file.length()
        if (bytes <= 0L) {
            throw InternalTableMissingException(
                "The internal offsets file is empty ($path, 0 bytes). " +
                    "Re-run the internal extraction."
            )
        }
        val reported = internal?.tableBytes ?: 0L
        if (reported > 0L && bytes != reported) {
            throw InternalTableMissingException(
                "The internal offsets file is truncated ($path, $bytes bytes, " +
                    "$reported expected). Re-run the internal extraction."
            )
        }
        return InternalTableFile(file, bytes, rows)
    }

    /**
     * Validates the category documents the engine wrote.
     *
     * @param summary the scan result.
     * @return the category files and the class map, or `null` when the engine
     *   wrote a single table instead.
     * @throws InternalTableMissingException when a promised file is gone.
     * @throws InternalCountMismatchException when the files disagree with the
     *   counts the scan reported.
     */
    fun requireShards(summary: ScanResultSummary): SplitResult? {
        val internal = summary.internal ?: return null
        if (internal.shards.isEmpty()) return null
        val parts = mutableListOf<CategoryPart>()
        var total = 0
        for (shard in internal.shards) {
            val file = File(shard.path)
            if (!file.isFile || file.length() <= 0L) {
                throw InternalTableMissingException(
                    "The ${shard.label} offsets file is gone (${shard.path}). " +
                        "Re-run the internal extraction."
                )
            }
            val onDisk = file.length()
            if (shard.bytes > 0L && onDisk != shard.bytes) {
                throw InternalTableMissingException(
                    "The ${shard.label} offsets file is $onDisk bytes, " +
                        "${shard.bytes} expected (${shard.path}). " +
                        "Re-run the internal extraction."
                )
            }
            total += shard.entries
            parts += CategoryPart(
                id = shard.id,
                categoryId = shard.category,
                label = shard.label,
                part = shard.part,
                counts = CategoryCounts(
                    classes = shard.classes,
                    functions = shard.functions,
                    symbols = shard.symbols
                ),
                documentFile = file
            )
        }
        val expected = expectedRows(summary)
        if (expected > 0 && total != expected) {
            throw InternalCountMismatchException(
                "The category files hold $total rows but the scan reported $expected."
            )
        }
        return SplitResult(
            parts = parts,
            classToCategory = readClassMap(internal.classMapPath),
            totalClasses = internal.classes,
            totalFunctions = internal.functions,
            totalSymbols = internal.dataSymbols
        )
    }

    /**
     * Reads the class-to-category map the engine wrote.
     *
     * @param path absolute path of the map, may be empty.
     * @return the map, empty when the file is missing.
     */
    private fun readClassMap(path: String): Map<String, String> {
        if (path.isBlank()) return emptyMap()
        val file = File(path)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            com.google.gson.Gson().fromJson<Map<String, String>>(file.reader(), type)
                ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * Validates a table without throwing.
     *
     * @param summary the scan result.
     * @return `null` when the table is usable, otherwise the reason it is not.
     */
    fun problem(summary: ScanResultSummary): String? = try {
        require(summary)
        null
    } catch (error: IOException) {
        // Both a missing document and a count mismatch are "this result cannot
        // be exported as it stands". Catching only the first let the second
        // escape into a caller that treats this as a yes/no question, which
        // crashed the results screen instead of showing the reason.
        error.message ?: "The internal offsets are unusable. Re-run the internal extraction."
    }
}
