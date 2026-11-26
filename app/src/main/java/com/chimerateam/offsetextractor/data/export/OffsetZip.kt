package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.util.formatBytes
import com.google.gson.JsonParser
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
 */
data class ZipBuildResult(
    val file: File,
    val manifest: List<ManifestEntry>,
    val verified: Boolean,
    val version: String,
    val bytes: Long = 0L,
    val internalRows: Int = 0,
    val categoryFiles: Int = 0
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

        val manifest = mutableListOf<ManifestEntry>()
        val categoryPaths = mutableListOf<String>()
        ZipOutputStream(BufferedOutputStream(target.outputStream(), BUFFER_BYTES)).use { zip ->
            // Level 9 spends several times the CPU of level 6 for a few
            // percent of size on JSON that is mostly digits and identifiers.
            zip.setLevel(6)

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

            val manifestText = buildManifest(summary, version, manifest)
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
        val expectedRows = InternalTable.expectedRows(summary)
        val verified = verify(target, manifest, categoryPaths, expectedRows)
        if (verified) {
            log("ZIP: verified (${manifest.size + 1} files, $expectedRows internal rows)")
        } else {
            log("ZIP: verification FAILED for ${target.name}")
        }
        onProgress(1f, if (verified) "Archive verified" else "Archive failed verification")
        return ZipBuildResult(
            file = target,
            manifest = manifest,
            verified = verified,
            version = version,
            bytes = target.length(),
            internalRows = split.rows,
            categoryFiles = categoryPaths.size
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
        entries: List<ManifestEntry>
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
     * @return whether every entry matched.
     */
    fun verify(
        archive: File,
        expected: List<ManifestEntry>,
        categoryPaths: List<String> = emptyList(),
        expectedInternalRows: Int? = null
    ): Boolean {
        if (!archive.isFile) return false
        if ((expectedInternalRows ?: 0) > 0 && categoryPaths.isEmpty()) return false
        return runCatching {
            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return@use false
                val manifest = zip.getInputStream(manifestEntry)
                    .bufferedReader(Charsets.UTF_8)
                    .use { JsonParser.parseReader(it).asJsonObject }
                val files = manifest.getAsJsonArray("files")
                if (files.size() != expected.size) return@use false
                if (manifest.get("entryCount")?.asInt != expected.size) return@use false
                for (index in 0 until files.size()) {
                    val item = files[index].asJsonObject
                    val path = item.get("path").asString
                    val size = item.get("size").asLong
                    val sha = item.get("sha256").asString
                    val entry = zip.getEntry(path) ?: return@use false
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
                    if (total != size) return@use false
                    if (hex(digest.digest()) != sha) return@use false
                }
                val rows = expectedInternalRows ?: return@use true
                if (rows <= 0) return@use true
                var written = 0
                for (path in categoryPaths) {
                    val entry = expected.firstOrNull { it.path == path } ?: return@use false
                    if (zip.getEntry(path) == null) return@use false
                    written += entry.entries
                }
                written == rows
            }
        }.getOrDefault(false)
    }

    private fun jniEntryCount(summary: ScanResultSummary): Int =
        summary.vtables.size + summary.symbols.size + summary.expectedPatterns.size

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

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
