package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.util.formatBytes
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.Writer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One file recorded in `manifest.json`. */
data class ManifestEntry(
    val path: String,
    val size: Long,
    val entries: Int,
    val sha256: String
)

/**
 * Result of building the single ZIP.
 *
 * @property file the finished archive.
 * @property manifest the files it contains, in write order.
 * @property verified whether re-opening the archive confirmed every CRC, size
 *   and digest, that every category file is present and that the internal row
 *   counts add up.
 * @property version version label shared by every file name.
 * @property bytes size of the finished archive.
 * @property internalRows internal records written, zero for a JNI-only export.
 * @property categoryFiles number of `InternalCPP/` category files.
 * @property rereadRows internal records counted inside the finished archive,
 *   or `null` when the archive was not re-read.
 */
data class ZipBuildResult(
    val file: File,
    val manifest: List<ManifestEntry>,
    val verified: Boolean,
    val version: String,
    val bytes: Long = 0L,
    val internalRows: Int = 0,
    val categoryFiles: Int = 0,
    val rereadRows: Int? = null
) {
    /** Every file in the archive, including `manifest.json`. */
    val fileCount: Int get() = manifest.size + 1
}

/**
 * Builds `BEDROCK_OFFSETS_<version>.zip`.
 *
 * The archive is written entry by entry from the NDJSON table and the category
 * files, so no stage holds a whole document in memory. `manifest.json` is
 * written last because it describes the entries that precede it; its own digest
 * is therefore not part of the list.
 *
 * @param classifier category rules.
 * @param workDir scratch directory for the raw category files.
 * @param log receives one line per export stage.
 */
