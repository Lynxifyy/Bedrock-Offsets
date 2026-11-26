package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.cache.DEFAULT_SLOTS_PER_CLASS
import com.chimerateam.offsetextractor.data.storage.StorageArea
import com.chimerateam.offsetextractor.data.storage.StorageAreaId
import com.chimerateam.offsetextractor.data.storage.StorageUsage
import com.chimerateam.offsetextractor.ui.components.SectionPanel
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.MonoText
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import com.chimerateam.offsetextractor.util.formatBytes
import java.io.File

/** Test tag of the storage list. */
const val STORAGE_LIST_TAG = "storage-list"

/** Cache caps offered in Settings, in megabytes; zero means no cap. */
val CACHE_CAP_CHOICES = listOf(512, 1024, 2048, 4096, 0)

/**
 * Per-class slot caps the Settings screen offers.
 *
 * Every entry is accepted unchanged by the engine; the cap is only a safeguard
 * against a corrupt vtable, and reaching it is reported in the archive.
 */
val SLOT_CAP_CHOICES = listOf(1024, DEFAULT_SLOTS_PER_CLASS, 16_384)

/**
 * Settings destination.
 *
 * The sections follow the order of the work: how a scan runs, what the export
 * writes, what the app keeps on disk, what it is allowed to interrupt you with,
 * and how it looks.
 *
 * @param themeMode currently selected palette.
 * @param onThemeModeChange invoked with the newly selected palette.
 * @param cacheSizeLabel human-readable cache size.
 * @param forceRescan whether cached results are ignored.
 * @param onForceRescanChange invoked with the new cache policy.
 * @param onClearCache invoked when the user asks to clear cached artefacts.
 * @param modifier layout modifier for the screen.
 * @param lastBuildId build identifier recorded by the most recent scan, or an
 *   empty string when nothing has been scanned yet.
 * @param lowMemoryWarnings whether the advisory low-memory notice is shown.
 * @param onLowMemoryWarningsChange invoked when the notice is switched.
 * @param storageUsage what the app holds on disk.
 * @param deleteApkAfterScan whether a downloaded APK is deleted after a scan.
 * @param onDeleteApkAfterScanChange invoked when that switch changes.
 * @param cacheCapMb hard cap on cached data in megabytes; zero disables it.
 * @param onCacheCapMbChange invoked with the new cap.
 * @param onFreeUpSpace invoked to delete everything the result does not need.
 * @param onDeleteArea invoked to delete one group.
 * @param onDeleteFile invoked to delete one file.
 * @param jobRunning whether a scan or an export is running, which hides the
 *   destructive actions.
 * @param reduceMotion whether animations are reduced.
 * @param onReduceMotionChange invoked when that switch changes.
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
    onResetCategories: () -> Unit = {},
    storageUsage: StorageUsage = StorageUsage(emptyList(), 0L),
    deleteApkAfterScan: Boolean = true,
    onDeleteApkAfterScanChange: (Boolean) -> Unit = {},
    cacheCapMb: Int = 2048,
    onCacheCapMbChange: (Int) -> Unit = {},
    onFreeUpSpace: () -> Unit = {},
    onDeleteArea: (StorageAreaId) -> Unit = {},
    onDeleteFile: (File) -> Unit = {},
    jobRunning: Boolean = false,
    reduceMotion: Boolean = false,
    onReduceMotionChange: (Boolean) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionPanel(title = "Scanning") {
            SettingToggleRow(
                title = "Include all functions",
                subtitle = "Enumerate every .eh_frame function. Leave on for a complete export.",
                checked = includeAllFunctions,
                onCheckedChange = onIncludeAllFunctionsChange
            )
            SettingToggleRow(
                title = "Generate signatures",
                subtitle = "Adds a full extra pass so offsets survive a game update.",
                checked = generateSignatures,
                onCheckedChange = onGenerateSignaturesChange
            )
            SettingToggleRow(
                title = "Force rescan",
                subtitle = "Ignore a cached result and scan every time.",
                checked = forceRescan,
                onCheckedChange = onForceRescanChange
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Maximum slots per class",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "A cap that is reached is reported in the archive, never applied silently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SLOT_CAP_CHOICES.forEach { slots ->
                    FilterChip(
                        selected = maxSlotsPerClass == slots,
                        onClick = { onMaxSlotsPerClassChange(slots) },
                        label = { Text(slots.toString()) }
                    )
                }
            }
            Text(
                text = "Selected: $maxSlotsPerClass slots per class.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionPanel(title = "Export") {
            SettingToggleRow(
                title = "Also include one combined file",
                subtitle = "Adds a single large internal document next to the category files.",
                checked = includeCombined,
                onCheckedChange = onIncludeCombinedChange
            )
            Spacer(Modifier.height(8.dp))
            Text(text = "Category rules", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Import a categories.json to change how classes and functions " +
                    "are split into files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImportCategories) {
                    Text("Import rules")
                }
                OutlinedButton(onClick = onResetCategories) {
                    Text("Reset to default")
                }
            }
        }

        SectionPanel(title = "Storage") {
            SettingToggleRow(
                title = "Delete downloaded APK after scan",
                subtitle = "Frees about 1 GB per scan. Your own files are never touched.",
                checked = deleteApkAfterScan,
                onCheckedChange = onDeleteApkAfterScanChange
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Keep at most",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Oldest files go first. The table the current result needs is kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CACHE_CAP_CHOICES.forEach { megabytes ->
                    FilterChip(
                        selected = cacheCapMb == megabytes,
                        onClick = { onCacheCapMbChange(megabytes) },
                        label = {
                            Text(
                                if (megabytes == 0) {
                                    "No limit"
                                } else {
                                    formatBytes(megabytes * 1024L * 1024L)
                                }
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            StorageList(
                usage = storageUsage,
                jobRunning = jobRunning,
                onFreeUpSpace = onFreeUpSpace,
                onDeleteArea = onDeleteArea,
                onDeleteFile = onDeleteFile
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Last scanned build: " + lastBuildId.ifBlank { "none yet" },
                style = MonoText.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!jobRunning) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClearCache,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear everything")
                }
            }
        }

        SectionPanel(title = "Notifications") {
            SettingToggleRow(
                title = "Low memory warnings",
                subtitle = "Show a notice when the system reports memory pressure. " +
                    "The scan is unaffected either way.",
                checked = lowMemoryWarnings,
                onCheckedChange = onLowMemoryWarningsChange
            )
        }

        SectionPanel(title = "Appearance") {
            SettingToggleRow(
                title = "Dark mode",
                subtitle = "Use the dark graphite palette",
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
            SettingToggleRow(
                title = "Reduce motion",
                subtitle = "Replace animations with instant transitions",
                checked = reduceMotion,
                onCheckedChange = onReduceMotionChange
            )
        }
    }
}

/**
 * What the app holds, grouped, with the actions that free it.
 *
 * @param usage the current footprint.
 * @param jobRunning whether a job is running; the actions are then disabled.
 * @param onFreeUpSpace deletes everything the result does not need.
 * @param onDeleteArea deletes one group.
 * @param onDeleteFile deletes one file.
 */
