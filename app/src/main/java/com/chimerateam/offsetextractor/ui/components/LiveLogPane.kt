package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.ui.theme.PanelCorner

/**
 * Scrolling view of engine and UI messages.
 *
 * @param messages log lines in chronological order.
 * @param modifier layout modifier for the pane.
 */
@Composable
fun LiveLogPane(
    messages: List<String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(PanelCorner),
            )
            .padding(12.dp),
    ) {
        if (messages.isEmpty()) {
            Text(
                text = "Live log will appear here.",
                style = MonoText.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(messages) { line ->
                    Text(
                        text = line,
                        style = MonoText.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LiveLogPanePreview() {
    BedrockOffsetExtractorTheme {
        LiveLogPane(
            messages = listOf(
                "[SAF] Opened APK File Descriptor successfully.",
                "[RUST] Mapped 800MB .so file with zero-copy. Arch: AArch64.",
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
