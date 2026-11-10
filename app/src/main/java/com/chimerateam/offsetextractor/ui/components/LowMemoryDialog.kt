package com.chimerateam.offsetextractor.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Warning shown when the system reports memory pressure.
 *
 * The scan itself streams from the file descriptor and never allocates with
 * the library size, so the notice does not offer to abort the scan. It offers
 * the one action that actually reclaims space, which is emptying the cache.
 *
 * @param onDismiss dismisses the notice without doing anything else.
 * @param onClearCache empties the cache and dismisses the notice.
 */
@Composable
fun LowMemoryDialog(
    onDismiss: () -> Unit,
    onClearCache: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Low on memory") },
        text = {
            Text(
                "The system is running low on memory. The scan keeps streaming " +
                    "rather than loading the file, so it can continue, but " +
                    "clearing the cache will free the most space.",
            )
        },
        confirmButton = {
            TextButton(onClick = onClearCache) {
                Text("Clear cache")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue")
            }
        },
    )
}
