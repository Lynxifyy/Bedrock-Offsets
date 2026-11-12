package com.chimerateam.offsetextractor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.saf.APK_MIME_TYPE
import com.chimerateam.offsetextractor.ui.components.BatchProgressDialog
import com.chimerateam.offsetextractor.ui.components.BatchSummaryDialog
import com.chimerateam.offsetextractor.ui.components.LowMemoryDialog
import com.chimerateam.offsetextractor.ui.log.LogTimestamp
import com.chimerateam.offsetextractor.ui.screens.ScannerScreen
import com.chimerateam.offsetextractor.ui.screens.SettingsScreen
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import com.chimerateam.offsetextractor.ui.viewmodel.MainViewModel
import com.chimerateam.offsetextractor.util.formatBytes
import kotlinx.coroutines.delay

/**
 * Root composable of the application.
 *
 * Owns the shell state (destination and theme) and renders the scanner state
 * published by [viewModel]. All file work and descriptor ownership live in the
 * ViewModel, so rotation and navigation cannot leak or duplicate a descriptor.
 *
 * @param viewModel scanner ViewModel backing the whole shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedrockOffsetExtractorApp(viewModel: MainViewModel) {
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    var tab by rememberSaveable { mutableStateOf(AppTab.Scanner) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val forceRescan by viewModel.forceRescan.collectAsStateWithLifecycle()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val lastBuildId by viewModel.lastBuildId.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            viewModel.onSelectionCancelled()
        } else {
            viewModel.onApkSelected(uri)
        }
    }

    val batchPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> viewModel.onApksSelected(uris) }

    val context = LocalContext.current
    val copyLog = {
        val text = logEntries.joinToString("\n") { entry ->
            "${LogTimestamp.format(entry.timestampMillis)} ${entry.render()}"
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bedrock log", text))
        viewModel.onExportActionTaken("Log copied to the clipboard.")
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    // The save dialog needs the default name before it is shown, so it is
    // read from the ViewModel at launch time.
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> viewModel.onExportDestinationChosen(uri) }

    state.batch?.let { batch ->
        BatchProgressDialog(
            progress = batch,
            onCancel = { viewModel.cancelBatch() }
        )
    }

    state.batchSummary?.let { summary ->
        BatchSummaryDialog(
            summary = summary,
            onDismiss = { viewModel.onBatchSummaryShown() }
        )
    }

    if (state.lowMemory) {
        LowMemoryDialog(
            onDismiss = { viewModel.onLowMemoryNoticeDismissed() },
            onClearCache = {
                viewModel.clearCache()
                viewModel.onLowMemoryNoticeDismissed()
            }
        )
    }

    LaunchedEffect(state.exportMessage) {
        val message = state.exportMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.onExportMessageShown()
    }

    // A finished scan gets a short buzz so the user does not have to watch the
    // screen. Failure gets a distinct double buzz, which is the one signal
    // that has to be distinguishable without looking.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.phase) {
        when (state.phase) {
            ScannerPhase.Success ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)

            ScannerPhase.Error -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(120)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            else -> Unit
        }
    }

    // The exported document is built on demand and shared as a whole, so a
    // large result set is never held twice.
    val exportJson = {
        state.result?.let { OffsetExporter.toJson(it) }.orEmpty()
    }
    val copyJson = {
        val json = exportJson()
        if (json.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bedrock offsets", json))
            viewModel.onExportActionTaken("Offsets copied to the clipboard.")
        }
    }
    val shareJson = {
        val json = exportJson()
        if (json.isNotEmpty()) {
            val result = state.result
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, json)
                putExtra(Intent.EXTRA_SUBJECT, "Bedrock offsets ${result?.buildId.orEmpty()}")
            }
            context.startActivity(Intent.createChooser(intent, "Share offsets"))
            viewModel.onExportActionTaken("Offsets handed to the share sheet.")
        }
    }
    val saveJson = {
        val name = viewModel.defaultExportFileName()
        if (name == null) {
            viewModel.onExportActionTaken("There is nothing to export yet.")
        } else {
            saveLauncher.launch(name)
        }
    }

    BedrockOffsetExtractorTheme(themeMode = themeMode) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bedrock Offset Extractor") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.clearCache() }) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = "Clear cache"
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy offsets as JSON") },
                                enabled = state.result != null,
                                onClick = {
                                    menuOpen = false
                                    copyJson()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share offsets as JSON") },
                                enabled = state.result != null,
                                onClick = {
                                    menuOpen = false
                                    shareJson()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save offsets to a file") },
                                enabled = state.result != null,
                                onClick = {
                                    menuOpen = false
                                    saveJson()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Scan several APKs") },
                                enabled = state.batch == null,
                                onClick = {
                                    menuOpen = false
                                    batchPicker.launch(arrayOf(APK_MIME_TYPE))
                                }
                            )
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    NavigationItem(
                        tab = AppTab.Scanner,
                        selected = tab == AppTab.Scanner,
                        icon = Icons.Filled.Radar,
                        onSelect = { tab = AppTab.Scanner }
                    )
                    NavigationItem(
                        tab = AppTab.Settings,
                        selected = tab == AppTab.Settings,
                        icon = Icons.Filled.Settings,
                        onSelect = { tab = AppTab.Settings }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (tab) {
                    AppTab.Scanner -> ScannerScreen(
                        state = state,
                        entries = logEntries,
                        onCopyLog = copyLog,
                        onClearLog = { viewModel.clearLog() },
                        onSelectApk = { picker.launch(arrayOf(APK_MIME_TYPE)) },
                        onSelectApks = { batchPicker.launch(arrayOf(APK_MIME_TYPE)) },
                        onCancelSelection = { viewModel.cancel() },
                        onRetry = { viewModel.retry() },
                        onCopyJson = copyJson,
                        onShareJson = shareJson,
                        onSaveJson = saveJson
                    )

                    AppTab.Settings -> SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { themeMode = it },
                        cacheSizeLabel = formatBytes(cacheSizeBytes),
                        forceRescan = forceRescan,
                        onForceRescanChange = { viewModel.setForceRescan(it) },
                        onClearCache = { viewModel.clearCache() },
                        lastBuildId = lastBuildId
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavigationItem(
    tab: AppTab,
    selected: Boolean,
    icon: ImageVector,
    onSelect: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onSelect,
        icon = { Icon(imageVector = icon, contentDescription = tab.label) },
        label = { Text(tab.label) }
    )
}
