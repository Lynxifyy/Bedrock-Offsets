package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.VerificationStatus
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Maximum rows a single search returns before the UI asks for a narrower one. */
const val INTERNAL_SEARCH_LIMIT = 200

/**
 * File-backed index over the internal C++ table.
 *
 * The table can hold hundreds of thousands of functions, so the UI never loads
 * it. A search streams the NDJSON file once and stops at
 * [INTERNAL_SEARCH_LIMIT], which keeps both memory and latency flat.
 *
 * @param table NDJSON file written by the engine, or `null` when Part 2 has not
 *   run.
 */
class InternalIndex(private val table: File?) {

    /** Whether a table is present and readable. */
    fun isAvailable(): Boolean = table?.isFile == true

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
        val file = table ?: return emptyList()
        if (needle.isEmpty() || !file.isFile) return emptyList()
        val rows = mutableListOf<OffsetRow>()
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
        return rows
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

    private fun parse(line: String): JsonObject? =
        runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()

    private fun statusOf(status: String): VerificationStatus = when (status) {
        "verified" -> VerificationStatus.Verified
        "ambiguous", "suspect", "conflict" -> VerificationStatus.Conflict
        else -> VerificationStatus.Unverified
    }
}
