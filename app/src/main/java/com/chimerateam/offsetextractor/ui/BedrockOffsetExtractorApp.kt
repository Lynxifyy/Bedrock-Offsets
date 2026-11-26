package com.chimerateam.offsetextractor.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chimerateam.offsetextractor.data.export.CategoryCatalog
import com.chimerateam.offsetextractor.data.export.ExportJobRepository
import com.chimerateam.offsetextractor.data.export.ExportKind
import com.chimerateam.offsetextractor.data.export.ExportState
import com.chimerateam.offsetextractor.data.export.OffsetExporter
import com.chimerateam.offsetextractor.data.saf.APK_MIME_TYPE
import com.chimerateam.offsetextractor.export.ExportService
import com.chimerateam.offsetextractor.ui.components.BatchProgressDialog
import com.chimerateam.offsetextractor.ui.components.BatchSummaryDialog
import com.chimerateam.offsetextractor.ui.components.ExportProgressToast
import com.chimerateam.offsetextractor.ui.components.LowMemoryDialog
import com.chimerateam.offsetextractor.ui.log.LogTimestamp
import com.chimerateam.offsetextractor.ui.screens.ApksScreen
import com.chimerateam.offsetextractor.ui.screens.ScannerScreen
import com.chimerateam.offsetextractor.ui.screens.SettingsScreen
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.theme.Amber500
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.BrandBlue
import com.chimerateam.offsetextractor.ui.theme.BrandPink
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import com.chimerateam.offsetextractor.ui.viewmodel.MainViewModel
import com.chimerateam.offsetextractor.util.formatBytes
import java.io.File
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
    val includeCombined by viewModel.includeCombined.collectAsStateWithLifecycle()
    val lowMemoryWarnings by viewModel.lowMemoryWarnings.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()
    val deleteApkAfterScan by viewModel.deleteApkAfterScan.collectAsStateWithLifecycle()
    val cacheCapMb by viewModel.cacheCapMb.collectAsStateWithLifecycle()
    val reduceMotion by viewModel.reduceMotion.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val exportProgress by ExportJobRepository.progress.collectAsStateWithLifecycle()

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
        // The pane holds the newest lines only; the file has the whole run.
        val text = viewModel.logText().ifBlank {
            logEntries.joinToString("\n") { entry ->
                "${LogTimestamp.format(entry.timestampMillis)} ${entry.render()}"
            }
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

    val saveZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        viewModel.onZipDestinationChosen(uri)
    }

    var pendingZipName by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        pendingZipName?.let { saveZipLauncher.launch(it) }
        pendingZipName = null
    }

    val categoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val imported = CategoryCatalog.import(context, uri)
            if (imported) {
                viewModel.setCategories(CategoryCatalog.load(context))
                viewModel.onExportActionTaken("Category rules imported.")
            } else {
                viewModel.onExportActionTaken("That file is not a valid category rule set.")
            }
        }
    }

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
    val downloadZip = {
        val name = viewModel.defaultZipFileName()
        if (name == null) {
            viewModel.onExportActionTaken("The internal offsets are not ready yet.")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingZipName = name
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            saveZipLauncher.launch(name)
        }
    }
    val shareZip: (String) -> Unit = { path ->
        val file = File(path)
        if (!file.isFile) {
            viewModel.onExportActionTaken("The cached archive is missing.")
        } else {
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share offsets"))
            }.onFailure {
                viewModel.onExportActionTaken("Could not share the archive: ${it.message}")
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                            onDownloadZip = downloadZip,
                            onRetryInternal = { viewModel.retryInternalExtraction() },
                            internalBusy = false,
                            exportProgress = exportProgress,
                            onRetryExport = { ExportService.retry(context) },
                            onDiscardExport = { ExportService.discard(context) }
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
                            onMaxSlotsPerClassChange = { viewModel.setMaxSlotsPerClass(it) },
                            includeCombined = includeCombined,
                            onIncludeCombinedChange = { viewModel.setIncludeCombined(it) },
                            lowMemoryWarnings = lowMemoryWarnings,
                            onLowMemoryWarningsChange = {
                                viewModel.setLowMemoryWarnings(it)
                            },
                            onImportCategories = {
                                categoryPicker.launch(
                                    arrayOf("application/json")
                                )
                            },
                            onResetCategories = {
                                CategoryCatalog.reset(context)
                                viewModel.setCategories(CategoryCatalog.load(context))
                                viewModel.onExportActionTaken(
                                    "Default category rules restored."
                                )
                            },
                            storageUsage = storageUsage,
                            deleteApkAfterScan = deleteApkAfterScan,
                            onDeleteApkAfterScanChange = {
                                viewModel.setDeleteApkAfterScan(it)
                            },
                            cacheCapMb = cacheCapMb,
                            onCacheCapMbChange = { viewModel.setCacheCapMb(it) },
                            onFreeUpSpace = { viewModel.freeUpSpace() },
                            onDeleteArea = { viewModel.deleteStorageArea(it) },
                            onDeleteFile = { viewModel.deleteStorageFile(it) },
                            jobRunning = state.phase == ScannerPhase.Extracting ||
                                state.phase == ScannerPhase.Scanning ||
                                exportProgress.isRunning,
                            reduceMotion = reduceMotion,
                            onReduceMotionChange = { viewModel.setReduceMotion(it) }
                        )
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.chimerateam.offsetextractor.ui.theme.LocalReduceMotion
                        .provides(reduceMotion)
                ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = exportProgress.state != ExportState.Idle &&
                        exportProgress.state != ExportState.Cancelled,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = androidx.compose.animation.fadeIn(
                        com.chimerateam.offsetextractor.ui.theme.motionTween(
                            com.chimerateam.offsetextractor.ui.theme.MotionDuration.Medium,
                            reduceMotion = reduceMotion
                        )
                    ) + androidx.compose.animation.scaleIn(
                        initialScale = 0.96f,
                        animationSpec = com.chimerateam.offsetextractor.ui.theme.motionTween(
                            com.chimerateam.offsetextractor.ui.theme.MotionDuration.Medium,
                            reduceMotion = reduceMotion
                        )
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        com.chimerateam.offsetextractor.ui.theme.motionTween(
                            com.chimerateam.offsetextractor.ui.theme.MotionDuration.Short,
                            reduceMotion = reduceMotion
                        )
                    )
                ) {
                    ExportProgressToast(
                        progress = exportProgress,
                        onCancel = { ExportService.cancel(context) },
                        onRetry = { ExportService.retry(context) },
                        onShare = shareZip,
                        onDismiss = { ExportJobRepository.clear() },
                        modifier = Modifier
                            // Clears the navigation bar and the pinned action
                            // row: the pill used to sit on top of Copy, Share
                            // and Start new scan.
                            .padding(horizontal = 16.dp)
                            .padding(bottom = TOAST_BOTTOM_INSET)
                    )
                }
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

/** Distance the progress pill keeps from the bottom edge. */
private val TOAST_BOTTOM_INSET = 96.dp
