package com.chimerateam.offsetextractor.data.export

import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.google.gson.GsonBuilder

/**
 * Serialises a scan summary into the JSON a loader consumes.
 *
 * The document carries the identity of the library it came from, so a loader
 * can refuse offsets that belong to a different build, and a flat `offsets`
 * map of name to address, which is what a loader actually reads.
 */
object OffsetExporter {

    /** Schema version of the document; bumped when the shape changes. */
    const val SCHEMA_VERSION = 1

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    /**
     * Renders [summary] as pretty-printed JSON.
     *
     * @param summary the completed scan.
     * @return a JSON document ending in a newline.
     */
    fun toJson(summary: ScanResultSummary): String {
        val document = OffsetDocument(
            schemaVersion = SCHEMA_VERSION,
            generatedBy = "BedrockOffsetExtractor",
            source = SourceInfo(
                apk = summary.displayName,
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
        return gson.toJson(document) + "\n"
    }

    /** Default file name for [summary], keyed by build identifier. */
    fun fileName(summary: ScanResultSummary): String {
        val key = summary.buildId.ifBlank { summary.sha256.take(16) }
        val safe = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return "offsets_${safe.ifBlank { "unknown" }}.json"
    }

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
