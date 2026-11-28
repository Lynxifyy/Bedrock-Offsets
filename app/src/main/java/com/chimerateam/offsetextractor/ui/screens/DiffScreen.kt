package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.DiffEntry
import com.chimerateam.offsetextractor.data.export.DiffKind
import com.chimerateam.offsetextractor.data.export.OffsetDiff
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.Graphite700
import com.chimerateam.offsetextractor.ui.theme.MonoText

/**
 * Build-to-build comparison of two scans made in this session.
 *
 * A diff needs two summaries, and both are already produced by scanning one APK
 * and then the other; keeping them in the ViewModel's history avoids a second
 * file picker and any re-parsing of the internal table.
 *
 * @param history completed scans, newest first.
 * @param modifier layout modifier.
 */
@Composable
fun DiffScreen(history: List<ScanResultSummary>, modifier: Modifier = Modifier) {
    if (history.size < 2) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Scan two APKs in this session to compare their offsets.",
                style = MonoText.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    var afterIndex by rememberSaveable { mutableStateOf(0) }
    var beforeIndex by rememberSaveable { mutableStateOf(1) }
    val after = history.getOrNull(afterIndex) ?: history.first()
    val before = history.getOrNull(beforeIndex) ?: history.last()
    val entries = remember(before, after) { OffsetDiff.compare(before, after) }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Text(text = "BUILD DIFF", style = MonoText.headline)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BuildPicker(
                label = "Baseline",
                history = history,
                selected = beforeIndex,
                onSelect = { beforeIndex = it },
                modifier = Modifier.weight(1f)
            )
            BuildPicker(
                label = "Newer",
                history = history,
                selected = afterIndex,
                onSelect = { afterIndex = it },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiffKind.entries.forEach { kind ->
                Text(
                    text = "${kind.name}: ${OffsetDiff.count(entries, kind)}",
                    style = MonoText.label,
                    color = colorFor(kind)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Graphite700)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries) { entry -> DiffRow(entry) }
        }
    }
}

@Composable
private fun BuildPicker(
    label: String,
    history: List<ScanResultSummary>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val summary = history.getOrNull(selected) ?: history.first()
    Box(modifier = modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$label: ${summary.buildId.ifBlank { summary.displayName }}",
                style = MonoText.label,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            history.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${item.buildId.ifBlank { "unknown" }} · ${item.displayName}",
                            style = MonoText.label,
                            maxLines = 1
                        )
                    },
                    onClick = {
                        onSelect(index)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DiffRow(entry: DiffEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = entry.kind.name,
            style = MonoText.label,
            color = colorFor(entry.kind),
            modifier = Modifier.weight(0.9f)
        )
        Text(
            text = entry.name,
            style = MonoText.label,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(2.4f)
        )
        Text(
            text = entry.beforeAddress ?: "-",
            style = MonoText.label,
            modifier = Modifier.weight(1.1f)
        )
        Text(
            text = entry.afterAddress ?: "-",
            style = MonoText.label,
            modifier = Modifier.weight(1.1f)
        )
        Text(
            text = entry.detail,
            style = MonoText.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(2.0f)
        )
    }
}

@Composable
private fun colorFor(kind: DiffKind) = when (kind) {
    DiffKind.Matched -> MaterialTheme.colorScheme.onSurfaceVariant
    DiffKind.Shifted -> MaterialTheme.colorScheme.primary
    DiffKind.New -> MaterialTheme.colorScheme.secondary
    DiffKind.Removed -> MaterialTheme.colorScheme.error
    DiffKind.BrokenSignature -> MaterialTheme.colorScheme.error
}
