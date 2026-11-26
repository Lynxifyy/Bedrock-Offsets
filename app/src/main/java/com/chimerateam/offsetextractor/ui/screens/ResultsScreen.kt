package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.InternalIndex
import com.chimerateam.offsetextractor.data.export.InternalTable
import com.chimerateam.offsetextractor.data.export.OffsetCategory
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.export.OffsetRow
import com.chimerateam.offsetextractor.data.export.OffsetRows
import com.chimerateam.offsetextractor.data.native.VerificationStatus
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.ui.components.HoneycombCell
import com.chimerateam.offsetextractor.ui.components.StatTiles
import com.chimerateam.offsetextractor.ui.components.middleEllipsis
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.MonoText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Test tag of the search field, so a UI test can drive it directly. */
internal const val SEARCH_FIELD_TAG = "results-search"

/** Test tag of the scrolling results list. */
internal const val RESULTS_LIST_TAG = "results-list"

/** Test tag of the single ZIP download button. */
internal const val DOWNLOAD_ZIP_TAG = "download-zip"

/** Tag of the text that explains why the internal pass failed. */
internal const val INTERNAL_ERROR_TAG = "internal-error-reason"

/** Test tag of the overflow menu holding the JNI actions. */
internal const val JNI_MENU_TAG = "jni-menu"

/** Test tag of the start-new-scan button. */
internal const val START_NEW_SCAN_TAG = "start-new-scan"

/**
 * Scrollable list of every offset the scan produced.
 *
 * The whole success pane is one [LazyColumn]: [header] is its first item and the
 * offset rows follow, so the summary can never be measured at the full height
 * and squeeze the list to zero. The action bar, including both download
 * buttons, lives below the list and is therefore pinned.
 *
 * Rows are grouped by category and filtered by a search box. The internal C++
 * table is file-backed, so a search streams the NDJSON table and stops after
 * [com.chimerateam.offsetextractor.data.export.INTERNAL_SEARCH_LIMIT] rows.
 *
 * @param result the completed scan summary.
 * @param onCopyJson copies the exported JNI JSON to the clipboard.
 * @param onShareJson shares the exported JNI JSON.
 * @param onSaveJson writes the exported JNI JSON to a user-chosen location.
 * @param onStartNewScan resets the scanner.
 * @param modifier layout modifier for the screen.
 * @param fillHeight whether the screen should fill the height it is given and
 *   let its list scroll, rather than sizing to its content.
 * @param header content shown above the results, normally the summary panel.
 * @param internalIndex index over the internal table; when omitted one is built
 *   from [ScanResultSummary.internal].
 * @param onDownloadZip opens the save dialog for the single ZIP.
 * @param onRetryInternal re-runs only the internal pass.
 * @param internalBusy whether Part 2 is still running.
 * @param internalRetryable whether a retry can succeed. A deterministic failure
 *   is reported with its reason instead of offering a button that cannot work.
 * @param onCopyName copies a long name from a row.
 */
