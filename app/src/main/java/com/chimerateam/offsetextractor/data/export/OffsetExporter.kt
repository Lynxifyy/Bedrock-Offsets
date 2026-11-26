package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.StringWriter
import java.io.Writer

/**
 * The two documents the app produces and their exact file names.
 *
 * Keeping the prefixes in one place makes a later rename a one-line edit. The
 * internal prefix is `INTERNALCPP`, not `INTERNALC++`: a `+` in a file name is
 * accepted by Android but breaks in URLs, shells and several tools.
 */
object ExportNames {

    /** Prefix of the JNI offsets document. */
    const val JNI_PREFIX = "JNI_OFFSETS"

    /** Prefix of the internal C++ offsets document. */
    const val INTERNAL_PREFIX = "INTERNALCPP_OFFSETS"

    /** Extension shared by both documents. */
    const val EXTENSION = ".json"
}

/** Which of the two documents an export action targets. */
enum class ExportKind {
    /** JNI signatures, symbols and vtables; the schema-v1 document. */
    Jni,

    /** Every internal C++ function and virtual slot. */
    Internal
}

/**
 * Serialises a scan summary into the JSON a loader consumes.
 *
 * The JNI document carries the identity of the library it came from, so a
 * loader can refuse offsets that belong to a different build, and a flat
 * `offsets` map of name to address, which is what a loader actually reads. Its
 * schema stays at version 1; the new fields are additive.
 */
object OffsetExporter {

    /** Schema version of the JNI document; bumped when the shape breaks. */
    const val SCHEMA_VERSION = 1

    private val streamingGson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    private val versionPattern = Regex("""(\d+)[-._](\d+)(?:[-._](\d+))?""")

    /**
     * Renders the JNI document as pretty-printed JSON.
     *
     * @param summary the completed scan.
     * @return a JSON document ending in a newline.
     */
    fun toJson(summary: ScanResultSummary): String {
        // One implementation for both paths: Copy and Share must produce the
        // same document the archive holds, and a second shape drifted once
        // already.
        val out = StringWriter()
        writeJni(summary, out)
        return out.toString()
    }

    /**
     * Streams the JNI document into [writer].
     *
     * Used for the save dialog so a large symbol table is never first built as
     * one `String`.
     *
     * @param summary the completed scan.
     * @param writer destination writer.
     */
    fun writeJni(summary: ScanResultSummary, writer: Writer) {
        // Streaming straight into the writer keeps the document off the heap.
        // Building the object graph first held every vtable and symbol twice
        // while the archive was being written, which is what turned a large
        // export into an OutOfMemoryError.
        val json = JsonWriter(writer)
        json.setIndent("  ")
        json.beginObject()
        json.name("schemaVersion").value(SCHEMA_VERSION)
        json.name("generatedBy").value("BedrockOffsetExtractor")
        json.name("source").beginObject()
        json.name("apk").value(summary.displayName)
        json.name("apkVersion").value(versionLabel(summary))
        json.name("apkSizeBytes").value(summary.apkSizeBytes)
        json.name("architecture").value(summary.architecture)
        json.name("librarySha256").value(summary.sha256)
        json.name("librarySizeBytes").value(summary.sizeBytes)
        json.name("buildId").value(summary.buildId)
        json.name("buildIdSource").value(summary.buildIdSource)
        json.name("entryPoint").value(summary.entryPoint)
        summary.textSection?.let { text ->
            json.name("textAddress").value(hex(text.address))
            json.name("textSize").value(text.size)
            json.name("textDelta").value(hex(text.delta))
        }
        json.endObject()
        json.name("offsets").beginObject()
        for ((name, offset) in buildOffsets(summary)) {
            json.name(name).value(offset)
        }
        json.endObject()
        json.name("verification").beginObject()
        json.name("verified").value(summary.verification.verified)
        json.name("conflicts").value(summary.verification.conflicts)
        json.name("unverified").value(summary.verification.unverified)
        json.name("confidencePercent").value(summary.verification.confidencePercent)
        json.endObject()
        json.name("vtables").beginObject()
        for (vtable in summary.vtables.entries.values.sortedBy { it.vtableAddress }) {
            json.name(vtable.name).beginObject()
            json.name("address").value(vtable.vtableHex)
            json.name("offset").value(OffsetRows.hex(vtable.vtableOffset))
            json.name("typeInfo").value(OffsetRows.hex(vtable.typeInfoAddress))
            json.endObject()
        }
        json.endObject()
        json.name("symbols").beginObject()
        for (symbol in summary.symbols.sortedBy { it.address }) {
            json.name(symbol.name).value(symbol.addressHex)
        }
        json.endObject()
        writeJniUnion(json, summary)
        json.name("stats").beginObject()
        json.name("expectedSignatures").value(summary.expectedPatterns.size)
        json.name("matchedSignatures").value(summary.patternHits.map { it.name }.distinct().size)
        json.name("discardedMatches").value(summary.discardedMatches)
        json.name("vtables").value(summary.vtables.size)
        json.name("symbols").value(summary.symbols.size)
        json.name("verifiedOffsets").value(summary.verification.verified)
        json.name("conflictingOffsets").value(summary.verification.conflicts)
        json.name("unverifiedOffsets").value(summary.verification.unverified)
        json.name("confidencePercent").value(summary.verification.confidencePercent)
        json.endObject()
        json.endObject()
        json.flush()
        writer.write("\n")
        writer.flush()
    }