class OffsetZipBuilder(
    private val classifier: Classifier,
    private val workDir: File,
    private val log: (String) -> Unit = {}
) {

    /**
     * Writes the archive.
     *
     * @param summary the completed scan; its internal table is read in place.
     * @param target destination archive.
     * @param includeCombined whether to add one combined internal document.
     * @param onProgress receives `(fraction, message)` with `fraction` in 0..1.
     * @return the archive and whether it verified.
     * @throws InternalTableMissingException when the scan reports internal rows
     *   but the table behind them is gone.
     * @throws InternalCountMismatchException when the table holds a different
     *   number of rows than the scan reported.
     */
    fun build(
        summary: ScanResultSummary,
        target: File,
        includeCombined: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ZipBuildResult {
        workDir.mkdirs()
        target.parentFile?.mkdirs()
        val version = OffsetExporter.versionLabel(summary)
        // The engine classifies while it streams, so its category documents are
        // copied into the archive byte for byte. Only a result scanned by an
        // older engine still has a single table to split, and that path parses
        // the rows a second time.
        val shards = InternalTable.requireShards(summary)
        val table = if (shards == null) InternalTable.require(summary) else null
        val split = shards ?: table?.let { validated ->
            log("ZIP: this result predates category shards, splitting the table")
            InternalTableSplitter(classifier, workDir).split(validated.file) { percent, message ->
                onProgress(percent / 200f, message)
            }
        } ?: SplitResult(emptyList(), emptyMap(), 0, 0, 0)
        requireCounts(summary, split, hasInternal = table != null || shards != null)
        log(
            "ZIP: classified ${split.totalClasses} classes / ${split.totalFunctions} functions " +
                "into ${split.parts.size} category files"
        )

        val expectedRows = InternalTable.expectedRows(summary)
        val manifest = mutableListOf<ManifestEntry>()
        val categoryPaths = mutableListOf<String>()
        ZipOutputStream(BufferedOutputStream(target.outputStream(), BUFFER_BYTES)).use { zip ->
            // Level 9 spends several times the CPU of level 6 for a few
            // percent of size on JSON that is mostly digits and identifiers.
            zip.setLevel(6)

            manifest += writeEntry(zip, README_PATH, 0) { writer ->
                OffsetExporter.writeReadme(summary, split, writer)
            }

            manifest += writeEntry(
                zip,
                "JNI/${OffsetExporter.jniFileName(summary)}",
                jniEntryCount(summary)
            ) { writer -> OffsetExporter.writeJni(summary, writer) }

            manifest += writeEntry(
                zip,
                "InternalCPP/${OffsetExporter.internalIndexFileName(summary)}",
                split.parts.size
            ) { writer -> OffsetExporter.writeInternalIndex(summary, split, writer) }

            for ((index, part) in split.parts.withIndex()) {
                val name = "InternalCPP/${OffsetExporter.categoryFileName(part.id, summary)}"
                categoryPaths += name
                val document = part.documentFile
                manifest += if (document != null) {
                    writeStreamEntry(zip, name, part.counts.total) { out ->
                        copyFile(document, out)
                    }.also {
                        // The archive holds the only copy now, so the shard is
                        // deleted as soon as its entry is closed.
                        document.delete()
                    }
                } else {
                    writeEntry(zip, name, part.counts.total) { writer ->
                        OffsetExporter.writeCategory(summary, part, writer)
                    }
                }
                val fraction = 0.6f + 0.3f * ((index + 1).toFloat() / split.parts.size)
                onProgress(fraction, "Writing ${part.label}")
            }

            if (includeCombined) {
                manifest += writeEntry(
                    zip,
                    "InternalCPP/${OffsetExporter.internalFileName(summary)}",
                    summary.internal?.functions ?: 0
                ) { writer -> OffsetExporter.writeInternal(summary, writer) }
            }

            val manifestText = buildManifest(
                summary, version, manifest, split, expectedRows, categoryPaths
            )
            val entry = ZipEntry("manifest.json")
            zip.putNextEntry(entry)
            zip.write(manifestText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        log(
            "ZIP: built ${target.name}, ${manifest.size + 1} files, " +
                "${formatBytes(target.length())}"
        )

        onProgress(0.95f, "Verifying the archive")
        val outcome = verify(target, manifest, categoryPaths, expectedRows)
        if (outcome.ok) {
            log(
                "ZIP: verified (${manifest.size + 1} files, ${outcome.rereadRows} of " +
                    "$expectedRows internal rows re-read)"
            )
        } else {
            log("ZIP: verification FAILED for ${target.name}: ${outcome.detail}")
        }
        onProgress(1f, if (outcome.ok) "Archive verified" else "Archive failed verification")
        return ZipBuildResult(
            file = target,
            manifest = manifest,
            verified = outcome.ok,
            version = version,
            bytes = target.length(),
            internalRows = split.rows,
            categoryFiles = categoryPaths.size,
            rereadRows = if (outcome.ok) outcome.rereadRows else null
        )
    }

    /**
     * Refuses to build an archive whose internal part disagrees with the scan.
     *
     * @param summary the scan result.
     * @param split the category split.
     * @param hasInternal whether the scan produced an internal table or
     *   category documents.
     */
    private fun requireCounts(
        summary: ScanResultSummary,
        split: SplitResult,
        hasInternal: Boolean
    ) {
        val internal = summary.internal
        if (!hasInternal) {
            if (split.rows > 0) {
                throw InternalCountMismatchException(
                    "The internal table holds ${split.rows} rows but the scan claims none."
                )
            }
            if (internal != null && (internal.functions > 0 || internal.classes > 0)) {
                throw InternalTableMissingException(
                    "The internal offsets file is gone (no path recorded, " +
                        "${InternalTable.expectedRows(summary)} rows expected). " +
                        "Re-run the internal extraction."
                )
            }
            return
        }
        if (!split.consistent) {
            throw InternalCountMismatchException(
                "Category assignment is inconsistent: ${split.assignedClasses} of " +
                    "${split.totalClasses} classes, ${split.assignedFunctions} of " +
                    "${split.totalFunctions} functions and ${split.assignedSymbols} of " +
                    "${split.totalSymbols} data symbols were assigned."
            )
        }
        if (internal == null) {
            throw InternalCountMismatchException(
                "The internal table holds ${split.rows} rows but the scan recorded no counts."
            )
        }
        if (split.totalFunctions != internal.functions ||
            split.totalClasses != internal.classes ||
            split.totalSymbols != internal.dataSymbols
        ) {
            throw InternalCountMismatchException(
                "The internal table does not match the scan: ${split.totalClasses} classes " +
                    "(expected ${internal.classes}), ${split.totalFunctions} functions " +
                    "(expected ${internal.functions}), ${split.totalSymbols} data symbols " +
                    "(expected ${internal.dataSymbols}). Re-run the internal extraction."
            )
        }
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        path: String,
        entries: Int,
        block: (Writer) -> Unit
    ): ManifestEntry {
        zip.putNextEntry(ZipEntry(path))
        val counter = DigestCountStream(zip)
        val buffered = BufferedOutputStream(counter, BUFFER_BYTES)
        val writer = buffered.writer(Charsets.UTF_8)
        block(writer)
        writer.flush()
        buffered.flush()
        zip.closeEntry()
        return ManifestEntry(path, counter.bytes, entries, counter.sha256())
    }

    /**
     * Writes an entry whose bytes are produced by [block] directly.
     *
     * Used for the category documents the engine already wrote: they are copied
     * through a fixed buffer, so no record ever becomes an object on the heap.
     *
     * @param zip open archive.
     * @param path entry name.
     * @param entries record count the entry must hold.
     * @param block writes the entry body.
     * @return the manifest entry with the digest and size of the written bytes.
     */
    private fun writeStreamEntry(
        zip: ZipOutputStream,
        path: String,
        entries: Int,
        block: (java.io.OutputStream) -> Unit
    ): ManifestEntry {
        zip.putNextEntry(ZipEntry(path))
        val counter = DigestCountStream(zip)
        val buffered = BufferedOutputStream(counter, BUFFER_BYTES)
        block(buffered)
        buffered.flush()
        zip.closeEntry()
        return ManifestEntry(path, counter.bytes, entries, counter.sha256())
    }

    /**
     * Copies one file into [out] through a 64 KB buffer.
     *
     * @param source file to copy.
     * @param out destination stream.
     */
    private fun copyFile(source: File, out: java.io.OutputStream) {
        source.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        }
    }

    private fun buildManifest(
        summary: ScanResultSummary,
        version: String,
        entries: List<ManifestEntry>,
        split: SplitResult,
        expectedRows: Int,
        categoryPaths: List<String>
    ): String {
        val json = com.google.gson.JsonObject()
        json.addProperty("schemaVersion", OffsetExporter.SCHEMA_VERSION)
        json.addProperty("kind", "bedrock_offsets_zip")
        json.addProperty("generatedBy", "BedrockOffsetExtractor")
        json.addProperty("generatedAt", java.time.Instant.now().toString())
        json.addProperty("version", version)
        json.addProperty("apk", summary.displayName)
        json.addProperty("buildId", summary.buildId)
        json.addProperty("librarySha256", summary.sha256)
        json.addProperty("entryCount", entries.size)
        writeCoverage(json, summary, split, expectedRows, categoryPaths)
        val files = com.google.gson.JsonArray()
        for (entry in entries) {
            val item = com.google.gson.JsonObject()
            item.addProperty("path", entry.path)
            item.addProperty("size", entry.size)
            item.addProperty("entries", entry.entries)
            item.addProperty("sha256", entry.sha256)
            files.add(item)
        }
        json.add("files", files)
        return json.toString()
    }

    /**
     * Adds the coverage and completeness blocks to the manifest.
     *
     * `coverage` answers "was anything dropped" for both halves of the export:
     * the internal function count against the `.eh_frame` frames, and the JNI
     * union against its sources. `categories` is the row count of every
     * category file, and `completeness` states the numbers that have to agree,
     * so a reader can check the archive without trusting the app.
     *
     * @param json manifest under construction.
     * @param summary the completed scan.
     * @param split the category documents.
     * @param expectedRows internal rows the scan promised.
     */
    private fun writeCoverage(
        json: com.google.gson.JsonObject,
        summary: ScanResultSummary,
        split: SplitResult,
        expectedRows: Int,
        categoryPaths: List<String>
    ) {
        val coverage = com.google.gson.JsonObject()
        val internal = com.google.gson.JsonObject()
        summary.internal?.coverage?.let { block ->
            internal.addProperty("functions", block.functions)
            internal.addProperty("fdeFunctions", block.fdeFunctions)
            internal.addProperty("fdeMissing", block.fdeMissing)
            internal.addProperty("functionsMatchFdes", block.functionsMatchFdes)
            internal.addProperty("classes", block.classes)
            internal.addProperty("slots", block.slots)
            internal.addProperty("dataSymbols", block.dataSymbols)
            internal.addProperty("functionSource", block.functionSource)
            internal.addProperty("truncated", block.truncated)
            internal.addProperty("truncatedSlotClasses", block.truncatedSlotClasses)
            internal.addProperty("truncatedSignatures", block.truncatedSignatures)
        }
        coverage.add("internal", internal)
        val jni = com.google.gson.JsonObject()
        summary.jni?.coverage?.let { block ->
            jni.addProperty("exports", block.exports)
            jni.addProperty("entryPoints", block.entryPoints)
            jni.addProperty("registered", block.registered)
            jni.addProperty("patterns", block.patterns)
            jni.addProperty("patternsMatched", block.patternsMatched)
            jni.addProperty("patternsAmbiguous", block.patternsAmbiguous)
            jni.addProperty("patternsNotFound", block.patternsNotFound)
            jni.addProperty("union", block.union)
            jni.addProperty("truncated", block.truncated)
        }
        coverage.add("jni", jni)
        json.add("coverage", coverage)

        val pathById = split.parts.map { it.id }.zip(categoryPaths).toMap()
        val categories = com.google.gson.JsonArray()
        for (part in split.parts.sortedBy { it.id }) {
            val item = com.google.gson.JsonObject()
            item.addProperty("id", part.id)
            item.addProperty("category", part.categoryId)
            item.addProperty("label", part.label)
            item.addProperty("file", pathById[part.id] ?: "")
            item.addProperty("classes", part.counts.classes)
            item.addProperty("functions", part.counts.functions)
            item.addProperty("symbols", part.counts.symbols)
            item.addProperty("entries", part.counts.total)
            categories.add(item)
        }
        json.add("categories", categories)

        val completeness = com.google.gson.JsonObject()
        completeness.addProperty("expectedRows", expectedRows)
        completeness.addProperty("writtenRows", split.rows)
        completeness.addProperty("categories", split.parts.size)
        completeness.addProperty("complete", split.rows == expectedRows && split.consistent)
        json.add("completeness", completeness)
    }

    /**
     * Re-opens [archive] and checks every entry against the manifest.
     *
     * `ZipFile` verifies a DEFLATED entry's CRC while it is read, so a corrupt
     * archive throws here rather than at the client. On top of that, an archive
     * that promises internal rows must actually contain the category files and
     * their manifest counts must add up to those rows: a manifest that only
     * describes itself would otherwise "verify" an empty export.
     *
     * @param archive the finished archive.
     * @param expected the manifest that was written.
     * @param categoryPaths the `InternalCPP/` category files the build wrote.
     * @param expectedInternalRows rows the scan reported, or `null` to skip the
     *   internal checks.
     * @return the outcome, with the number of rows counted inside the archive.
     */
    fun verify(
        archive: File,
        expected: List<ManifestEntry>,
        categoryPaths: List<String> = emptyList(),
        expectedInternalRows: Int? = null
    ): VerifyOutcome {
        if (!archive.isFile) return VerifyOutcome(false, 0, "the archive is gone")
        if ((expectedInternalRows ?: 0) > 0 && categoryPaths.isEmpty()) {
            return VerifyOutcome(false, 0, "the archive holds no category file")
        }
        return runCatching {
            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json")
                    ?: return@use VerifyOutcome(false, 0, "manifest.json is missing")
                val manifest = zip.getInputStream(manifestEntry)
                    .bufferedReader(Charsets.UTF_8)
                    .use { JsonParser.parseReader(it).asJsonObject }
                val files = manifest.getAsJsonArray("files")
                if (files.size() != expected.size) {
                    return@use VerifyOutcome(
                        false,
                        0,
                        "the manifest lists ${files.size()} files, ${expected.size} were written"
                    )
                }
                if (manifest.get("entryCount")?.asInt != expected.size) {
                    return@use VerifyOutcome(false, 0, "the manifest entry count is wrong")
                }
                for (index in 0 until files.size()) {
                    val item = files[index].asJsonObject
                    val path = item.get("path").asString
                    val size = item.get("size").asLong
                    val sha = item.get("sha256").asString
                    val entry = zip.getEntry(path)
                        ?: return@use VerifyOutcome(false, 0, "$path is missing")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            total += read
                        }
                    }
                    if (total != size) {
                        return@use VerifyOutcome(false, 0, "$path is $total bytes, not $size")
                    }
                    if (hex(digest.digest()) != sha) {
                        return@use VerifyOutcome(false, 0, "$path has a different digest")
                    }
                }
                val rows = expectedInternalRows ?: return@use VerifyOutcome(true, 0, "")
                if (rows <= 0) return@use VerifyOutcome(true, 0, "")
                // The last check counts the records inside the finished
                // archive, not the ones the build meant to write: a manifest
                // that only describes itself would otherwise "verify" an empty
                // export.
                var written = 0
                var reread = 0
                for (path in categoryPaths) {
                    val entry = expected.firstOrNull { it.path == path }
                        ?: return@use VerifyOutcome(false, 0, "$path is not in the manifest")
                    if (zip.getEntry(path) == null) {
                        return@use VerifyOutcome(false, 0, "$path is missing")
                    }
                    written += entry.entries
                    reread += countRows(zip, path)
                }
                if (written != rows) {
                    return@use VerifyOutcome(
                        false,
                        reread,
                        "the category files hold $written rows, the scan reported $rows"
                    )
                }
                if (reread != rows) {
                    return@use VerifyOutcome(
                        false,
                        reread,
                        "$reread rows were re-read from the archive, the scan reported $rows"
                    )
                }
                VerifyOutcome(true, reread, "")
            }
        }.getOrElse { VerifyOutcome(false, 0, it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Counts the records inside one category entry of a finished archive.
     *
     * The document is walked with a streaming reader, so a 100,000-row category
     * costs a buffer and no objects.
     *
     * @param zip open archive.
     * @param path entry to read.
     * @return classes plus functions plus symbols in the entry.
     */
    private fun countRows(zip: ZipFile, path: String): Int {
        val entry = zip.getEntry(path) ?: return 0
        var rows = 0
        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
            val json = JsonReader(reader)
            json.beginObject()
            while (json.hasNext()) {
                val name = json.nextName()
                if (name != "classes" && name != "functions" && name != "symbols") {
                    json.skipValue()
                    continue
                }
                // The engine writes classes as a name-keyed object and the
                // other two as arrays; a reader must accept either.
                when (json.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        json.beginArray()
                        while (json.hasNext()) {
                            json.skipValue()
                            rows++
                        }
                        json.endArray()
                    }
                    JsonToken.BEGIN_OBJECT -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            json.nextName()
                            json.skipValue()
                            rows++
                        }
                        json.endObject()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        return rows
    }

    private fun jniEntryCount(summary: ScanResultSummary): Int {
        val union = summary.jni?.offsets?.size ?: 0
        val legacy = summary.vtables.size + summary.symbols.size + summary.expectedPatterns.size
        return maxOf(union, legacy)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val README_PATH = "README.txt"
    }
}

/**
 * Outcome of re-reading a finished archive.
 *
 * @property ok whether every check passed.
 * @property rereadRows internal records counted inside the archive.
 * @property detail the first failure, empty when [ok].
 */
data class VerifyOutcome(val ok: Boolean, val rereadRows: Int, val detail: String)

/** Counts bytes and digests them on the way to the ZIP entry. */
private class DigestCountStream(destination: OutputStream) : FilterOutputStream(destination) {
    private val digest = MessageDigest.getInstance("SHA-256")

    var bytes: Long = 0
        private set

    override fun write(value: Int) {
        digest.update(value.toByte())
        bytes += 1
        out.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        digest.update(buffer, offset, length)
        bytes += length
        out.write(buffer, offset, length)
    }

    fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
}
