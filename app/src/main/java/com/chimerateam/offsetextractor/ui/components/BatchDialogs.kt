package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.state.BatchProgress
import com.chimerateam.offsetextractor.ui.state.BatchSummary
import com.chimerateam.offsetextractor.ui.theme.MonoText

/**
 * Progress of a multi-APK run, with the only useful escape hatch.
 *
 * The queue is sequential, so the position tells the user how much is left and
 * the cancel button stops after the APK in flight is torn down.
 *
 * @param progress snapshot of the queue.
 * @param onCancel aborts the remaining APKs.
 */
@Composable
fun BatchProgressDialog(
    progress: BatchProgress,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* The run owns its own dismissal via cancel. */ },
        title = { Text("Scanning a batch") },
        text = {
            Column {
                Text(text = progress.label, style = MonoText.body)
                progress.currentName?.let { name ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.total == 0) {
                            0f
                        } else {
                            progress.completed.toFloat() / progress.total
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${progress.succeeded} succeeded · ${progress.failed} failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel batch")
            }
        },
    )
}

/**
 * Outcome of a finished multi-APK run.
 *
 * @param summary counts from the run.
 * @param onDismiss dismisses the dialog.
 */
@Composable
fun BatchSummaryDialog(
    summary: BatchSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch complete") },
        text = { Text(summary.message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