    /**
     * Writes the union of every JNI source, one status per pattern, and the
     * coverage block.
     *
     * The `offsets` map above is the pattern-only view kept for clients that
     * already read it. `jniOffsets` is the complete set: exported symbols,
     * `JNI_*` lifecycle exports, `RegisterNatives` tables and patterns, each
     * with its source and confidence. A native no pattern knows is here, and a
     * pattern that found nothing says so instead of vanishing.
     *
     * @param json writer positioned inside the document object.
     * @param summary the completed scan.
     */
    private fun writeJniUnion(json: JsonWriter, summary: ScanResultSummary) {
        val report = summary.jni ?: return
        json.name("jniOffsets").beginArray()
        for (offset in report.offsets) {
            json.beginObject()
            json.name("javaName").value(offset.javaName)
            offset.signature?.let { json.name("signature").value(it) }
            offset.className?.let { json.name("class").value(it) }
            json.name("address").value(offset.addressHex)
            json.name("offset").value(OffsetRows.hex(offset.address - summary.imageBase))
            json.name("source").value(offset.source)
            json.name("confidence").value(offset.confidence)
            json.endObject()
        }
        json.endArray()
        json.name("signatures").beginArray()
        for (pattern in report.patterns) {
            json.beginObject()
            json.name("name").value(pattern.name)
            json.name("status").value(pattern.status)
            json.name("hits").value(pattern.hits)
            json.endObject()
        }
        json.endArray()
        val coverage = report.coverage
        json.name("coverage").beginObject()
        json.name("exports").value(coverage.exports)
        json.name("entryPoints").value(coverage.entryPoints)
        json.name("registered").value(coverage.registered)
        json.name("patterns").value(coverage.patterns)
        json.name("patternsMatched").value(coverage.patternsMatched)
        json.name("patternsAmbiguous").value(coverage.patternsAmbiguous)
        json.name("patternsNotFound").value(coverage.patternsNotFound)
        json.name("patternsStale").beginArray()
        for (name in coverage.patternsStale) json.value(name)
        json.endArray()
        json.name("union").value(coverage.union)
        json.name("truncated").value(coverage.truncated)
        json.endObject()
    }

