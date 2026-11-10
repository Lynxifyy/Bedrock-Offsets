package com.chimerateam.offsetextractor.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.ui.components.LiveLogPane
import com.chimerateam.offsetextractor.ui.components.ResultsPanel
import com.chimerateam.offsetextractor.ui.components.SelectedApkCard
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.state.ScannerUiState
import com.chimerateam.offsetextractor.ui.theme.PanelGap

/**
 * Scanner destination: controls and results on one side, live log on the other.
 *
 * On compact widths the log is stacked below the controls; from 600dp the two
 * panes sit side by side.
 *
 * @param state the scanner snapshot published by the ViewModel.
 * @param messages live log lines in chronological order.
 * @param onSelectApk invoked when the user requests the system file picker.
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
    messages: List<String>,
    onSelectApk: () -> Unit,
    onCancelSelection: () -> Unit,
    onRetry: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val sideBySide = availableWidth >= 600.dp

        val controls: @Composable () -> Unit = {
            ControlsPane(
                state = state,
                onSelectApk = onSelectApk,
                onCancelSelection = onCancelSelection,
                onRetry = onRetry,
                onCopyJson = onCopyJson,
                onShareJson = onShareJson,
                onSaveJson = onSaveJson,
            )
        }

        if (sideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PanelGap),
                horizontalArrangement = Arrangement.spacedBy(PanelGap),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    controls()
                }
                LiveLogPane(
                    messages = messages,
                    modifier = Modifier
                        .width(availableWidth * 0.42f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PanelGap),
                verticalArrangement = Arrangement.spacedBy(PanelGap),
            ) {
                controls()
                LiveLogPane(
                    messages = messages,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ControlsPane(
    state: ScannerUiState,
    onSelectApk: () -> Unit,
    onCancelSelection: () -> Unit,
    onRetry: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
) {
    when (state.phase) {
        ScannerPhase.Extracting, ScannerPhase.Scanning -> LoadingScreen(
            message = state.progressTask,
            progress = state.progress,
        )

        ScannerPhase.Success -> SuccessPane(
            state = state,
            onStartNewScan = onCancelSelection,
            onCopyJson = onCopyJson,
            onShareJson = onShareJson,
            onSaveJson = onSaveJson,
        )

        ScannerPhase.Error -> ErrorPane(
            message = state.error ?: "The scan failed.",
            hint = state.errorHint,
            onRetry = onRetry,
            onSelectApk = onSelectApk,
        )

        ScannerPhase.Idle -> if (state.selectedApk != null) {
            SelectedApkCard(
                metadata = state.selectedApk,
                usedCacheCopy = state.usedCacheCopy,
                onCancel = onCancelSelection,
                onSelectApk = onSelectApk,
            )
        } else {
            IdleScreen(onSelectApk = onSelectApk)
        }
    }
}

@Composable
private fun SuccessPane(
    state: ScannerUiState,
    onStartNewScan: () -> Unit,
    onCopyJson: () -> Unit,
    onShareJson: () -> Unit,
    onSaveJson: () -> Unit,
) {
    val result = state.result ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        ResultsPanel(result = result)
        Spacer(Modifier.height(PanelGap))
        ResultsScreen(
            result = result,
            onCopyJson = onCopyJson,
            onShareJson = onShareJson,
            onSaveJson = onSaveJson,
            onStartNewScan = onStartNewScan,
        )
    }
}

@Composable
private fun ErrorPane(
    message: String,
    hint: String?,
    onRetry: () -> Unit,
    onSelectApk: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
