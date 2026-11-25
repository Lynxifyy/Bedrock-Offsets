package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import java.io.File
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

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

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
    fun toJson(summary: ScanResultSummary): String = gson.toJson(jniDocument(summary)) + "\n"

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
        streamingGson.toJson(jniDocument(summary), writer)
        writer.write("\n")
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

    private fun jniDocument(summary: ScanResultSummary): OffsetDocument = OffsetDocument(
        schemaVersion = SCHEMA_VERSION,
        generatedBy = "BedrockOffsetExtractor",
        source = SourceInfo(
            apk = summary.displayName,
            apkVersion = versionLabel(summary),
            apkSizeBytes = summary.apkSizeBytes,
            architecture = summary.architecture,
            librarySha256 = summary.sha256,
            librarySizeBytes = summary.sizeBytes,
            buildId = summary.buildId,
            buildIdSource = summary.buildIdSource,
            entryPoint = summary.entryPoint,
            textAddress = summary.textSection?.address,
            textSize = summary.textSection?.size,
            textDelta = summary.textSection?.delta
        ),
        offsets = buildOffsets(summary),
        verification = summary.verification,
        vtables = summary.vtables.entries.values
            .sortedBy { it.vtableAddress }
            .associate { vtable ->
                vtable.name to VTableEntry(
                    address = vtable.vtableHex,
                    offset = OffsetRows.hex(vtable.vtableOffset),
                    typeInfo = OffsetRows.hex(vtable.typeInfoAddress)
                )
            },
        symbols = summary.symbols
            .sortedBy { it.address }
            .associate { symbol -> symbol.name to symbol.addressHex },
        stats = Stats(
            expectedSignatures = summary.expectedPatterns.size,
            matchedSignatures = summary.patternHits.map { it.name }.distinct().size,
            discardedMatches = summary.discardedMatches,
            vtables = summary.vtables.size,
            symbols = summary.symbols.size,
            verifiedOffsets = summary.verification.verified,
            conflictingOffsets = summary.verification.conflicts,
            unverifiedOffsets = summary.verification.unverified,
            confidencePercent = summary.verification.confidencePercent
        )
    )

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

/** Top-level document written to storage. */
private data class OffsetDocument(
    val schemaVersion: Int,
    val generatedBy: String,
    val source: SourceInfo,
    val offsets: Map<String, String>,
    val verification: VerificationSummary,
    val vtables: Map<String, VTableEntry>,
    val symbols: Map<String, String>,
    val stats: Stats
)

/** Identity of the library the offsets were read from. */
private data class SourceInfo(
    val apk: String,
    val apkVersion: String,
    val apkSizeBytes: Long,
    val architecture: String,
    val librarySha256: String,
    val librarySizeBytes: Long,
    val buildId: String,
    val buildIdSource: String,
    val entryPoint: String,
    val textAddress: Long?,
    val textSize: Long?,
    val textDelta: Long?
)

/** One resolved vtable, in both absolute and rebase-stable form. */
private data class VTableEntry(
    val address: String,
    val offset: String,
    val typeInfo: String
)

/** Counts that let a reader judge how complete and how trustworthy the scan was. */
private data class Stats(
    val expectedSignatures: Int,
    val matchedSignatures: Int,
    val discardedMatches: Int,
    val vtables: Int,
    val symbols: Int,
    val verifiedOffsets: Int,
    val conflictingOffsets: Int,
    val unverifiedOffsets: Int,
    val confidencePercent: Int
)
