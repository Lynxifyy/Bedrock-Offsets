package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.ScriptFormat
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.Graphite700
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.util.formatBytes

/** Height of the compact IDE command bar. */
private val CommandBarHeight = 44.dp

/**
 * Compact desktop-style command bar.
 *
 * Replaces the mobile app bar: the menus hold the file, scan, diff and export
 * actions, the field is the global quick search, and the pill names the build
 * the workspace is showing.
 *
 * @param buildId build identifier of the active scan, or blank.
 * @param query current quick-search text.
 * @param onQueryChange invoked when the quick-search text changes.
 * @param resultReady whether a scan result is available.
 * @param onOpenApk opens the single-APK picker.
 * @param onOpenBatch opens the multi-APK picker.
 * @param onNewScan resets the scanner.
 * @param onRetry retries a failed scan.
 * @param onDiff switches to the diff destination.
 * @param onCopyJson copies the exported JSON.
 * @param onShareJson shares the exported JSON.
 * @param onSaveJson saves the exported JSON.
 * @param onDownloadZip builds and saves the archive.
 * @param onExportScript renders the scan as a reverse-engineering script.
 * @param onClearCache clears the cache.
 * @param onSettings opens settings.
 */
@Composable
fun IdeCommandBar(
    buildId: String,
    query: String,
    onQueryChange: (String) -> Unit,
    resultReady: Boolean,
    onOpenApk: () -> Unit,
    onOpenBatch: () -> Unit,
    onNewScan: () -> Unit,
    onRetry: () -> Unit,
    onDiff: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onDownloadZip: () -> Unit,
    onExportScript: (ScriptFormat) -> Unit,
    onClearCache: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CommandBarHeight)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "BEDROCK",
                    style = MonoText.label,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                MenuButton(
                    "File",
                    listOf(
                        "Open APK" to onOpenApk,
                        "Open several APKs" to onOpenBatch,
                        "Clear cache" to onClearCache,
                        "Settings" to onSettings
                    )
                )
                MenuButton(
                    "Scan",
                    listOf(
                        "Start new scan" to onNewScan,
                        "Retry failed scan" to onRetry
                    )
                )
                MenuButton("Diff", listOf("Compare builds" to onDiff))
                MenuButton(
                    "Export",
                    listOf(
                        "Copy offsets as JSON" to onCopyJson,
                        "Share offsets" to onShareJson,
                        "Save offsets to a file" to onSaveJson,
                        "Download ZIP" to onDownloadZip
                    ) + ScriptFormat.entries.map { format ->
                        "Export ${format.label}" to { onExportScript(format) }
                    },
                    enabled = resultReady
                )
                Spacer(Modifier.width(8.dp))
                QuickSearch(query = query, onQueryChange = onQueryChange)
                Spacer(Modifier.weight(1f))
                BuildPill(buildId)
            }
            HorizontalDivider(color = Graphite700)
        }
    }
}

@Composable
private fun MenuButton(
    label: String,
    items: List<Pair<String, () -> Unit>>,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text(label, style = MonoText.label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (text, action) ->
                DropdownMenuItem(
                    text = { Text(text, style = MonoText.label) },
                    onClick = {
                        open = false
                        action()
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickSearch(query: String, onQueryChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MonoText.label.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.width(220.dp)
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "Quick search (/)",
                    style = MonoText.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            inner()
        }
    }
}

@Composable
private fun BuildPill(buildId: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = buildId.ifBlank { "no build" },
            style = MonoText.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Bottom status bar of the workspace.
 *
 * Shows the facts a reverse engineer checks before trusting an offset: target
 * architecture, load base, peak resident size, worker count and how much of the
 * result was verified against the symbol table.
 *
 * @param result the completed scan, or `null` before one finishes.
 */
@Composable
fun IdeStatusBar(result: ScanResultSummary?, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusItem("Arch", result?.architecture ?: "-")
            StatusItem("Base", result?.imageBase?.let { "0x%x".format(it) } ?: "-")
            StatusItem(
                "RSS",
                result?.peakResidentBytes?.let(::formatBytes) ?: "-"
            )
            StatusItem("Workers", Runtime.getRuntime().availableProcessors().toString())
            StatusItem(
                "Coverage",
                result?.verification?.let { "${it.confidencePercent}%" } ?: "-"
            )
            Spacer(Modifier.weight(1f))
            StatusItem("Engine", result?.engineVersion?.ifBlank { "-" } ?: "-")
        }
    }
}

@Composable
private fun StatusItem(label: String, value: String) {
    Text(
        text = "$label $value",
        style = MonoText.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}
