package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.ExportProgress
import com.chimerateam.offsetextractor.data.export.ExportStage
import com.chimerateam.offsetextractor.data.export.ExportState
import com.chimerateam.offsetextractor.util.formatBytes
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Test tag of the progress pill. */
const val EXPORT_TOAST_TAG = "export-toast"

/** Test tag of the pill's cancel action. */
const val EXPORT_CANCEL_TAG = "export-cancel"

/** Test tag of the pill's retry action. */
const val EXPORT_RETRY_TAG = "export-retry"

/** Test tag of the pill's share action. */
const val EXPORT_SHARE_TAG = "export-share"

/** Test tag of the interrupted-job card. */
const val EXPORT_INTERRUPTED_TAG = "export-interrupted"

/** Test tag of the interrupted card's discard action. */
const val EXPORT_DISCARD_TAG = "export-discard"

/**
 * Non-blocking progress pill shown above the pinned bar.
 *
 * A determinate ring with a visible track carries the percentage, and the file
 * name plus the stage text carry the context. On success it turns into a check
 * mark for four seconds with a Share action; on failure it turns red and offers
 * Retry.
 *
 * @param progress current export snapshot.
 * @param onCancel cancels the running job.
 * @param onRetry retries a failed or interrupted job.
 * @param onShare shares the cached ZIP by path.
 * @param onDismiss clears a finished snapshot.
 * @param modifier layout modifier.
 */
@Composable
fun ExportProgressToast(
    progress: ExportProgress,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (progress.state == ExportState.Idle || progress.state == ExportState.Cancelled) {
        return
    }
    if (progress.state == ExportState.Done) {
        LaunchedEffect(progress.jobId) {
            delay(SUCCESS_VISIBLE_MILLIS)
            onDismiss()
        }
    }

    val isDone = progress.state == ExportState.Done
    val isError = progress.state == ExportState.Failed ||
        progress.state == ExportState.Interrupted
    val fraction = if (isDone) 1f else progress.fraction.coerceIn(0f, 1f)
    val ringColor = when {
        isError -> MaterialTheme.colorScheme.error
        isDone -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    // The counts are the only way a user can tell that the archive really
    // carries the internal offsets; a bare "Saved" hid a ZIP with none.
    val contents = if (progress.files > 0) {
        " (${progress.files} files, ${progress.internalRows} internal rows)"
    } else {
        ""
    }
    val stageText = when {
        isDone -> "Saved ${progress.fileName}$contents"
        progress.stage == ExportStage.Building -> "Building ZIP…"
        else -> "Saving…"
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(EXPORT_TOAST_TAG)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    color = ringColor
                )
                if (isDone) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Download complete",
                        tint = ringColor
                    )
                } else {
                    Text(
                        text = "${(fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = progress.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (progress.bytesTotal > 0) {
                        "$stageText  ${formatBytes(progress.bytesDone)} / " +
                            formatBytes(progress.bytesTotal)
                    } else {
                        stageText
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (progress.error != null && isError) {
                    Text(
                        text = progress.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when {
                isDone -> TextButton(
                    onClick = { progress.zipPath?.let(onShare) },
                    enabled = progress.zipPath != null,
                    modifier = Modifier.testTag(EXPORT_SHARE_TAG)
                ) {
                    Text("Share")
                }

                isError -> TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(EXPORT_RETRY_TAG)
                ) {
                    Text("Retry")
                }

                else -> TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag(EXPORT_CANCEL_TAG)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Card offering Retry or Discard for a job that outlived the app process.
 *
 * @param progress the restored job.
 * @param onRetry re-runs Stage 2 when the cached ZIP is intact.
 * @param onDiscard deletes the cached ZIP and forgets the job.
 * @param modifier layout modifier.
 */
@Composable
fun ExportInterruptedCard(
    progress: ExportProgress,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth().testTag(EXPORT_INTERRUPTED_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Download was interrupted",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "${progress.fileName} can be resumed without rescanning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            progress.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, modifier = Modifier.testTag(EXPORT_RETRY_TAG)) {
                    Text("Retry")
                }
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.testTag(EXPORT_DISCARD_TAG)
                ) {
                    Text("Discard")
                }
            }
        }
    }
}

/** How long the success pill stays on screen. */
private const val SUCCESS_VISIBLE_MILLIS = 4_000L