    /**
     * Writes the plain-text guide at the root of the archive.
     *
     * A developer who opens the ZIP sees a directory tree with no explanation,
     * so this file says what each folder holds, what a reader must check before
     * trusting a number, and how the coverage blocks are to be read.
     *
     * @param summary the completed scan.
     * @param split the category documents in the archive.
     * @param writer destination writer.
     */
    fun writeReadme(summary: ScanResultSummary, split: SplitResult, writer: Writer) {
        val coverage = summary.internal?.coverage
        val jni = summary.jni?.coverage
        writer.write("BEDROCK_OFFSETS ${versionLabel(summary)}\n")
        writer.write("Generated by BedrockOffsetExtractor\n")
        writer.write("Source APK: ${summary.displayName}\n")
        writer.write("Library: libminecraftpe.so, AArch64, ${summary.sizeBytes} bytes\n")
        writer.write("Library SHA-256: ${summary.sha256}\n")
        writer.write("Build ID: ${summary.buildId} (${summary.buildIdSource})\n")
        if (coverage != null) {
            writer.write("Internal completeness: ${coverage.summaryLine} " +
                "from ${coverage.functionSource}, ${coverage.classes} classes, " +
                "${coverage.slots} slots, ${coverage.dataSymbols} data symbols\n")
            if (!coverage.functionsMatchFdes) {
                writer.write("WARNING: ${coverage.fdeMissing} functions from .eh_frame are " +
                    "missing from the tables.\n")
            }
        }
        if (jni != null) {
            writer.write("JNI coverage: ${jni.exports} exports, ${jni.entryPoints} lifecycle " +
                "entry points, ${jni.registered} registered natives, " +
                "${jni.patternsMatched} of ${jni.patterns} signatures matched, " +
                "${jni.union} offsets in total\n")
        }
        writer.write("\n")
        writer.write("WHAT IS HERE\n")
        writer.write("  manifest.json\n")
        writer.write("      Every file with its size, entry count and SHA-256, the coverage\n")
        writer.write("      blocks, and the row count of every category. Read this first.\n")
        writer.write("  JNI/JNI_OFFSETS_<version>.json\n")
        writer.write("      Every JNI entry point, combined, with the union of all sources.\n")
        writer.write("  JNI/JNI_<PACKAGE>_<version>.json\n")
        writer.write("      The same offsets split by Java package group.\n")
        writer.write("  InternalCPP/INTERNALCPP_INDEX_<version>.json\n")
        writer.write("      Every category with its file, counts, and the class-to-category map.\n")
        writer.write("  InternalCPP/INTERNALCPP_<CATEGORY>_<version>.json\n")
        writer.write("      One file per category: its classes with every virtual slot, its\n")
        writer.write("      functions with signatures, and its data symbols. A category with\n")
        writer.write("      more than ${UNNAMED_PART_SIZE} rows is split into _PART_NN files.\n")
        writer.write("\n")
        writer.write("CATEGORIES IN THIS ARCHIVE\n")
        for (part in split.parts.sortedBy { it.id }) {
            writer.write(
                "  %-32s %7d rows (%d classes, %d functions, %d symbols)\n".format(
                    part.id,
                    part.counts.total,
                    part.counts.classes,
                    part.counts.functions,
                    part.counts.symbols
                )
            )
        }
        writer.write("\n")
        writer.write("HOW TO LOAD\n")
        writer.write("  1. Read manifest.json and compare librarySha256 with the\n")
        writer.write("     libminecraftpe.so you loaded. An offset from another build is a\n")
        writer.write("     wrong number, not a stale one.\n")
        writer.write("  2. Addresses are virtual addresses in the loaded image. `offset` is\n")
        writer.write("     `address - imageBase`, which survives a rebase.\n")
        writer.write("  3. `confidence` says how the address was obtained: `high` when the\n")
        writer.write("     linker or the loader wrote it (symbol, RegisterNatives table),\n")
        writer.write("     `medium` when a signature and a decoded prologue agree, `low`\n")
        writer.write("     when nothing confirmed it.\n")
        writer.write("  4. A signature whose `status` is `symbol_only` was replaced by its\n")
        writer.write("     symbol: the signature needs updating, the offset is still right.\n")
        writer.write("\n")
        writer.write("See docs/OUTPUT_FORMAT.md in the Bedrock-Offsets repository for the\n")
        writer.write("field-by-field description.\n")
        writer.flush()
    }

    /**
     * Streams the internal C++ document into [writer].
     *
     * The function and class rows live in the NDJSON table the engine wrote, so
     * the document is assembled in three passes over that file: classes,
     * functions, then the flat `offsets` map. Nothing larger than one row is
     * ever held in memory.
     *
     * @param summary the completed scan; `internal` must carry the table path.
     * @param writer destination writer.
     */
    fun writeInternal(summary: ScanResultSummary, writer: Writer) {
        val table = summary.internal?.path?.takeIf { it.isNotBlank() }?.let(::File)
        val json = JsonWriter(writer)
        json.setIndent("  ")
        json.beginObject()
        json.name("schemaVersion").value(SCHEMA_VERSION)
        json.name("generatedBy").value("BedrockOffsetExtractor")
        json.name("kind").value("internal_cpp")
        writeInternalSource(json, summary)
        writeInternalStats(json, summary)
        writeInternalClasses(json, table)
        writeInternalFunctions(json, table)
        writeInternalSymbols(json, table)
        writeInternalOffsets(json, table)
        json.endObject()
        json.flush()
        writer.write("\n")
        writer.flush()
    }

