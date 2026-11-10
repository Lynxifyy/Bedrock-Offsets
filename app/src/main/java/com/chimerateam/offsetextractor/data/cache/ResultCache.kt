package com.chimerateam.offsetextractor.data.cache

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException

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
     * Stores [summary] under its build identifier.
     *
     * @param summary the completed scan.
     * @return whether the summary was stored.
     */
    fun store(summary: ScanResultSummary): Boolean {
        if (summary.buildId.isBlank()) return false
        val envelope = Envelope(
            schemaVersion = SCHEMA_VERSION,
            summary = summary,
        )
        return cache.writeDocument(summary.buildId, gson.toJson(envelope))
    }

    /**
     * Reads the summary stored for [buildId].
     *
     * @param buildId build identifier of the library.
     * @return the stored summary, or `null` when there is no usable entry.
     */
    fun load(buildId: String): ScanResultSummary? {
        val text = cache.readDocument(buildId) ?: return null
        val envelope = try {
            gson.fromJson(text, Envelope::class.java)
        } catch (malformed: JsonSyntaxException) {
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
        val summary: ScanResultSummary?,
    )

    private companion object {
        /** Bumped whenever [ScanResultSummary] gains a field a hit must carry. */
        const val SCHEMA_VERSION = 1
    }
}
