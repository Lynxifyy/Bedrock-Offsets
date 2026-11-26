package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.VerificationStatus
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.File
import java.io.IOException

/** Maximum rows a single search returns before the UI asks for a narrower one. */
const val INTERNAL_SEARCH_LIMIT = 200

/**
 * File-backed index over the internal C++ table.
 *
 * The table can hold hundreds of thousands of functions, so the UI never loads
 * it. A search streams every document once and stops at
 * [INTERNAL_SEARCH_LIMIT], which keeps both memory and latency flat.
 *
 * Two shapes exist and both are searched. A scan without category rules writes
 * one NDJSON file of `{"type":...}` records; the normal scan classifies as it
 * writes and produces one JSON document per category. Only the first shape used
 * to be understood, so on every real scan the search found nothing at all and
 * the 883k functions were unreachable from the UI.
 *
 * @param sources the documents written by the engine.
 */
class InternalIndex(private val sources: List<File>) {

    /**
     * Builds an index over whatever the engine wrote.
     *
     * @param summary the internal half of a scan, or `null` when Part 2 has not
     *   run.
     */
    constructor(summary: InternalSummary?) : this(filesOf(summary))

    /** Whether at least one document is present and readable. */
    fun isAvailable(): Boolean = sources.any { it.isFile }

    /** Documents the index reads, for the diagnostics line. */
    fun documentCount(): Int = sources.count { it.isFile }

