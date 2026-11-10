package com.chimerateam.offsetextractor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.saf.ApkMetadata
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.util.formatBytes

/**
 * Summary of the APK currently held open, with the actions that apply to it.
 *
 * @param displayName file name to show.
 * @param sizeBytes size to show; negative means unknown.
 * @param usedCacheCopy whether the descriptor points at a private cache copy.
 * @param onCancel releases the descriptor and clears the selection.
 * @param onSelectApk reopens the picker.
 * @param modifier layout modifier for the panel.
 */
@Composable
fun SelectedApkCard(
    displayName: String,
    sizeBytes: Long,
    usedCacheCopy: Boolean,
    onCancel: () -> Unit,
    onSelectApk: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionPanel(title = "Selected APK", modifier = modifier) {
        Text(text = displayName, style = MonoText.headline)
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatBytes(sizeBytes),
            style = MonoText.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (usedCacheCopy) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Streamed to a private cache copy: the provider could not " +
                    "hand out a file descriptor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSelectApk) {
                Text("Replace APK")
            }
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectedApkCardPreview() {
    BedrockOffsetExtractorTheme {
        SelectedApkCard(
            displayName = "minecraft-1.21.50.apk",
            sizeBytes = 812L * 1024 * 1024,
            usedCacheCopy = false,
            onCancel = {},
            onSelectApk = {}
        )
    }
}

/** Convenience overload taking the metadata model directly. */
@Composable
fun SelectedApkCard(
    metadata: ApkMetadata,
    usedCacheCopy: Boolean,
    onCancel: () -> Unit,
    onSelectApk: () -> Unit,
    modifier: Modifier = Modifier
) {
    SelectedApkCard(
        displayName = metadata.displayName,
        sizeBytes = metadata.sizeBytes,
        usedCacheCopy = usedCacheCopy,
        onCancel = onCancel,
        onSelectApk = onSelectApk,
        modifier = modifier
    )
}