    /**
     * Streams the category index.
     *
     * The index is the entry point of the `InternalCPP/` folder: it lists every
     * category file with its counts and maps each class to its category, so a
     * client can jump straight to the file it needs.
     *
     * @param summary the completed scan.
     * @param split the category files produced by [InternalTableSplitter].
     * @param writer destination writer.
     */
    fun writeInternalIndex(summary: ScanResultSummary, split: SplitResult, writer: Writer) {
        val json = JsonWriter(writer)
        json.setIndent("  ")
        json.beginObject()
        json.name("schemaVersion").value(SCHEMA_VERSION)
        json.name("generatedBy").value("BedrockOffsetExtractor")
        json.name("kind").value("internal_cpp_index")
        json.name("version").value(versionLabel(summary))
        writeInternalSource(json, summary)
        json.name("stats").beginObject()
        json.name("classes").value(split.totalClasses)
        json.name("functions").value(split.totalFunctions)
        json.name("dataSymbols").value(split.totalSymbols)
        json.name("categories").value(split.parts.size)
        json.endObject()
        json.name("categories").beginArray()
        for (part in split.parts) {
            json.beginObject()
            json.name("id").value(part.id)
            json.name("category").value(part.categoryId)
            json.name("label").value(part.label)
            json.name("file").value(
                "InternalCPP/${categoryFileName(part.id, summary)}"
            )
            if (part.part > 0) json.name("part").value(part.part)
            json.name("classes").value(part.counts.classes)
            json.name("functions").value(part.counts.functions)
            json.name("symbols").value(part.counts.symbols)
            json.endObject()
        }
        json.endArray()
        json.name("classToCategory").beginObject()
        split.classToCategory.toSortedMap().forEach { (name, category) ->
            json.name(name).value(category)
        }
        json.endObject()
        json.endObject()
        json.flush()
        writer.write("\n")
        writer.flush()
    }

    /**
     * Streams one category file.
     *
     * @param summary the completed scan.
     * @param part the category file to write.
     * @param writer destination writer.
     */
    fun writeCategory(summary: ScanResultSummary, part: CategoryPart, writer: Writer) {
        val json = JsonWriter(writer)
        json.setIndent("  ")
        json.beginObject()
        json.name("schemaVersion").value(SCHEMA_VERSION)
        json.name("generatedBy").value("BedrockOffsetExtractor")
        json.name("kind").value("internal_cpp_category")
        json.name("category").value(part.categoryId)
        json.name("label").value(part.label)
        if (part.part > 0) json.name("part").value(part.part)
        json.name("version").value(versionLabel(summary))
        writeInternalSource(json, summary)
        json.name("stats").beginObject()
        json.name("classes").value(part.counts.classes)
        json.name("functions").value(part.counts.functions)
        json.name("symbols").value(part.counts.symbols)
        json.name("entries").value(part.counts.total)
        json.endObject()
        json.name("classes").beginObject()
        part.classFile?.let { file ->
            file.forEachLine { line ->
                val record = parseRecord(line, "class") ?: return@forEachLine
                val name = record.get("name")?.asString ?: return@forEachLine
                json.name(name).jsonValue(record.toString())
            }
        }
        json.endObject()
        json.name("functions").beginArray()
        for (file in part.functionFiles) {
            file.forEachLine { line ->
                val record = parseRecord(line, "function") ?: return@forEachLine
                json.jsonValue(record.toString())
            }
        }
        json.endArray()
        json.name("symbols").beginArray()
        part.symbolFile?.let { file ->
            file.forEachLine { line ->
                val record = parseRecord(line, "symbol") ?: return@forEachLine
                json.jsonValue(record.toString())
            }
        }
        json.endArray()
        json.endObject()
        json.flush()
        writer.write("\n")
        writer.flush()
    }