@Composable
private fun StorageList(
    usage: StorageUsage,
    jobRunning: Boolean,
    onFreeUpSpace: () -> Unit,
    onDeleteArea: (StorageAreaId) -> Unit,
    onDeleteFile: (File) -> Unit
) {
    var expanded by remember { mutableStateOf<StorageAreaId?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Using ${formatBytes(usage.totalBytes)}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onFreeUpSpace, enabled = !jobRunning) {
            Text("Free up space")
        }
    }
    if (usage.isEmpty) {
        Text(
            text = "Nothing cached. Everything is deleted after a scan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(STORAGE_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        usage.areas.forEach { area ->
            StorageRow(
                area = area,
                expanded = expanded == area.id,
                onToggle = { expanded = if (expanded == area.id) null else area.id },
                onDeleteArea = { onDeleteArea(area.id) },
                onDeleteFile = onDeleteFile,
                jobRunning = jobRunning
            )
        }
    }
}

@Composable
private fun StorageRow(
    area: StorageArea,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeleteArea: () -> Unit,
    onDeleteFile: (File) -> Unit,
    jobRunning: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onToggle, modifier = Modifier.weight(1f)) {
            Text(
                text = "${area.label} — ${formatBytes(area.bytes)} (${area.count})",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        IconButton(
            onClick = onDeleteArea,
            enabled = !jobRunning && area.deletable,
            modifier = Modifier.semantics {
                contentDescription = "Delete ${area.label}"
            }
        ) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = null)
        }
    }
    AnimatedVisibility(visible = expanded) {
        Column {
            area.files.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.file.name,
                            style = MonoText.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatBytes(file.bytes) +
                                if (file.neededByResult) " — needed by the result" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { onDeleteFile(file.file) },
                        enabled = !jobRunning && !file.neededByResult,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete file")
                    }
                }
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
