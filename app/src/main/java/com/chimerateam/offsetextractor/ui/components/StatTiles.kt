package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.LocalSemanticColors
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.ui.theme.PanelCorner

/** Test tag of the tile grid. */
const val STAT_TILES_TAG = "stat-tiles"

/** Test tag of the completeness badge. */
const val COMPLETENESS_BADGE_TAG = "completeness-badge"

/**
 * The numbers a developer reads first, as tiles rather than a paragraph.
 *
 * Six tiles in two rows of three: functions, classes, slots, JNI offsets,
 * signatures and confidence. A value that the scan did not produce shows a dash
 * rather than a zero, so "no data" and "nothing found" stay distinguishable.
 *
 * @param result the completed scan summary.
 * @param modifier layout modifier for the grid.
 */
@Composable
fun StatTiles(result: ScanResultSummary, modifier: Modifier = Modifier) {
    val internal = result.internal
    val jniCount = result.jni?.offsets?.size ?: result.jniSymbolCount
    val tiles = listOf(
        "Functions" to internal?.functions?.let(::formatCount),
        "Classes" to internal?.classes?.let(::formatCount),
        "Slots" to internal?.vtableSlots?.let(::formatCount),
        "JNI offsets" to if (jniCount > 0) formatCount(jniCount) else null,
        "Signatures" to internal?.signatures?.let(::formatCount),
        "Confidence" to result.verification.takeIf { it.total > 0 }
            ?.let { "${it.confidencePercent}%" }
    )

    Column(
        modifier = modifier.fillMaxWidth().testTag(STAT_TILES_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tiles.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (label, value) ->
                    StatTile(label = label, value = value, modifier = Modifier.weight(1f))
                }
            }
        }
        CompletenessBadge(result = result)
    }
}

/**
 * One number with its label.
 *
 * @param label what the number counts.
 * @param value the formatted number, or `null` when the scan produced none.
 * @param modifier layout modifier, normally a weight.
 */
@Composable
private fun StatTile(label: String, value: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PanelCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = value ?: "–",
                style = MonoText.headline,
                color = if (value == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The completeness check from the manifest, as one badge.
 *
 * Green when the exported function count matches the unwind table, amber when a
 * pass was capped, and grey when there is nothing to compare.
 *
 * @param result the completed scan summary.
 */
@Composable
private fun CompletenessBadge(result: ScanResultSummary) {
    val coverage = result.internal?.coverage
    val semantics = LocalSemanticColors.current
    val (text, colour) = when {
        coverage == null -> "No internal table to check" to
            MaterialTheme.colorScheme.onSurfaceVariant
        coverage.truncated || !coverage.functionsMatchFdes ->
            "Partial: ${coverage.summaryLine}" to semantics.warning
        else -> "Complete: ${coverage.summaryLine}" to semantics.success
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(COMPLETENESS_BADGE_TAG)
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(PanelCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = colour
        )
    }
}

/** Thousands separators, so a six-digit count stays readable. */
private fun formatCount(value: Int): String = "%,d".format(value)
