package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.components.SectionPanel
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.ui.theme.ThemeMode

/**
 * Settings destination.
 *
 * @param themeMode currently selected palette.
 * @param onThemeModeChange invoked with the newly selected palette.
 * @param cacheSizeLabel human-readable cache size.
 * @param onClearCache invoked when the user asks to clear cached artefacts.
 * @param modifier layout modifier for the screen.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    cacheSizeLabel: String,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionPanel(title = "Appearance") {
            SettingToggleRow(
                title = "Dark mode",
                subtitle = "Use the caramel dark palette",
                checked = themeMode == ThemeMode.Dark || themeMode == ThemeMode.Amoled,
                onCheckedChange = { dark ->
                    onThemeModeChange(if (dark) ThemeMode.Dark else ThemeMode.Light)
                },
            )
            SettingToggleRow(
                title = "AMOLED black",
                subtitle = "Pure black background for OLED panels",
                checked = themeMode == ThemeMode.Amoled,
                onCheckedChange = { amoled ->
                    onThemeModeChange(if (amoled) ThemeMode.Amoled else ThemeMode.Dark)
                },
            )
            SettingToggleRow(
                title = "Follow system",
                subtitle = "Match the device light/dark setting",
                checked = themeMode == ThemeMode.System,
                onCheckedChange = { follow ->
                    if (follow) onThemeModeChange(ThemeMode.System)
                },
            )
        }

        SectionPanel(title = "Storage") {
            Text(
                text = "Cache size: $cacheSizeLabel",
                style = MonoText.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onClearCache,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear cache")
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    BedrockOffsetExtractorTheme {
        SettingsScreen(
            themeMode = ThemeMode.Dark,
            onThemeModeChange = {},
            cacheSizeLabel = "0 B",
            onClearCache = {},
        )
    }
}
