package com.chimerateam.offsetextractor.data.cache

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException

/**
 * Serialises a completed scan so a later run can skip the work.
 *
 * The stored document is the full summary, not only the exported offsets, so a
 * cache hit can rebuild the same results panel without rescanning. Every read
 * is fail-closed: a truncated or outdated document is reported as a miss
 * rather than an exception, and the scan simply runs again.
 */
class ResultCache(private val cache: ScanCache) {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Stores [summary] under its [keyFor] key.
     *
     * @param summary the completed scan.
     * @return whether the summary was stored.
     */
    fun store(summary: ScanResultSummary): Boolean {
        if (summary.buildId.isBlank()) return false
        val envelope = Envelope(
            schemaVersion = SCHEMA_VERSION,
            summary = summary
        )
        val key = keyFor(summary.buildId, summary.engineVersion, summary.imageBase)
        // Serialize straight into the temporary file. `gson.toJson(envelope)`
        // built a second 30-100 MB String while the vtables and internal rows
        // were still live.
        return cache.writeDocument(key) { writer ->
            gson.toJson(envelope, writer)
        }
    }

    /**
     * Reads the summary stored for [buildId] by [engineVersion] at [imageBase].
     *
     * The key carries all three: a cached document from another engine, or one
     * whose addresses were derived from a different load base, is a miss and the
     * library is scanned again. Without the base the fix for the 0x2463f0 shift
     * would never reach a device that had already cached a scan, and without the
     * version a later naming change would be invisible for the same reason.
     *
     * @param buildId build identifier of the library.
     * @param engineVersion version of the engine that would consume the entry.
     * @param imageBase load base the caller will compute offsets against.
     * @return the stored summary, or `null` when there is no usable entry.
     */
    fun load(buildId: String, engineVersion: String, imageBase: Long): ScanResultSummary? {
        if (buildId.isBlank()) return null
        val file = cache.documentFor(keyFor(buildId, engineVersion, imageBase))
        if (!file.isFile) return null
        // Gson's Reader overload parses incrementally, so a cache hit never
        // materialises the whole document as a String first.
        val envelope = try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, Envelope::class.java)
            }
        } catch (malformed: JsonParseException) {
            // Covers a truncated document (JsonSyntaxException) and a payload
            // model that did not survive shrinking (JsonIOException). Either way
            // the cache is unusable, so the scan simply runs again.
            return null
        } catch (unreadable: java.io.IOException) {
            // The file disappeared or became unreadable between the isFile
            // check and the open. A cache read stays fail-closed.
            return null
        }
        if (envelope == null || envelope.schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val summary = envelope.summary ?: return null
        return if (summary.buildId == buildId) summary else null
    }

    private data class Envelope(
        val schemaVersion: Int,
        val summary: ScanResultSummary?
    )

    companion object {
        /**
         * Bumped whenever [ScanResultSummary] gains a field a hit must carry.
         *
         * 4: the summary gained `engineVersion`, and the internal class record
         * gained a nullable file offset.
         */
        private const val SCHEMA_VERSION = 4

        /**
         * Cache key for a scan: build identifier, engine version and load base.
         *
         * `ScanCache.documentFileName` keeps only letters, digits, `-` and `_`,
         * so the version's dots become underscores to keep the fields apart.
         *
         * @param buildId build identifier of the library.
         * @param engineVersion version of the engine that produced the scan.
         * @param imageBase load base the addresses were derived from.
         * @return the key, never blank.
         */
        fun keyFor(buildId: String, engineVersion: String, imageBase: Long): String {
            val engine = engineVersion.ifBlank { "unknown" }.replace('.', '_')
            return "$buildId-e$engine-b${imageBase.toString(16)}"
        }
    }
}
