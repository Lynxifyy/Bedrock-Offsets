package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
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
 *   and digest.
 * @property version version label shared by every file name.
 */
data class ZipBuildResult(
    val file: File,
    val manifest: List<ManifestEntry>,
    val verified: Boolean,
    val version: String
)

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
 */
class OffsetZipBuilder(
    private val classifier: Classifier,
    private val workDir: File
) {

    /**
     * Writes the archive.
     *
     * @param summary the completed scan; its internal table is read in place.
     * @param target destination archive.
     * @param includeCombined whether to add one combined internal document.
     * @param onProgress receives `(fraction, message)` with `fraction` in 0..1.
     * @return the archive and whether it verified.
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
        val table = summary.internal?.path
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        val split = if (table != null && table.isFile) {
            InternalTableSplitter(classifier, workDir).split(table) { percent, message ->
                onProgress(percent / 200f, message)
            }
        } else {
            SplitResult(emptyList(), emptyMap(), 0, 0, 0)
        }
        check(split.consistent) {
            "Category assignment is inconsistent: ${split.assignedFunctions} of " +
                "${split.totalFunctions} functions were assigned."
        }

        val manifest = mutableListOf<ManifestEntry>()
        ZipOutputStream(BufferedOutputStream(target.outputStream(), BUFFER_BYTES)).use { zip ->
            zip.setLevel(9)

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
                manifest += writeEntry(zip, name, part.counts.total) { writer ->
                    OffsetExporter.writeCategory(summary, part, writer)
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

        onProgress(0.95f, "Verifying the archive")
        val verified = verify(target, manifest)
        onProgress(1f, "Archive verified")
        return ZipBuildResult(target, manifest, verified, version)
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
     * archive throws here rather than at the client.
     *
     * @param archive the finished archive.
     * @param expected the manifest that was written.
     * @return whether every entry matched.
     */
    fun verify(archive: File, expected: List<ManifestEntry>): Boolean {
        if (!archive.isFile) return false
        return runCatching {
            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return@use false
                val manifest = zip.getInputStream(manifestEntry)
                    .bufferedReader(Charsets.UTF_8)
                    .use { JsonParser.parseReader(it).asJsonObject }
                val files = manifest.getAsJsonArray("files")
                if (files.size() != expected.size) return@use false
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
                true
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
