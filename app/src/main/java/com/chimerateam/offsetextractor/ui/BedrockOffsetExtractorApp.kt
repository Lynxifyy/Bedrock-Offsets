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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.chimerateam.offsetextractor.data.export.OffsetScripts
import com.chimerateam.offsetextractor.data.export.ScriptFormat
import com.chimerateam.offsetextractor.data.saf.APK_MIME_TYPE
import com.chimerateam.offsetextractor.export.ExportService
import com.chimerateam.offsetextractor.ui.components.BatchProgressDialog
import com.chimerateam.offsetextractor.ui.components.BatchSummaryDialog
import com.chimerateam.offsetextractor.ui.components.ExportProgressToast
import com.chimerateam.offsetextractor.ui.components.IdeCommandBar
import com.chimerateam.offsetextractor.ui.components.IdeStatusBar
import com.chimerateam.offsetextractor.ui.components.LowMemoryDialog
import com.chimerateam.offsetextractor.ui.log.LogTimestamp
import com.chimerateam.offsetextractor.ui.screens.ApksScreen
import com.chimerateam.offsetextractor.ui.screens.DiffScreen
import com.chimerateam.offsetextractor.ui.screens.ScannerScreen
import com.chimerateam.offsetextractor.ui.screens.SettingsScreen
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.LocalReduceMotion
import com.chimerateam.offsetextractor.ui.theme.MotionDuration
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import com.chimerateam.offsetextractor.ui.theme.motionTween
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
    val includeCombined by viewModel.includeCombined.collectAsStateWithLifecycle()
    val lowMemoryWarnings by viewModel.lowMemoryWarnings.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()
    val deleteApkAfterScan by viewModel.deleteApkAfterScan.collectAsStateWithLifecycle()
    val cacheCapMb by viewModel.cacheCapMb.collectAsStateWithLifecycle()
    val reduceMotion by viewModel.reduceMotion.collectAsStateWithLifecycle()
    val toastSpec = motionTween<Float>(MotionDuration.MEDIUM, reduceMotion = reduceMotion)
    val toastExitSpec = motionTween<Float>(MotionDuration.SHORT, reduceMotion = reduceMotion)
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val scanHistory by viewModel.scanHistory.collectAsStateWithLifecycle()
    val inspector by viewModel.inspector.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
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

    // The exported document is built on demand and shared as a whole. Building
    // it is O(result) string work, so it runs on the I/O dispatcher: doing it on
    // the UI thread froze the frame and could OOM the process on a large table.
    val exportScope = rememberCoroutineScope()
    val copyJson: () -> Unit = {
        val result = state.result
        if (result == null) {
            viewModel.onExportActionTaken("There is nothing to export yet.")
        } else {
            exportScope.launch {
                val json = withContext(Dispatchers.IO) { OffsetExporter.toJson(result) }
                if (json.length > CLIPBOARD_JSON_LIMIT) {
                    val file = withContext(Dispatchers.IO) {
                        writeExportFile(context, result.buildId, json)
                    }
                    viewModel.onExportActionTaken(
                        "The offsets are too large for the clipboard; saved to ${file.name}."
                    )
                } else {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Bedrock offsets", json))
                    viewModel.onExportActionTaken("Offsets copied to the clipboard.")
                }
            }
        }
    }
    val shareJson: () -> Unit = {
        val result = state.result
        if (result == null) {
            viewModel.onExportActionTaken("There is nothing to export yet.")
        } else {
            exportScope.launch {
                // Binder transactions are capped at about 1 MB. Putting the
                // document in EXTRA_TEXT throws TransactionTooLargeException
                // once the table grows, so the document goes to a file and the
                // intent carries a content URI instead.
                val file = withContext(Dispatchers.IO) {
                    writeExportFile(context, result.buildId, OffsetExporter.toJson(result))
                }
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(
                            Intent.EXTRA_SUBJECT,
                            "Bedrock offsets ${result.buildId.orEmpty()}"
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share offsets"))
                    viewModel.onExportActionTaken("Offsets handed to the share sheet.")
                }.onFailure {
                    viewModel.onExportActionTaken("Could not share the offsets: ${it.message}")
                }
            }
        }
    }
    val exportScript: (ScriptFormat) -> Unit = { format ->
        val result = state.result
        if (result == null) {
            viewModel.onExportActionTaken("There is nothing to export yet.")
        } else {
            exportScope.launch {
                val file = withContext(Dispatchers.IO) {
                    writeExportFile(
                        context = context,
                        buildId = result.buildId,
                        content = OffsetScripts.render(result, format),
                        extension = format.extension
                    )
                }
                if (shareFile(context, file, format.mimeType, "Share ${format.label}")) {
                    viewModel.onExportActionTaken("${format.label} handed to the share sheet.")
                } else {
                    viewModel.onExportActionTaken(
                        "Could not share the ${format.label}: ${file.name}"
                    )
                }
            }
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

    BedrockOffsetExtractorTheme(themeMode = themeMode) {
        BoxWithConstraints {
            // A rail from 840dp: the three destinations stop being a thumb target
            // and become a permanent left column, which leaves the whole width for
            // the two panes the scanner already uses on tablets.
            val wide = maxWidth >= 840.dp
            Scaffold(
                topBar = {
                    IdeCommandBar(
                        buildId = state.result?.buildId ?: lastBuildId,
                        query = searchQuery,
                        onQueryChange = {
                            searchQuery = it
                            tab = AppTab.Scanner
                        },
                        resultReady = state.result != null,
                        onOpenApk = { picker.launch(arrayOf(APK_MIME_TYPE)) },
                        onOpenBatch = { batchPicker.launch(arrayOf(APK_MIME_TYPE)) },
                        onNewScan = { viewModel.cancel() },
                        onRetry = { viewModel.retry() },
                        onDiff = { tab = AppTab.Diff },
                        onCopyJson = copyJson,
                        onShareJson = shareJson,
                        onSaveJson = saveJson,
                        onDownloadZip = downloadZip,
                        onExportScript = exportScript,
                        onClearCache = { viewModel.clearCache() },
                        onSettings = { tab = AppTab.Settings }
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column {
                        IdeStatusBar(result = state.result)
                        if (!wide) {
                            NavigationBar {
                                AppTab.entries.forEach { entry ->
                                    NavigationItem(
                                        tab = entry,
                                        selected = tab == entry,
                                        onSelect = { tab = entry }
                                    )
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (wide) {
                        AppNavigationRail(current = tab, onSelect = { tab = it })
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
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
                                    onDiscardExport = { ExportService.discard(context) },
                                    wide = wide,
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    inspector = inspector,
                                    onSelectAddress = { viewModel.inspectAddress(it) }
                                )

                                AppTab.Diff -> DiffScreen(
                                    history = scanHistory,
                                    modifier = Modifier.fillMaxSize()
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
                                    onIncludeAllFunctionsChange = {
                                        viewModel.setIncludeAllFunctions(
                                            it
                                        )
                                    },
                                    onGenerateSignaturesChange = {
                                        viewModel.setGenerateSignatures(
                                            it
                                        )
                                    },
                                    onMaxSlotsPerClassChange = {
                                        viewModel.setMaxSlotsPerClass(
                                            it
                                        )
                                    },
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
                        CompositionLocalProvider(
                            LocalReduceMotion.provides(reduceMotion)
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = exportProgress.state != ExportState.Idle &&
                                    exportProgress.state != ExportState.Cancelled,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                enter = fadeIn(toastSpec) + scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = toastSpec
                                ),
                                exit = fadeOut(toastExitSpec)
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
                                        .padding(bottom = if (wide) 24.dp else TOAST_BOTTOM_INSET)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Left navigation for expanded widths.
 *
 * @param current the selected destination.
 * @param onSelect invoked with the destination the user tapped.
 */
@Composable
private fun AppNavigationRail(current: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationRail {
        AppTab.entries.forEach { entry ->
            NavigationRailItem(
                selected = current == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(imageVector = entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) }
            )
        }
    }
}

@Composable
private fun RowScope.NavigationItem(tab: AppTab, selected: Boolean, onSelect: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onSelect,
        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
        label = { Text(tab.label) }
    )
}

/** Distance the progress pill keeps from the bottom edge. */
private val TOAST_BOTTOM_INSET = 96.dp

/**
 * Clipboard payload above which the document is written to a file instead.
 *
 * Android's clipboard round-trips through the system service and large
 * payloads either fail or are dropped by the receiver; 256 KiB is well below
 * the point where that happens and far above a typical offset table.
 */
private const val CLIPBOARD_JSON_LIMIT = 256 * 1024

/**
 * Writes an export document into `cacheDir/exports` and returns the file.
 *
 * The directory is the one declared in `file_paths.xml`, so the file can be
 * shared through the app's `FileProvider` without exposing the whole cache.
 */
private fun writeExportFile(
    context: Context,
    buildId: String,
    content: String,
    extension: String = "json"
): File {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val safeBuild = buildId.ifBlank { "scan" }
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("")
    val file = File(directory, "bedrock-offsets-$safeBuild.$extension")
    file.writeText(content)
    return file
}

/** Hands [file] to the share sheet as a readable content URI. */
private fun shareFile(context: Context, file: File, mimeType: String, title: String): Boolean =
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }.isSuccess
