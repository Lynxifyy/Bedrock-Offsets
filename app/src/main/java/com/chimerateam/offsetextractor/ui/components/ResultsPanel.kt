package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.data.native.TypeVTable
import com.chimerateam.offsetextractor.data.native.VtableMap
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.util.formatBytes

/** Number of signature hits rendered in the panel before it truncates. */
private const val MAX_SHOWN_HITS = 12

/** Number of resolved vtables rendered in the panel before it truncates. */
private const val MAX_SHOWN_VTABLES = 8

/**
 * Summary of a completed scan: identity of the library and where `.text` lives.
 *
 * The address range and the load delta are what a downstream loader needs to
 * turn a file offset from a signature scan into a virtual address.
 *
 * @param result the completed scan summary.
 * @param modifier layout modifier for the panel.
 */
@Composable
fun ResultsPanel(result: ScanResultSummary, modifier: Modifier = Modifier) {
    SectionPanel(title = "Result", modifier = modifier) {
        Field("APK", result.displayName)
        Field("Architecture", result.architecture)
        Field("Size", formatBytes(result.sizeBytes))
        Field("SHA-256", result.sha256.take(16) + "...")
        Field("Entry point", result.entryPoint)
        Field("Sections", result.sectionCount.toString())

        if (result.patternHits.isNotEmpty() || result.discardedMatches > 0) {
            Spacer(Modifier.height(12.dp))
            Text(text = "Verified signatures", style = MonoText.headline)
            Spacer(Modifier.height(4.dp))
            Field("Matches", result.patternHits.size.toString())
            Field("Discarded", result.discardedMatches.toString())
            result.peakResidentBytes?.let { peak ->
                Field("Peak resident", formatBytes(peak))
            }
            result.patternHits.take(MAX_SHOWN_HITS).forEach { hit ->
                Field(hit.name, hit.addressHex)
            }
            if (result.patternHits.size > MAX_SHOWN_HITS) {
                Text(
                    text = "and ${result.patternHits.size - MAX_SHOWN_HITS} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!result.vtables.isEmpty) {
            Spacer(Modifier.height(12.dp))
            Text(text = "RTTI vtables", style = MonoText.headline)
            Spacer(Modifier.height(4.dp))
            Field("Resolved", result.vtables.size.toString())
            Field("Source", result.vtables.source)
            result.vtables.entries.values.take(MAX_SHOWN_VTABLES).forEach { vtable ->
                Field(vtable.name, vtable.vtableHex)
            }
            if (result.vtables.size > MAX_SHOWN_VTABLES) {
                Text(
                    text = "and ${result.vtables.size - MAX_SHOWN_VTABLES} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        result.textSection?.let { text ->
            Spacer(Modifier.height(12.dp))
            Text(text = "Executable section", style = MonoText.headline)
            Spacer(Modifier.height(4.dp))
            Field(".text start", text.hex(text.address))
            Field(".text end", text.hex(text.address + text.size))
            Field(".text size", formatBytes(text.size))
            Field("File offset", text.hex(text.offset))
            Field("Load delta", text.hex(text.delta))
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A file offset becomes a virtual address by adding the " +
                    "load delta for its section.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Renders one label/value pair with the value in the data font. */
@Composable
private fun Field(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MonoText.body)
    }
}

/** Formats [value] as a `0x`-prefixed hexadecimal string. */
private fun SectionInfo.hex(value: Long): String = "0x%x".format(value)

@Preview(showBackground = true)
@Composable
private fun ResultsPanelPreview() {
    BedrockOffsetExtractorTheme {
        ResultsPanel(
            result = ScanResultSummary(
                displayName = "minecraft-1.21.50.apk",
                sha256 = "0123456789abcdef0123456789abcdef",
                sizeBytes = 812L * 1024 * 1024,
                architecture = "AArch64",
                entryPoint = "0x1a2b3c",
                sectionCount = 33,
                patternHits = listOf(
                    PatternHit(
                        name = "frame_push_x29_x30",
                        sectionOffset = 0x120,
                        fileOffset = 0x3120,
                        address = 0x403120,
                        addressHex = "0x403120",
                        prologueKind = "frame_push",
                        prologueInstruction = "stp x29, x30, [sp, #-16]!"
                    )
                ),
                discardedMatches = 3,
                peakResidentBytes = 42L * 1024 * 1024,
                vtables = VtableMap(
                    source = VtableMap.SOURCE_RELOCATIONS,
                    typeInfoCandidates = 1,
                    relocationsExamined = 4,
                    entries = mapOf(
                        "ClientInstance" to TypeVTable(
                            name = "ClientInstance",
                            mangled = "14ClientInstance",
                            vtableAddress = 0x403000,
                            vtableHex = "0x403000",
                            vtableOffset = 0x3000,
                            typeInfoAddress = 0x403080
                        )
                    )
                ),
                textSection = SectionInfo(
                    name = ".text",
                    kind = "text",
                    address = 0x400000,
                    size = 0x7a0000,
                    offset = 0x3000,
                    align = 16,
                    delta = 0x3fd000,
                    allocated = true,
                    executable = true,
                    writable = false,
                    occupiesFile = true
                )
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