    /**
     * Version label used by every file name and by the `source.apkVersion`
     * field. One function, so a name can never disagree with the document.
     *
     * 1. The `versionName` read from the APK manifest, e.g. `26.60.3`.
     * 2. A version inside the APK display name, e.g. `minecraft-26-66.apk`.
     * 3. The first eight characters of the build identifier.
     *
     * Dots are preserved; only characters that are unsafe in a file name are
     * removed.
     *
     * @param summary the completed scan.
     * @return a sanitised label such as `26.60.3`.
     */
    fun versionLabel(summary: ScanResultSummary): String {
        val fromManifest = summary.apkVersion.trim()
        val candidate = if (fromManifest.isNotEmpty()) {
            fromManifest
        } else {
            val name = summary.displayName
            val marker = name.indexOf("minecraft", ignoreCase = true)
            val tail = if (marker >= 0) {
                name.substring(marker + "minecraft".length)
            } else {
                name
            }
            val match = versionPattern.find(tail)
            when {
                match != null -> match.groupValues.drop(1)
                    .filter { it.isNotEmpty() }
                    .joinToString(".")
                summary.buildId.isNotBlank() -> summary.buildId.take(8)
                else -> summary.sha256.take(8)
            }
        }
        return sanitize(candidate).ifBlank { "unknown" }
    }

    /**
     * File name of the JNI document, for example `JNI_OFFSETS_26.66.json`.
     *
     * @param summary the completed scan.
     */
    fun jniFileName(summary: ScanResultSummary): String =
        "${ExportNames.JNI_PREFIX}_${versionLabel(summary)}${ExportNames.EXTENSION}"

    /**
     * File name of the internal document, for example
     * `INTERNALCPP_OFFSETS_26.66.json`.
     *
     * @param summary the completed scan.
     */
    fun internalFileName(summary: ScanResultSummary): String =
        "${ExportNames.INTERNAL_PREFIX}_${versionLabel(summary)}${ExportNames.EXTENSION}"

    /** File name of the category index, e.g. `INTERNALCPP_INDEX_26.60.3.json`. */
    fun internalIndexFileName(summary: ScanResultSummary): String =
        "INTERNALCPP_INDEX_${versionLabel(summary)}${ExportNames.EXTENSION}"

    /**
     * File name of one category file.
     *
     * @param part category and part identifier, e.g. `actor_entity`.
     * @param summary the completed scan.
     */
    fun categoryFileName(part: String, summary: ScanResultSummary): String =
        "INTERNALCPP_${part.uppercase()}_${versionLabel(summary)}${ExportNames.EXTENSION}"

    /** File name of the single ZIP, e.g. `BEDROCK_OFFSETS_26.60.3.zip`. */
    fun zipFileName(summary: ScanResultSummary): String =
        "BEDROCK_OFFSETS_${versionLabel(summary)}.zip"

    /**
     * File name for [kind].
     *
     * @param kind which document is being exported.
     * @param summary the completed scan.
     */
    fun fileNameFor(kind: ExportKind, summary: ScanResultSummary): String = when (kind) {
        ExportKind.Jni -> jniFileName(summary)
        ExportKind.Internal -> internalFileName(summary)
    }

