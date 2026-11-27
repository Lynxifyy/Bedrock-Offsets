package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File

/**
 * Coverage of one bucket: a category, the gameplay total, or the whole library.
 *
 * The two numbers that matter are [realNamed] and [uniqueSignatures], both
 * counted against [functions]. Everything else is context for them.
 *
 * @property id category id, or `gameplay`/`overall` for the aggregate buckets.
 * @property label human-readable name of the bucket.
 * @property functions functions in the bucket.
 * @property realNamed functions whose name is neither `sub_<rva>` nor a
 *   `Class::vfunc_N` placeholder.
 * @property placeholderSlots functions named only by their declaring slot.
 * @property unnamed functions with no name at all.
 * @property signatures functions carrying a byte signature.
 * @property uniqueSignatures functions whose signature matched exactly once.
 * @property classes RTTI classes in the bucket.
 * @property vtableGroups vtable groups in the bucket, which is what the class
 *   rows would collapse to if groups were keyed by name.
 */
data class CoverageBucket(
    val id: String,
    val label: String,
    val functions: Int = 0,
    val realNamed: Int = 0,
    val placeholderSlots: Int = 0,
    val unnamed: Int = 0,
    val signatures: Int = 0,
    val uniqueSignatures: Int = 0,
    val classes: Int = 0,
    val vtableGroups: Int = 0
) {
    /** Share of functions with a real name, in percent. */
    val realNamedPercent: Double
        get() = if (functions == 0) 0.0 else realNamed * 100.0 / functions

    /** Share of functions with a unique signature, in percent. */
    val uniqueSignaturePercent: Double
        get() = if (functions == 0) 0.0 else uniqueSignatures * 100.0 / functions

    /** This bucket plus [other], for the aggregate rows. */
    fun plus(other: CoverageBucket): CoverageBucket = CoverageBucket(
        id = id,
        label = label,
        functions = functions + other.functions,
        realNamed = realNamed + other.realNamed,
        placeholderSlots = placeholderSlots + other.placeholderSlots,
        unnamed = unnamed + other.unnamed,
        signatures = signatures + other.signatures,
        uniqueSignatures = uniqueSignatures + other.uniqueSignatures,
        classes = classes + other.classes,
        vtableGroups = vtableGroups + other.vtableGroups
    )
}

/**
 * Per-category coverage of a finished scan.
 *
 * @property categories one bucket per category document, in document order.
 * @property gameplay every category except `thirdparty`, which is the figure a
 *   Bedrock modder cares about.
 * @property overall every category.
 */
data class CoverageReport(
    val categories: List<CoverageBucket>,
    val gameplay: CoverageBucket,
    val overall: CoverageBucket
) {
    /** Whether any bucket holds functions at all. */
    val isEmpty: Boolean get() = overall.functions == 0
}

/**
 * Counts real names and unique signatures from the engine's category documents.
 *
 * The engine classifies while it writes, so the scan leaves one document per
 * category rather than a single table. This walks those documents with a
 * streaming reader instead of loading them: the largest is over a hundred
 * megabytes on a real library, and the tablet has three gigabytes.
 *
 * The counting rules are the ones `scripts/coverage_report.py` uses, so the
 * screen and the report cannot disagree about what a real name is.
 */
object CoverageCalculator {

    /** Category whose functions are library code no mod navigates. */
    const val THIRD_PARTY_CATEGORY = "thirdparty"

    private const val PLACEHOLDER_PREFIX = "sub_"
    private const val PLACEHOLDER_MARKER = "::vfunc_"

    /**
     * Measures every category document of [summary].
     *
     * @param summary the scan result whose shards are read.
     * @return the report, or `null` when the scan recorded no category
     *   documents to read.
     */
    fun compute(summary: ScanResultSummary): CoverageReport? {
        val shards = summary.internal?.shards.orEmpty().filter { it.path.isNotBlank() }
        if (shards.isEmpty()) return null
        val buckets = LinkedHashMap<String, CoverageBucket>()
        for (shard in shards) {
            val file = File(shard.path)
            if (!file.isFile) continue
            val measured = runCatching { measure(file, shard) }.getOrNull() ?: continue
            val previous = buckets[measured.id]
            buckets[measured.id] = previous?.plus(measured) ?: measured
        }
        if (buckets.isEmpty()) return null
        val categories = buckets.values.toList()
        val overall = categories.fold(emptyBucket("overall", "Overall")) { acc, bucket ->
            acc.plus(bucket)
        }
        val gameplay = categories
            .filter { it.id != THIRD_PARTY_CATEGORY }
            .fold(emptyBucket("gameplay", "Gameplay")) { acc, bucket -> acc.plus(bucket) }
        return CoverageReport(categories, gameplay, overall)
    }

