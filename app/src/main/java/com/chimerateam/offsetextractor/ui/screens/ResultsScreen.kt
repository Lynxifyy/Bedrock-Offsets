package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.OffsetCategory
import com.chimerateam.offsetextractor.data.export.OffsetRow
import com.chimerateam.offsetextractor.data.export.OffsetRows
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.MonoText

/** Test tag of the search field, so a UI test can drive it directly. */
internal const val SEARCH_FIELD_TAG = "results-search"

/**
 * Scrollable list of every offset the scan produced.
 *
 * Rows are grouped by category and can be filtered by a search box. A target
 * that was looked for but not found is rendered in the error colour, because
 * that is what an out-of-date signature pack looks like.
 *
 * @param result the completed scan summary.
 * @param onCopyJson copies the exported JSON to the clipboard.
 * @param onShareJson shares the exported JSON.
 * @param onSaveJson writes the exported JSON to a user-chosen location.
 * @param onStartNewScan resets the scanner.
 * @param modifier layout modifier for the screen.
 */
@Composable
fun ResultsScreen(
    result: ScanResultSummary,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onStartNewScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(result) { OffsetRows.from(result) }
    val visible = remember(rows, query) { rows.filter { it.matches(query) } }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "Offsets", style = MonoText.headline)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${visible.size} of ${rows.size} entries" +
                if (query.isBlank()) "" else " matching \"$query\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag(SEARCH_FIELD_TAG),
            singleLine = true,
            label = { Text("Search offsets") },
        )
        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            Text(
                text = if (rows.isEmpty()) {
                    "This scan produced no offsets."
                } else {
                    "No offset matches \"$query\"."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(visible, key = { "${it.category}:${it.name}:${it.addressHex}" }) { row ->
                    OffsetRowItem(row)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCopyJson) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text("Copy JSON", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onShareJson) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text("Share JSON", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onSaveJson) {
                Text("Save JSON")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onStartNewScan) {
            Text("Start new scan")
        }
    }
}

@Composable
private fun OffsetRowItem(row: OffsetRow) {
    val addressColour = if (row.missing) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = row.name,
            style = MonoText.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = row.addressHex, style = MonoText.body, color = addressColour)
            Text(
                text = "  ${row.category.label} · ${row.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Human-readable label of a category. */
private val OffsetCategory.label: String
    get() = when (this) {
        OffsetCategory.VTable -> "vtable"
        OffsetCategory.Pattern -> "pattern"
        OffsetCategory.Symbol -> "symbol"
    }
