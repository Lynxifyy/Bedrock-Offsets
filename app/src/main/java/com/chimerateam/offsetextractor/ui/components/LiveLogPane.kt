package com.chimerateam.offsetextractor.ui.components

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.log.LogLevel
import com.chimerateam.offsetextractor.ui.log.LogPalette
import com.chimerateam.offsetextractor.ui.log.Logs
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.ui.theme.PanelCorner

/**
 * Scrolling view of engine and UI messages.
 *
 * The list follows new entries while the newest entry is visible. Once the user
 * scrolls up to read an earlier line the pane stops following, and it resumes
 * when they scroll back to the bottom, so reading history is not fought by a
 * stream of progress lines.
 *
 * @param entries log entries in chronological order.
 * @param onCopyLog copies the whole log to the clipboard.
 * @param onClearLog discards the log.
 * @param modifier layout modifier for the pane.
 */
@Composable
fun LiveLogPane(
    entries: List<LogEntry>,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Following is derived from the scroll position rather than stored, so a
    // rotation or a recomposition cannot desynchronise the two.
    val following by remember {
        derivedStateOf {
            val last = listState.layoutInfo.totalItemsCount - 1
            last <= 0 || listState.firstVisibleItemIndex >= last
        }
    }

    LaunchedEffect(listState, entries.size) {
        if (entries.isNotEmpty() && following) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(PanelCorner)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live log",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopyLog) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy log",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClearLog) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "Clear log",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            if (entries.isEmpty()) {
                Text(
                    text = "Live log will appear here.",
                    style = MonoText.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        entries,
                        key = { it.timestampMillis.toString() + it.message.hashCode() }
                    ) { entry ->
                        LogLine(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    val tagColour = LogPalette.colorFor(entry)
    val bodyColour = when (entry.level) {
        LogLevel.Error -> MaterialTheme.colorScheme.error
        LogLevel.Warning -> LogPalette.bodyColorFor(entry)
        LogLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val line = remember(entry, tagColour, bodyColour) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = tagColour, fontWeight = FontWeight.Medium)) {
                append("[${entry.tag}]")
            }
            append(" ")
            withStyle(SpanStyle(color = bodyColour)) {
                append(entry.message)
            }
        }
    }
    Text(
        text = line,
        style = MonoText.body,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Preview(showBackground = true)
@Composable
private fun LiveLogPanePreview() {
    BedrockOffsetExtractorTheme {
        LiveLogPane(
            entries = listOf(
                Logs.info("SAF", "Opened APK File Descriptor successfully.", 0),
                Logs.info("RUST", "Mapped 800MB .so file with zero-copy. Arch: AArch64.", 1),
                Logs.error("UI", "Export failed: storage is full.", 2)
            ),
            onCopyLog = {},
            onClearLog = {},
            modifier = Modifier.fillMaxWidth()
        )
    }
}
