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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * @param forceRescan whether cached results are ignored.
 * @param onForceRescanChange invoked with the new cache policy.
 * @param onClearCache invoked when the user asks to clear cached artefacts.
 * @param lastBuildId build identifier recorded by the most recent scan, or an
 *   empty string when nothing has been scanned yet.
 * @param modifier layout modifier for the screen.
 * @param lowMemoryWarnings whether the advisory low-memory notice is shown.
 * @param onLowMemoryWarningsChange invoked when the notice is switched.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    cacheSizeLabel: String,
    forceRescan: Boolean,
    onForceRescanChange: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
    lastBuildId: String = "",
    includeAllFunctions: Boolean = true,
    generateSignatures: Boolean = true,
    maxSlotsPerClass: Int = 4096,
    onIncludeAllFunctionsChange: (Boolean) -> Unit = {},
    onGenerateSignaturesChange: (Boolean) -> Unit = {},
    onMaxSlotsPerClassChange: (Int) -> Unit = {},
    includeCombined: Boolean = false,
    onIncludeCombinedChange: (Boolean) -> Unit = {},
    lowMemoryWarnings: Boolean = false,
    onLowMemoryWarningsChange: (Boolean) -> Unit = {},
    onImportCategories: () -> Unit = {},
    onResetCategories: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionPanel(title = "Appearance") {
            SettingToggleRow(
                title = "Dark mode",
                subtitle = "Use the deep blue dark palette",
                checked = themeMode == ThemeMode.Dark || themeMode == ThemeMode.Amoled,
                onCheckedChange = { dark ->
                    onThemeModeChange(if (dark) ThemeMode.Dark else ThemeMode.Light)
                }
            )
            SettingToggleRow(
                title = "AMOLED black",
                subtitle = "Pure black background for OLED panels",
                checked = themeMode == ThemeMode.Amoled,
                onCheckedChange = { amoled ->
                    onThemeModeChange(if (amoled) ThemeMode.Amoled else ThemeMode.Dark)
                }
            )
            SettingToggleRow(
                title = "Follow system",
                subtitle = "Match the device light/dark setting",
                checked = themeMode == ThemeMode.System,
                onCheckedChange = { follow ->
                    onThemeModeChange(if (follow) ThemeMode.System else ThemeMode.Light)
                }
            )
        }

        SectionPanel(title = "Internal C++ extraction") {
            SettingToggleRow(
                title = "Include all functions",
                subtitle = "Enumerate every .eh_frame function (large file)",
                checked = includeAllFunctions,
                onCheckedChange = onIncludeAllFunctionsChange
            )
            SettingToggleRow(
                title = "Generate signatures",
                subtitle = "Adds a full extra pass so offsets survive an update",
                checked = generateSignatures,
                onCheckedChange = onGenerateSignaturesChange
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Max slots per class: $maxSlotsPerClass",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(256, 1024, 4096).forEach { slots ->
                    OutlinedButton(
                        onClick = { onMaxSlotsPerClassChange(slots) },
                        enabled = slots != maxSlotsPerClass
                    ) {
                        Text(slots.toString())
                    }
                }
            }
        }

        SectionPanel(title = "Download") {
            SettingToggleRow(
                title = "Also include one combined file",
                subtitle = "Adds a single large internal document to the ZIP",
                checked = includeCombined,
                onCheckedChange = onIncludeCombinedChange
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Category rules",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Import a categories.json to change how classes and " +
                    "functions are split into files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImportCategories) {
                    Text("Import categories.json")
                }
                OutlinedButton(onClick = onResetCategories) {
                    Text("Reset to default")
                }
            }
        }

        SectionPanel(title = "Storage") {
            SettingToggleRow(
                title = "Low memory warnings",
                subtitle = "Show a notice when the system reports memory pressure. " +
                    "The scan is unaffected either way.",
                checked = lowMemoryWarnings,
                onCheckedChange = onLowMemoryWarningsChange
            )
            Spacer(Modifier.height(8.dp))
            SettingToggleRow(
                title = "Force rescan",
                subtitle = "Ignore a cached result and scan every time",
                checked = forceRescan,
                onCheckedChange = onForceRescanChange
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Last scanned build",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = lastBuildId.ifBlank { "None yet" },
                style = MonoText.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Cache size: $cacheSizeLabel",
                style = MonoText.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onClearCache,
                modifier = Modifier.fillMaxWidth()
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
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title }
        )
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
            forceRescan = false,
            onForceRescanChange = {},
            onClearCache = {},
            lastBuildId = "abcdef0123456789"
        )
    }
}
