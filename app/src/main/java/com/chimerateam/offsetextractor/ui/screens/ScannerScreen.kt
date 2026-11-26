package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.export.ExportProgress
import com.chimerateam.offsetextractor.ui.components.ExportInterruptedCard
import com.chimerateam.offsetextractor.ui.components.LiveLogPane
import com.chimerateam.offsetextractor.ui.components.ResultsPanel
import com.chimerateam.offsetextractor.ui.components.SelectedApkCard
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.theme.MotionDuration
import com.chimerateam.offsetextractor.ui.theme.MotionEasing
import com.chimerateam.offsetextractor.ui.theme.motionTween
import com.chimerateam.offsetextractor.ui.state.ScannerUiState
import com.chimerateam.offsetextractor.ui.theme.PanelGap

/**
 * Scanner destination: controls and results on one side, live log on the other.
 *
 * On compact widths the log is stacked below the controls; from 600dp the two
 * panes sit side by side.
 *
 * @param state the scanner snapshot published by the ViewModel.
 * @param entries live log entries in chronological order.
 * @param onCopyLog copies the log to the clipboard.
 * @param onClearLog discards the log.
 * @param onSelectApk invoked when the user requests the system file picker.
 * @param onSelectApks invoked when the user requests a multi-APK batch.
 * @param onCancelSelection releases the descriptor and resets the selection.
 * @param onRetry clears an error and returns the scanner to idle.
 * @param onCopyJson copies the exported JSON to the clipboard.
 * @param onShareJson shares the exported JSON.
 * @param onSaveJson writes the exported JSON to a user-chosen location.
 * @param modifier layout modifier for the screen.
 */
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    entries: List<LogEntry>,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
    onSelectApk: () -> Unit,
    onSelectApks: () -> Unit,
    onCancelSelection: () -> Unit,
    onRetry: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    modifier: Modifier = Modifier,
    onDownloadZip: () -> Unit = {},
    onRetryInternal: () -> Unit = {},
    internalBusy: Boolean = false,
    exportProgress: ExportProgress? = null,
    onRetryExport: () -> Unit = {},
    onDiscardExport: () -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val sideBySide = availableWidth >= 600.dp

        if (sideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PanelGap),
                horizontalArrangement = Arrangement.spacedBy(PanelGap)
            ) {
                // Deliberately not scrollable: the success pane ends in a lazily
                // scrolled list, and a lazy list inside a vertically scrollable
                // parent is measured with an infinite maximum height, which
                // throws. The pane takes the full height and the list scrolls
                // inside it.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    ControlsPane(
                        state = state,
                        fillHeight = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onSelectApk = onSelectApk,
                        onSelectApks = onSelectApks,
                        onCancelSelection = onCancelSelection,
                        onRetry = onRetry,
                        onCopyJson = onCopyJson,
                        onShareJson = onShareJson,
                        onSaveJson = onSaveJson,
                        onDownloadZip = onDownloadZip,
                        onRetryInternal = onRetryInternal,
                        internalBusy = internalBusy,
                        exportProgress = exportProgress,
                        onRetryExport = onRetryExport,
                        onDiscardExport = onDiscardExport
                    )
                }
                LiveLogPane(
                    entries = entries,
                    onCopyLog = onCopyLog,
                    onClearLog = onClearLog,
                    modifier = Modifier
                        .width(availableWidth * 0.42f)
                        .fillMaxHeight()
                )
            }
        } else {
            // Collapsed by default on a phone: an expanded log stole the
            // height the result card needs to show its own rows.
            var logExpanded by rememberSaveable { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PanelGap),
                verticalArrangement = Arrangement.spacedBy(PanelGap)
            ) {
                ControlsPane(
                    state = state,
                    // Bounded, because the pane holds a weighted list: a wrap-content pane
                    // took the whole screen and pushed the pinned controls out of view,
                    // which cut the result card off.
                    fillHeight = false,
                    onSelectApk = onSelectApk,
                    onSelectApks = onSelectApks,
                    onCancelSelection = onCancelSelection,
                    onRetry = onRetry,
                    onCopyJson = onCopyJson,
                    onShareJson = onShareJson,
                    onSaveJson = onSaveJson,
                    onDownloadZip = onDownloadZip,
                    onRetryInternal = onRetryInternal,
                    internalBusy = internalBusy,
                    exportProgress = exportProgress,
                    onRetryExport = onRetryExport,
                    onDiscardExport = onDiscardExport
                )
                LiveLogPane(
                    entries = entries,
                    onCopyLog = onCopyLog,
                    onClearLog = onClearLog,
                    collapsible = true,
                    expanded = logExpanded,
                    onToggle = { logExpanded = !logExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (logExpanded) {
                                Modifier.weight(0.5f)
                            } else {
                                Modifier.wrapContentHeight()
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun ControlsPane(
    state: ScannerUiState,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
    onSelectApk: () -> Unit,
    onSelectApks: () -> Unit,
    onCancelSelection: () -> Unit,
    onRetry: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onDownloadZip: () -> Unit,
    onRetryInternal: () -> Unit,
    internalBusy: Boolean,
    exportProgress: ExportProgress?,
    onRetryExport: () -> Unit,
    onDiscardExport: () -> Unit
) {
    if (exportProgress != null && exportProgress.state ==
        com.chimerateam.offsetextractor.data.export.ExportState.Interrupted
    ) {
        ExportInterruptedCard(
            progress = exportProgress,
            onRetry = onRetryExport,
            onDiscard = onDiscardExport
        )
        Spacer(Modifier.height(12.dp))
    }

    // AnimatedContent keeps the state change visible rather than instantaneous:
    // a fade and a small slide make it obvious which pane replaced which.
    AnimatedContent(
        modifier = modifier,
        targetState = state.phase,
        transitionSpec = {
            val enter = motionTween<Float>(MotionDuration.Medium, MotionEasing.Decelerate)
            val slide = motionTween<androidx.compose.ui.unit.IntOffset>(
                MotionDuration.Medium,
                MotionEasing.Decelerate
            )
            val exit = motionTween<Float>(MotionDuration.Short, MotionEasing.Accelerate)
            (fadeIn(enter) + slideInVertically(slide) { it / 12 })
                .togetherWith(fadeOut(exit))
        },
        label = "scanner-phase"
    ) { phase ->
        PhasePane(
            phase = phase,
            state = state,
            fillHeight = fillHeight,
            onSelectApk = onSelectApk,
            onSelectApks = onSelectApks,
            onCancelSelection = onCancelSelection,
            onRetry = onRetry,
            onCopyJson = onCopyJson,
            onShareJson = onShareJson,
            onSaveJson = onSaveJson,
            onDownloadZip = onDownloadZip,
            onRetryInternal = onRetryInternal,
            internalBusy = internalBusy,
            exportProgress = exportProgress,
            onRetryExport = onRetryExport,
            onDiscardExport = onDiscardExport
        )
    }
}

@Composable
private fun PhasePane(
    phase: ScannerPhase,
    state: ScannerUiState,
    fillHeight: Boolean,
    onSelectApk: () -> Unit,
    onSelectApks: () -> Unit,
    onCancelSelection: () -> Unit,
    onRetry: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onDownloadZip: () -> Unit,
    onRetryInternal: () -> Unit,
    internalBusy: Boolean,
    exportProgress: ExportProgress?,
    onRetryExport: () -> Unit,
    onDiscardExport: () -> Unit
) {
    when (phase) {
        ScannerPhase.Extracting, ScannerPhase.Scanning -> LoadingScreen(
            message = state.progressTask,
            progress = state.progress
        )

        ScannerPhase.Success -> SuccessPane(
            state = state,
            fillHeight = fillHeight,
            onStartNewScan = onCancelSelection,
            onCopyJson = onCopyJson,
            onShareJson = onShareJson,
            onSaveJson = onSaveJson,
            onDownloadZip = onDownloadZip,
            onRetryInternal = onRetryInternal,
            internalBusy = internalBusy,
            exportProgress = exportProgress,
            onRetryExport = onRetryExport,
            onDiscardExport = onDiscardExport
        )

        ScannerPhase.Error -> ErrorPane(
            message = state.error ?: "The scan failed.",
            hint = state.errorHint,
            onRetry = onRetry,
            onSelectApk = onSelectApk
        )

        ScannerPhase.Idle -> if (state.selectedApk != null) {
            SelectedApkCard(
                metadata = state.selectedApk,
                usedCacheCopy = state.usedCacheCopy,
                onCancel = onCancelSelection,
                onSelectApk = onSelectApk
            )
        } else {
            IdleScreen(onSelectApk = onSelectApk, onSelectApks = onSelectApks)
        }
    }
}

@Composable
private fun SuccessPane(
    state: ScannerUiState,
    fillHeight: Boolean,
    onStartNewScan: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    onDownloadZip: () -> Unit,
    onRetryInternal: () -> Unit,
    internalBusy: Boolean,
    exportProgress: ExportProgress?,
    onRetryExport: () -> Unit,
    onDiscardExport: () -> Unit
) {
    val result = state.result ?: return
    // One scroll container: the summary is the first item of the list and the
    // offset rows follow. The export buttons are outside the list, so they stay
    // pinned no matter how long the list grows.
    ResultsScreen(
        result = result,
        fillHeight = fillHeight,
        header = { ResultsPanel(result = result, internalAvailable = state.internalAvailable) },
        onCopyJson = onCopyJson,
        onShareJson = onShareJson,
        onSaveJson = onSaveJson,
        onStartNewScan = onStartNewScan,
        onDownloadZip = onDownloadZip,
        onRetryInternal = onRetryInternal,
        internalBusy = internalBusy,
        internalAvailable = state.internalAvailable
    )
}

@Composable
private fun ErrorPane(
    message: String,
    hint: String?,
    onRetry: () -> Unit,
    onSelectApk: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
            OutlinedButton(onClick = onSelectApk) {
                Text("Select another APK")
            }
        }
    }
}