    /**
     * Returns the rows matching [query].
     *
     * Matches names, aliases, mangled names, addresses, and `Class[slot]`
     * selectors. Returns an empty list for a blank query so the list stays
     * short until the user asks for something.
     *
     * @param query search text.
     * @param limit maximum rows to return.
     * @return matching rows, in file order.
     */
    fun search(query: String, limit: Int = INTERNAL_SEARCH_LIMIT): List<OffsetRow> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val rows = mutableListOf<OffsetRow>()
        for (file in sources) {
            if (rows.size >= limit) break
            if (!file.isFile) continue
            try {
                if (isCategoryDocument(file)) {
                    searchDocument(file, needle, limit, rows)
                } else {
                    searchNdjson(file, needle, limit, rows)
                }
            } catch (error: IOException) {
                // A document the engine is still writing, or one evicted under
                // memory pressure, must not take the whole search down.
                android.util.Log.w("InternalIndex", "skipping ${file.name}: ${error.message}")
            }
        }
        return rows
    }

    private fun searchNdjson(file: File, needle: String, limit: Int, rows: MutableList<OffsetRow>) {
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (rows.size >= limit) break
                val root = parse(line) ?: continue
                when (root.get("type")?.asString) {
                    "function" -> functionRow(
                        root.getAsJsonObject("function"),
                        needle
                    )?.let(rows::add)
                    "class" -> classRows(
                        root.getAsJsonObject("class"),
                        needle,
                        limit - rows.size,
                        rows
                    )
                }
            }
        }
    }

    private fun searchDocument(
        file: File,
        needle: String,
        limit: Int,
        rows: MutableList<OffsetRow>
    ) {
        file.reader().use { reader ->
            JsonReader(reader).use { json ->
                json.beginObject()
                while (json.hasNext() && rows.size < limit) {
                    when (json.nextName()) {
                        "functions" -> json.beginArray().also {
                            while (json.hasNext() && rows.size < limit) {
                                val element = JsonParser.parseReader(json) as? JsonObject
                                if (element != null) functionRow(element, needle)?.let(rows::add)
                            }
                            json.endArray()
                        }
                        "symbols" -> {
                            json.beginArray()
                            while (json.hasNext() && rows.size < limit) {
                                val element = JsonParser.parseReader(json) as? JsonObject
                                if (element != null) symbolRow(element, needle)?.let(rows::add)
                            }
                            json.endArray()
                        }
                        "classes" -> {
                            json.beginObject()
                            while (json.hasNext() && rows.size < limit) {
                                json.nextName()
                                val element = JsonParser.parseReader(json) as? JsonObject
                                if (element != null) {
                                    classRows(element, needle, limit - rows.size, rows)
                                }
                            }
                            json.endObject()
                        }
                        else -> json.skipValue()
                    }
                }
            }
        }
    }

    private fun symbolRow(symbol: JsonObject, needle: String): OffsetRow? {
        val name = symbol.get("name")?.asString ?: return null
        val address = symbol.get("address")?.asString ?: "0x0"
        val mangled = symbol.get("mangled")?.takeIf { !it.isJsonNull }?.asString
        val matches = name.contains(needle, ignoreCase = true) ||
            address.contains(needle, ignoreCase = true) ||
            mangled?.contains(needle, ignoreCase = true) == true
        if (!matches) return null
        return OffsetRow(
            category = OffsetCategory.Symbol,
            name = name,
            addressHex = address,
            detail = "data · " + (symbol.get("section")?.asString ?: "?"),
            status = statusOf(symbol.get("status")?.asString ?: "verified")
        )
    }

    private fun functionRow(function: JsonObject, needle: String): OffsetRow? {
        val name = function.get("name")?.asString ?: return null
        val address = function.get("address")?.asString ?: "0x0"
        val aliases = function.get("aliases")?.takeIf { it.isJsonArray }
            ?.asJsonArray?.mapNotNull { it.asString } ?: emptyList()
        val mangled = function.get("mangled")?.takeIf { !it.isJsonNull }?.asString
        val matches = name.contains(needle, ignoreCase = true) ||
            address.contains(needle, ignoreCase = true) ||
            mangled?.contains(needle, ignoreCase = true) == true ||
            aliases.any { it.contains(needle, ignoreCase = true) }
        if (!matches) return null
        val source = function.get("nameSource")?.asString ?: "auto"
        val kind = function.get("kind")?.asString ?: "function"
        val prologue = function.get("prologueKind")?.asString ?: "none"
        val status = function.get("status")?.asString ?: "fde_only"
        return OffsetRow(
            category = OffsetCategory.InternalCpp,
            name = name,
            addressHex = address,
            detail = "$source · $kind · $prologue",
            status = statusOf(status)
        )
    }

    private fun classRows(
        info: JsonObject,
        needle: String,
        remaining: Int,
        rows: MutableList<OffsetRow>
    ) {
        if (remaining <= 0) return
        val className = info.get("name")?.asString ?: return
        val selector = Regex("""^(.+)\[(\d+)]$""").matchEntire(needle)
        if (selector != null) {
            val wantedClass = selector.groupValues[1]
            val wantedSlot = selector.groupValues[2].toIntOrNull() ?: return
            if (!className.contains(wantedClass, ignoreCase = true)) return
            val slots = info.get("slots")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
            val slot = slots.firstOrNull { it.asJsonObject.get("slot")?.asInt == wantedSlot }
                ?.asJsonObject ?: return
            rows.add(
                OffsetRow(
                    category = OffsetCategory.VirtualSlot,
                    name = slot.get("name")?.asString ?: className,
                    addressHex = slot.get("address")?.asString ?: "0x0",
                    detail = "slot $wantedSlot · ${slot.get("kind")?.asString ?: "virtual"}",
                    status = statusOf(slot.get("status")?.asString ?: "verified")
                )
            )
            return
        }
        if (!className.contains(needle, ignoreCase = true)) return
        val slotCount = info.get("slotCount")?.asInt ?: 0
        rows.add(
            OffsetRow(
                category = OffsetCategory.VirtualSlot,
                name = className,
                addressHex = info.get("vtable")?.asString ?: "0x0",
                detail = "$slotCount virtual slots"
            )
        )
        val slots = info.get("slots")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (element in slots) {
            if (rows.size >= INTERNAL_SEARCH_LIMIT || rows.size >= remaining) break
            val slot = element.asJsonObject
            rows.add(
                OffsetRow(
                    category = OffsetCategory.VirtualSlot,
                    name = slot.get("name")?.asString ?: className,
                    addressHex = slot.get("address")?.asString ?: "0x0",
                    detail = "slot ${slot.get("slot")?.asInt ?: 0} · " +
                        (slot.get("kind")?.asString ?: "virtual"),
                    status = statusOf(slot.get("status")?.asString ?: "verified")
                )
            )
        }
    }

    /**
     * Whether [file] is one of the per-category documents.
     *
     * They open with the document header, so the marker is inside the first
     * few hundred bytes; reading the head keeps the check cheap for a 40 MB
     * file.
     */
    private fun isCategoryDocument(file: File): Boolean {
        val head = ByteArray(512)
        file.inputStream().use { stream ->
            val read = stream.read(head)
            if (read <= 0) return false
            return String(head, 0, read, Charsets.UTF_8)
                .contains("\"kind\":\"internal_cpp_category\"")
        }
    }

    private fun parse(line: String): JsonObject? =
        runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()

    private fun statusOf(status: String): VerificationStatus = when (status) {
        "verified" -> VerificationStatus.Verified
        "ambiguous", "suspect", "conflict" -> VerificationStatus.Conflict
        else -> VerificationStatus.Unverified
    }

    private companion object {
        /** Documents the engine wrote for [summary], in a stable order. */
        fun filesOf(summary: InternalSummary?): List<File> {
            if (summary == null) return emptyList()
            if (summary.shards.isNotEmpty()) {
                return summary.shards.map { File(it.path) }
            }
            val table = summary.path
            return if (table.isBlank()) emptyList() else listOf(File(table))
        }
    }
}
