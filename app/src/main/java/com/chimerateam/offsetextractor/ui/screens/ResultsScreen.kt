package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
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
import com.chimerateam.offsetextractor.data.native.VerificationStatus
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.ui.components.HoneycombCell
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
 * @param fillHeight whether the screen should fill the height it is given and
 *   let its list scroll, rather than sizing to its content.
 * @param onCopyJson copies the exported JSON to the clipboard.
 * @param onShareJson shares the exported JSON.
 * @param onSaveJson writes the exported JSON to a user-chosen location.
 * @param onStartNewScan resets the scanner.
 * @param modifier layout modifier for the screen.
 */
@Composable
fun ResultsScreen(
    result: ScanResultSummary,
    fillHeight: Boolean = false,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onStartNewScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(result) { OffsetRows.from(result) }
    val visible = remember(rows, query) { rows.filter { it.matches(query) } }
    val conflicts = remember(rows) { rows.count { it.status.isConflict } }

    Column(
        modifier = if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth()
    ) {
        Text(text = "Offsets", style = MonoText.headline)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${visible.size} of ${rows.size} entries" +
                if (query.isBlank()) "" else " matching \"$query\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        ConfidenceBanner(verification = result.verification, conflicts = conflicts)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag(SEARCH_FIELD_TAG),
            singleLine = true,
            label = { Text("Search offsets") }
        )
        Spacer(Modifier.height(12.dp))

        if (visible.isEmpty()) {
            EmptyResults(
                message = if (rows.isEmpty()) {
                    "This scan produced no offsets."
                } else {
                    "No offset matches \"$query\"."
                }
            )
        } else {
            // With a bounded parent the list takes the leftover height and
            // scrolls there; otherwise it sizes to its rows.
            val listModifier =
                if (fillHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()
            LazyColumn(modifier = listModifier) {
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
            overflow = TextOverflow.Ellipsis
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
    }
