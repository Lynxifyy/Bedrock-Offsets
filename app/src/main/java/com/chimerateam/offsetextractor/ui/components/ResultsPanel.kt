package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.InternalTable
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.util.formatBytes

/**
 * Summary of a completed scan: identity of the library and where `.text` lives.
 *
 * The address range and the load delta are what a downstream loader needs to
 * turn a file offset from a signature scan into a virtual address. The per-hit
 * and per-vtable lists live in the results list instead, so this panel stays a
 * fixed-size summary rather than duplicating the rows below it.
 *
 * @param result the completed scan summary.
 * @param modifier layout modifier for the panel.
 * @param internalAvailable whether the internal table behind [result] is still
 *   on disk; when it is not, the panel says so instead of implying the data is
 *   ready to download.
 */
@Composable
fun ResultsPanel(
    result: ScanResultSummary,
    modifier: Modifier = Modifier,
    internalAvailable: Boolean = true
) {
    SectionPanel(title = "Result", modifier = modifier) {
        Field("APK", result.displayName)
        Field("Architecture", result.architecture)
        Field("Library size", formatBytes(result.sizeBytes))
        if (result.apkSizeBytes > 0) {
            Field("APK size", formatBytes(result.apkSizeBytes))
        }
        Field("SHA-256", result.sha256.take(16) + "...")
        Field("Entry point", entryPointLabel(result.entryPoint))
        Field("Sections", result.sectionCount.toString())

        if (result.patternHits.isNotEmpty() || result.discardedMatches > 0) {
            Spacer(Modifier.height(8.dp))
            Text(text = "Verified signatures", style = MonoText.headline)
            Spacer(Modifier.height(4.dp))
            Field("Matches", result.patternHits.size.toString())
            Field("Discarded", result.discardedMatches.toString())
            result.peakResidentBytes?.let { peak ->
                Field("Peak resident", formatBytes(peak))
            }
        }

        if (!result.vtables.isEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(text = "RTTI vtables", style = MonoText.headline)
            Spacer(Modifier.height(4.dp))
            Field("Resolved", result.vtables.size.toString())
            Field("Source", result.vtables.source)
        }

        result.internal?.let { internal ->
            Spacer(Modifier.height(8.dp))
            Text(text = "Internal C++", style = MonoText.headline)
            Spacer(Modifier.height(4.dp))
            Field("Functions", internal.functions.toString())
            Field("Classes", internal.classes.toString())
            Field("Virtual slots", internal.vtableSlots.toString())
            Field("Named", internal.named.toString())
            Field("Verified", internal.verified.toString())
            Field("Ambiguous", internal.ambiguous.toString())
            Field("Signatures", internal.signatures.toString())
            Field("Unique signatures", internal.signaturesUnique.toString())
            Field("Data symbols", internal.dataSymbols.toString())
            if (internal.tableBytes > 0) {
                Field("Table", formatBytes(internal.tableBytes))
            }
            if (!internalAvailable && InternalTable.isPromised(result)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "The internal data was cleared. Extract it again before " +
                        "downloading, or the archive will not contain it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (internal.truncatedSignatures) {
                Text(
                    text = "Signature generation was capped; the first " +
                        "${internal.signatures} named functions were signed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        result.internalError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Internal extraction failed: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        result.textSection?.let { text ->
            Spacer(Modifier.height(8.dp))
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

/**
 * Renders one label/value pair.
 *
 * The label is weighted and limited to one line so a long symbol name cannot
 * push the value off the row; the value never soft-wraps, and a long press
 * copies it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Field(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = { clipboard.setText(AnnotatedString(value)) }
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = middleEllipsis(value, 40),
            style = MonoText.body,
            softWrap = false,
            maxLines = 1
        )
    }
}

/**
 * Shortens [value] in the middle so both ends stay visible.
 *
 * Compose's `TextOverflow` only clips at the end on the versions this app
 * supports, so a symbol such as
 * `Java_com_microsoft_xbox_idp_util_HttpCall_getResponseAsync__Lcom_..._2`
 * loses its distinguishing suffix without this helper.
 *
 * @param value text to shorten.
 * @param max maximum characters kept.
 * @return the original text, or a middle-elided form.
 */
internal fun middleEllipsis(value: String, max: Int = 48): String {
    if (value.length <= max || max < 5) return value
    val head = max / 2
    val tail = max - head - 1
    return value.take(head) + "\u2026" + value.takeLast(tail)
}

/**
 * A shared object normally has `e_entry == 0`; showing `0x0` reads like a real
 * address. The raw value is still written to JSON by the exporter.
 */
private fun entryPointLabel(value: String): String =
    if (value == "0x0" || value.isBlank()) "none (shared object)" else value

/** Formats [value] as a `0x`-prefixed hexadecimal string. */
private fun SectionInfo.hex(value: Long): String = "0x%x".format(value)