    private fun emptyBucket(id: String, label: String) = CoverageBucket(id, label)

    /**
     * Counts one category document.
     *
     * @param file the document.
     * @param shard the part the scan recorded, used for the id and label when
     *   the document itself does not carry them.
     */
    private fun measure(
        file: File,
        shard: com.chimerateam.offsetextractor.data.native.ShardPart
    ): CoverageBucket {
        var id = shard.id.ifBlank { shard.category }
        var label = shard.label.ifBlank { id }
        var functions = 0
        var realNamed = 0
        var placeholderSlots = 0
        var unnamed = 0
        var signatures = 0
        var uniqueSignatures = 0
        var classes = 0
        var vtableGroups = 0

        file.bufferedReader().use { input ->
            val reader = JsonReader(input)
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "category" -> id = reader.nextString()
                    "label" -> label = reader.nextString()
                    "classes" -> {
                        val counted = readClasses(reader)
                        classes += counted.first
                        vtableGroups += counted.second
                    }
                    "functions" -> {
                        val counted = readFunctions(reader)
                        functions += counted.functions
                        realNamed += counted.realNamed
                        placeholderSlots += counted.placeholderSlots
                        unnamed += counted.unnamed
                        signatures += counted.signatures
                        uniqueSignatures += counted.uniqueSignatures
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return CoverageBucket(
            id = id,
            label = label,
            functions = functions,
            realNamed = realNamed,
            placeholderSlots = placeholderSlots,
            unnamed = unnamed,
            signatures = signatures,
            uniqueSignatures = uniqueSignatures,
            classes = classes,
            vtableGroups = vtableGroups
        )
    }

    /** Counts of the function section, kept separate so the walk stays readable. */
    private class FunctionCounts(
        val functions: Int,
        val realNamed: Int,
        val placeholderSlots: Int,
        val unnamed: Int,
        val signatures: Int,
        val uniqueSignatures: Int
    )

    private fun readFunctions(reader: JsonReader): FunctionCounts {
        var functions = 0
        var realNamed = 0
        var placeholderSlots = 0
        var unnamed = 0
        var signatures = 0
        var uniqueSignatures = 0
        reader.beginArray()
        while (reader.hasNext()) {
            var name = ""
            var source = "auto"
            var hasSignature = false
            var signatureUnique = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = nextStringOrEmpty(reader)
                    "nameSource" -> source = nextStringOrEmpty(reader)
                    "signature" -> hasSignature = present(reader)
                    "signatureUnique" -> signatureUnique = booleanOrFalse(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            functions++
            when {
                source == "auto" && name.startsWith(PLACEHOLDER_PREFIX) -> unnamed++
                name.contains(PLACEHOLDER_MARKER) -> placeholderSlots++
                name.startsWith(PLACEHOLDER_PREFIX) -> unnamed++
                name.isBlank() -> unnamed++
                else -> realNamed++
            }
            if (hasSignature) {
                signatures++
                if (signatureUnique) uniqueSignatures++
            }
        }
        reader.endArray()
        return FunctionCounts(
            functions,
            realNamed,
            placeholderSlots,
            unnamed,
            signatures,
            uniqueSignatures
        )
    }

    /**
     * Counts the class section.
     *
     * @return classes and vtable groups.
     */
    private fun readClasses(reader: JsonReader): Pair<Int, Int> {
        var classes = 0
        var groups = 0
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName()
            classes++
            var groupCount = -1
            var hasVtable = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "vtableGroups" -> {
                        if (isNull(reader)) continue
                        groupCount = 0
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.skipValue()
                            groupCount++
                        }
                        reader.endArray()
                    }
                    "vtable" -> hasVtable = present(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            groups += when {
                groupCount >= 0 -> groupCount
                hasVtable -> 1
                else -> 0
            }
        }
        reader.endObject()
        return classes to groups
    }

    private fun nextStringOrEmpty(reader: JsonReader): String =
        if (isNull(reader)) "" else reader.nextString()

    /** Consumes a value, reporting whether it was anything but `null`. */
    private fun present(reader: JsonReader): Boolean {
        if (isNull(reader)) return false
        reader.skipValue()
        return true
    }

    private fun booleanOrFalse(reader: JsonReader): Boolean =
        if (isNull(reader)) false else reader.nextBoolean()

    private fun isNull(reader: JsonReader): Boolean {
        if (reader.peek() != JsonToken.NULL) return false
        reader.nextNull()
        return true
    }
}
