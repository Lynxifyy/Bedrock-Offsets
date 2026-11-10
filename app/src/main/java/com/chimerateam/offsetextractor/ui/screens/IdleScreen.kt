package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.components.HoneycombCell
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.Honey300

/**
 * Welcome state shown before an APK has been chosen.
 *
 * @param onSelectApk invoked when the user requests the system file picker.
 * @param onSelectApks invoked when the user requests a multi-APK batch.
 * @param modifier layout modifier for the column.
 */
@Composable
fun IdleScreen(
    onSelectApk: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectApks: () -> Unit = onSelectApk,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HoneycombCell(size = 72.dp, color = Honey300, fillAlpha = 0.2f)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Bedrock Offset Extractor",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Pick a Minecraft Bedrock APK to harvest its native offsets. " +
                "Nothing leaves the device and the library is never executed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSelectApk) {
            Text("Select APK")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSelectApks) {
            Text("Select several APKs")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun IdleScreenPreview() {
    BedrockOffsetExtractorTheme {
        IdleScreen(onSelectApk = {})
    }
}