    private fun sanitize(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    private fun writeInternalSource(json: JsonWriter, summary: ScanResultSummary) {
        json.name("source").beginObject()
        json.name("apk").value(summary.displayName)
        json.name("apkVersion").value(versionLabel(summary))
        json.name("architecture").value(summary.architecture)
        json.name("librarySha256").value(summary.sha256)
        json.name("buildId").value(summary.buildId)
        json.name("imageBase").value(hex(summary.imageBase))
        summary.textSection?.let { text ->
            json.name("textAddress").value(hex(text.address))
            json.name("textSize").value(text.size)
            json.name("textDelta").value(hex(text.delta))
        }
        json.endObject()
    }

    private fun writeInternalStats(json: JsonWriter, summary: ScanResultSummary) {
        val internal = summary.internal
        json.name("stats").beginObject()
        json.name("classes").value(internal?.classes ?: 0)
        json.name("vtableSlots").value(internal?.vtableSlots ?: 0)
        json.name("functions").value(internal?.functions ?: 0)
        json.name("named").value(internal?.named ?: 0)
        json.name("autoNamed").value(internal?.autoNamed ?: 0)
        json.name("verified").value(internal?.verified ?: 0)
        json.name("ambiguous").value(internal?.ambiguous ?: 0)
        json.name("notFound").value(internal?.notFound ?: 0)
        json.name("signatures").value(internal?.signatures ?: 0)
        json.name("signaturesUnique").value(internal?.signaturesUnique ?: 0)
        json.name("slotsWithSizeZero").value(internal?.slotsWithSizeZero ?: 0)
        json.name("dataSymbols").value(internal?.dataSymbols ?: 0)
        json.endObject()
    }

    private fun writeInternalClasses(json: JsonWriter, table: File?) {
        json.name("classes").beginObject()
        if (table != null && table.isFile) {
            table.forEachLine { line ->
                val record = parseRecord(line, "class") ?: return@forEachLine
                val name = record.get("name")?.asString ?: return@forEachLine
                json.name(name).jsonValue(record.toString())
            }
        }
        json.endObject()
    }

    private fun writeInternalFunctions(json: JsonWriter, table: File?) {
        val duplicates = countFunctionNames(table)
        json.name("functions").beginArray()
        if (table != null && table.isFile) {
            table.forEachLine { line ->
                val record = parseRecord(line, "function") ?: return@forEachLine
                val name = record.get("name")?.asString
                val address = record.get("address")?.asString
                if (name != null && address != null && (duplicates[name] ?: 0) > 1) {
                    // The flat offsets map disambiguates a colliding name with
                    // `@address`; the alias list records that spelling too.
                    val aliases = record.getAsJsonArray("aliases")
                        ?: JsonArray().also { record.add("aliases", it) }
                    aliases.add("$name@$address")
                }
                json.jsonValue(record.toString())
            }
        }
        json.endArray()
    }

    private fun writeInternalSymbols(json: JsonWriter, table: File?) {
        json.name("symbols").beginArray()
        if (table != null && table.isFile) {
            table.forEachLine { line ->
                val record = parseRecord(line, "symbol") ?: return@forEachLine
                json.jsonValue(record.toString())
            }
        }
        json.endArray()
    }

    private fun writeInternalOffsets(json: JsonWriter, table: File?) {
        val duplicates = countFunctionNames(table)
        json.name("offsets").beginObject()
        if (table != null && table.isFile) {
            table.forEachLine { line ->
                val record = parseRecord(line, "function") ?: return@forEachLine
                val name = record.get("name")?.asString ?: return@forEachLine
                val address = record.get("address")?.asString ?: return@forEachLine
                val key = if ((duplicates[name] ?: 0) > 1) "$name@$address" else name
                json.name(key).value(address)
            }
        }
        json.endObject()
    }

    /** Counts how many functions share each name, streaming the table once. */
    private fun countFunctionNames(table: File?): Map<String, Int> {
        if (table == null || !table.isFile) return emptyMap()
        val counts = HashMap<String, Int>()
        table.forEachLine { line ->
            val record = parseRecord(line, "function") ?: return@forEachLine
            val name = record.get("name")?.asString ?: return@forEachLine
            counts[name] = (counts[name] ?: 0) + 1
        }
        return counts
    }

    private fun parseRecord(line: String, kind: String) = runCatching {
        val root = JsonParser.parseString(line).asJsonObject
        if (root.get("type")?.asString != kind) null else root.getAsJsonObject(kind)
    }.getOrNull()

    private fun hex(value: Long): String = "0x%x".format(value)

    private fun buildOffsets(summary: ScanResultSummary): Map<String, String> {
        val offsets = linkedMapOf<String, String>()
        summary.vtables.entries.values.sortedBy { it.vtableAddress }.forEach { vtable ->
            offsets["vtable:${vtable.name}"] = vtable.vtableHex
        }
        val firstHit = summary.patternHits.groupBy { it.name }
            .mapValues { (_, hits) -> hits.minByOrNull { it.address }!! }
        summary.expectedPatterns.forEach { name ->
            offsets["pattern:$name"] = firstHit[name]?.addressHex ?: OffsetRows.MISSING_ADDRESS
        }
        firstHit.keys.filterNot { it in summary.expectedPatterns }.sorted().forEach { name ->
            offsets["pattern:$name"] = firstHit.getValue(name).addressHex
        }
        summary.symbols.sortedBy { it.address }.forEach { symbol ->
            offsets["symbol:${symbol.name}"] = symbol.addressHex
        }
        return offsets
    }
}