@Composable
fun ResultsScreen(
    result: ScanResultSummary,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onStartNewScan: () -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    header: @Composable () -> Unit = {},
    internalIndex: InternalIndex? = null,
    onDownloadZip: (() -> Unit)? = null,
    onRetryInternal: (() -> Unit)? = null,
    internalBusy: Boolean = false,
    internalAvailable: Boolean = true,
    internalRetryable: Boolean = true,
    onCopyName: (String) -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(result) { OffsetRows.from(result) }
    val fallbackIndex = remember(result.internal) {
        InternalIndex(result.internal)
    }
    val index = internalIndex ?: fallbackIndex
    var internalRows by remember { mutableStateOf<List<OffsetRow>>(emptyList()) }

    LaunchedEffect(query, index) {
        if (query.isBlank() || !index.isAvailable()) {
            internalRows = emptyList()
            return@LaunchedEffect
        }
        // A short debounce keeps typing responsive while still searching a
        // multi-megabyte table without blocking the main thread.
        delay(200)
        internalRows = withContext(Dispatchers.IO) { index.search(query) }
    }

    val visible = remember(rows, internalRows, query) {
        rows.filter { it.matches(query) } + internalRows
    }
    val total = rows.size + internalRows.size
    val conflicts = remember(rows) { rows.count { it.status.isConflict } }

    Column(
        modifier = if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth()
    ) {
        // Weighted so the pinned controls below always get their space; the
        // pane is bounded by the scanner layout in both modes.
        val listModifier = Modifier.fillMaxWidth().weight(1f)
        LazyColumn(modifier = listModifier.testTag(RESULTS_LIST_TAG)) {
            item(key = "summary") {
                header()
                Spacer(Modifier.height(12.dp))
                StatTiles(result = result)
                Spacer(Modifier.height(12.dp))
                ConfidenceBanner(verification = result.verification, conflicts = conflicts)
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyResults(
                        message = if (total == 0) {
                            "This scan produced no offsets."
                        } else {
                            "No offset matches \"$query\"."
                        }
                    )
                }
            }
            items(
                visible,
                key = { "${it.category}:${it.name}:${it.addressHex}" }
            ) { row ->
                OffsetRowItem(row = row, onCopyName = onCopyName)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // The title, counts, search box and export buttons are pinned below the
        // list, so a tall summary or a long result set can never push them off
        // the screen.
        Text(text = "Offsets", style = MonoText.headline)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${visible.size} of $total entries" +
                if (query.isBlank()) "" else " matching \"$query\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (internalRows.size >=
            com.chimerateam.offsetextractor.data.export.INTERNAL_SEARCH_LIMIT
        ) {
            Text(
                text = "Showing the first ${internalRows.size} matches; " +
                    "refine your search for more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag(SEARCH_FIELD_TAG),
            singleLine = true,
            label = { Text("Search offsets") }
        )

        ExportBar(
            result = result,
            onCopyJson = onCopyJson,
            onShareJson = onShareJson,
            onSaveJson = onSaveJson,
            onStartNewScan = onStartNewScan,
            onDownloadZip = onDownloadZip,
            onRetryInternal = onRetryInternal,
            internalBusy = internalBusy,
            internalAvailable = internalAvailable,
            internalRetryable = internalRetryable
        )
    }
}

/**
 * Pinned action bar with the single ZIP download.
 *
 * The button is full-width so its label stays readable and can never be pushed
 * off-screen, including at 1920x1200 in landscape.
 */
@Composable
private fun ExportBar(
    result: ScanResultSummary,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onStartNewScan: () -> Unit,
    onDownloadZip: (() -> Unit)?,
    onRetryInternal: (() -> Unit)?,
    internalBusy: Boolean,
    internalAvailable: Boolean,
    internalRetryable: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when {
            internalBusy -> Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().testTag(DOWNLOAD_ZIP_TAG)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Building internal table...",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            !result.internalResolved && result.internalError != null -> Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // The reason, not only the fact. It used to live in the log
                // pane only, so the card showed a red button with no cause.
                Text(
                    text = result.internalError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().testTag(INTERNAL_ERROR_TAG)
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onRetryInternal?.invoke() },
                    enabled = onRetryInternal != null && internalRetryable,
                    modifier = Modifier.fillMaxWidth().testTag(DOWNLOAD_ZIP_TAG)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text(
                        text = if (internalRetryable) {
                            "Internal extraction failed. Retry"
                        } else {
                            "Internal extraction failed. A retry cannot fix this"
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            // The table is the archive's reason to exist, and it can be
            // cleared after the scan; offering a download that would produce an
            // archive without it is worse than asking for a re-extraction.
            // Only a scan that claimed internal rows can lose them, so a
            // JNI-only result keeps its download button.
            !internalAvailable && InternalTable.isPromised(result) -> Button(
                onClick = { onRetryInternal?.invoke() },
                enabled = onRetryInternal != null,
                modifier = Modifier.fillMaxWidth().testTag(DOWNLOAD_ZIP_TAG)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    text = "Internal data was cleared. Extract it again to download",
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }

            else -> Button(
                onClick = { onDownloadZip?.invoke() },
                enabled = result.internalResolved && onDownloadZip != null,
                modifier = Modifier.fillMaxWidth().testTag(DOWNLOAD_ZIP_TAG)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text(
                    text = "Download ${OffsetExporter.zipFileName(result)}",
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // One row instead of three full-width buttons: the bar has to leave the
        // result card enough height to show its own rows.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onStartNewScan,
                modifier = Modifier.weight(1f).testTag(START_NEW_SCAN_TAG)
            ) {
                Text("Start new scan")
            }
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag(JNI_MENU_TAG)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More JNI actions"
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy JNI JSON") },
                        onClick = {
                            menuOpen = false
                            onCopyJson()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share JNI JSON") },
                        onClick = {
                            menuOpen = false
                            onShareJson()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Save JNI JSON") },
                        onClick = {
                            menuOpen = false
                            onSaveJson()
                        }
                    )
                }
            }
        }
    }
}

/**
 * One-line summary of how well the offsets agree with the library's symbols.
 *
 * A library that exports no symbols has nothing to compare against, so the
 * banner says so instead of showing a confidence of zero, which would read as
 * "everything is wrong".
 */
@Composable
private fun ConfidenceBanner(verification: VerificationSummary, conflicts: Int) {
    val hasConflicts = conflicts > 0
    val text = when {
        !verification.symbolsAvailable ->
            "Confidence: unavailable (the library exports no symbols)"

        hasConflicts ->
            "Confidence: ${verification.confidencePercent}% · " +
                "$conflicts offset(s) conflict with the symbol table"

        else ->
            "Confidence: ${verification.confidencePercent}% · " +
                "${verification.verified} verified, ${verification.unverified} unverified"
    }
    Text(
        text = text,
        style = MonoText.body,
        color = if (hasConflicts) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/**
 * One result row. The name is middle-elided and copyable on long press so a
 * mangled JNI name stays distinguishable and usable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OffsetRowItem(row: OffsetRow, onCopyName: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val addressColour = if (row.missing) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(row.name))
                    onCopyName(row.name)
                }
            )
    ) {
        Text(
            text = middleEllipsis(row.name, 56),
            style = MonoText.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = row.addressHex, style = MonoText.body, color = addressColour)
            if (row.status.isTrusted || row.status.isConflict) {
                VerificationBadge(row.status)
            }
            Text(
                text = "  ${row.category.label} · ${row.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Illustration shown when the results list has nothing to render.
 *
 * A dimmed honeycomb stands in for an empty list, which reads as "there is
 * nothing here" rather than as a rendering failure.
 */
@Composable
private fun EmptyResults(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HoneycombCell(
            size = 48.dp,
            color = MaterialTheme.colorScheme.outline,
            fillAlpha = 0.08f
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Icon marking an offset as verified against the symbol table or in conflict
 * with it. An unverified offset gets no badge, which keeps a stripped library's
 * list uncluttered.
 */
@Composable
private fun VerificationBadge(status: VerificationStatus) {
    val (icon, description, tint) = if (status.isTrusted) {
        Triple(
            Icons.Filled.VerifiedUser,
            "Verified against the symbol table",
            MaterialTheme.colorScheme.primary
        )
    } else {
        Triple(
            Icons.Filled.Warning,
            "Conflicts with the symbol table",
            MaterialTheme.colorScheme.error
        )
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

/** Human-readable label of a category. */
private val OffsetCategory.label: String
    get() = when (this) {
        OffsetCategory.VTable -> "vtable"
        OffsetCategory.Pattern -> "pattern"
        OffsetCategory.Symbol -> "symbol"
        OffsetCategory.InternalCpp -> "internal"
        OffsetCategory.VirtualSlot -> "virtual slot"
    }
