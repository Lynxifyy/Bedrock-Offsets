package com.chimerateam.offsetextractor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chimerateam.offsetextractor.data.export.ExportKind
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.saf.APK_MIME_TYPE
import com.chimerateam.offsetextractor.ui.components.BatchProgressDialog
import com.chimerateam.offsetextractor.ui.components.BatchSummaryDialog
import com.chimerateam.offsetextractor.ui.components.LowMemoryDialog
import com.chimerateam.offsetextractor.ui.log.LogTimestamp
import com.chimerateam.offsetextractor.ui.screens.ApksScreen
import com.chimerateam.offsetextractor.ui.screens.ScannerScreen
import com.chimerateam.offsetextractor.ui.screens.SettingsScreen
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.Amber500
import com.chimerateam.offsetextractor.ui.theme.BrandBlue
import com.chimerateam.offsetextractor.ui.theme.BrandPink
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import com.chimerateam.offsetextractor.ui.viewmodel.MainViewModel
import com.chimerateam.offsetextractor.util.formatBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val themeModeName by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeMode = remember(themeModeName) {
        ThemeMode.values().firstOrNull { it.name == themeModeName } ?: ThemeMode.System
    }
    var tab by rememberSaveable { mutableStateOf(AppTab.Scanner) }
    var pendingExportKind by remember { mutableStateOf(ExportKind.Jni) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val forceRescan by viewModel.forceRescan.collectAsStateWithLifecycle()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val lastBuildId by viewModel.lastBuildId.collectAsStateWithLifecycle()
    val includeAllFunctions by viewModel.includeAllFunctions.collectAsStateWithLifecycle()
    val generateSignatures by viewModel.generateSignatures.collectAsStateWithLifecycle()
    val maxSlotsPerClass by viewModel.maxSlotsPerClass.collectAsStateWithLifecycle()
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
    ) { uri: Uri? -> viewModel.onExportDestinationChosen(uri, pendingExportKind) }

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
        val name = viewModel.defaultExportFileName(ExportKind.Jni)
        if (name == null) {
            viewModel.onExportActionTaken("There is nothing to export yet.")
        } else {
            pendingExportKind = ExportKind.Jni
            saveLauncher.launch(name)
        }
    }
    val downloadJni = {
        val name = viewModel.defaultExportFileName(ExportKind.Jni)
        if (name == null) {
            viewModel.onExportActionTaken("There is nothing to export yet.")
        } else {
            pendingExportKind = ExportKind.Jni
            saveLauncher.launch(name)
        }
    }
    val downloadInternal = {
        val name = viewModel.defaultExportFileName(ExportKind.Internal)
        if (name == null) {
            viewModel.onExportActionTaken("The internal offsets are not ready yet.")
        } else {
            pendingExportKind = ExportKind.Internal
            saveLauncher.launch(name)
        }
    }
    val shareScope = rememberCoroutineScope()
    val shareInternal: () -> Unit = {
        val result = state.result
        if (result == null || !result.internalResolved) {
            viewModel.onExportActionTaken("The internal offsets are not ready yet.")
        } else {
            shareScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                        val file = File(
                            directory,
                            OffsetExporter.internalFileName(result)
                        )
                        file.bufferedWriter().use { writer ->
                            OffsetExporter.writeInternal(result, writer)
                        }
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    }
                }
                outcome.onSuccess { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "Share internal offsets")
                    )
                    viewModel.onExportActionTaken(
                        "Internal offsets handed to the share sheet."
                    )
                }.onFailure {
                    viewModel.onExportActionTaken(
                        "Could not share the internal file: ${it.message}"
                    )
                }
            }
        }
    }

    // A finite sweep on tab change: it looks alive without an always-running
    // animation that would keep Compose's test clock busy forever.
    val sweepTarget = when (tab) {
        AppTab.Scanner -> 0f
        AppTab.Apks -> 1f
        AppTab.Settings -> 2f
    }
    val sweep by animateFloatAsState(
        targetValue = sweepTarget,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "brand-sweep"
    )
    val topBarBrush = remember(sweep) {
        Brush.linearGradient(
            colors = listOf(BrandBlue, Amber500, BrandPink, BrandBlue),
            start = Offset(-200f + sweep * 260f, 0f),
            end = Offset(900f + sweep * 260f, 320f)
        )
    }

    BedrockOffsetExtractorTheme(themeMode = themeMode) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bedrock Offset Extractor") },
                    modifier = Modifier.background(topBarBrush),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
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
                        tab = AppTab.Apks,
                        selected = tab == AppTab.Apks,
                        icon = Icons.Filled.Download,
                        onSelect = { tab = AppTab.Apks }
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
                        onSaveJson = saveJson,
                        onDownloadJni = downloadJni,
                        onDownloadInternal = downloadInternal,
                        onRetryInternal = { viewModel.retryInternalExtraction() },
                        internalBusy = false,
                        onShareInternal = shareInternal
                    )

                    AppTab.Apks -> ApksScreen(
                        onExtract = { file ->
                            tab = AppTab.Scanner
                            viewModel.onApkFileSelected(file)
                        }
                    )

                    AppTab.Settings -> SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { viewModel.setThemeMode(it.name) },
                        cacheSizeLabel = formatBytes(cacheSizeBytes),
                        forceRescan = forceRescan,
                        onForceRescanChange = { viewModel.setForceRescan(it) },
                        onClearCache = { viewModel.clearCache() },
                        lastBuildId = lastBuildId,
                        includeAllFunctions = includeAllFunctions,
                        generateSignatures = generateSignatures,
                        maxSlotsPerClass = maxSlotsPerClass,
                        onIncludeAllFunctionsChange = { viewModel.setIncludeAllFunctions(it) },
                        onGenerateSignaturesChange = { viewModel.setGenerateSignatures(it) },
                        onMaxSlotsPerClassChange = { viewModel.setMaxSlotsPerClass(it) }
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
