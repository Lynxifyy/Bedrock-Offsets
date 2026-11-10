package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chimerateam.offsetextractor.data.saf.ApkSource
import com.chimerateam.offsetextractor.ui.components.LiveLogPane
import com.chimerateam.offsetextractor.ui.components.SelectedApkCard
import com.chimerateam.offsetextractor.ui.theme.PanelGap

/**
 * Scanner destination: controls and results on one side, live log on the other.
 *
 * On compact widths the log is stacked below the controls; from 600dp the two
 * panes sit side by side.
 *
 * @param isBusy whether an extraction or scan is running.
 * @param progress completion in the range 0..100.
 * @param busyMessage task description shown while busy.
 * @param messages live log lines.
 * @param selectedApk the APK whose descriptor is currently held, if any.
 * @param onSelectApk invoked when the user requests the system file picker.
 * @param onCancelSelection releases the descriptor and resets the selection.
 * @param modifier layout modifier for the screen.
 */
@Composable
fun ScannerScreen(
    isBusy: Boolean,
    progress: Int,
    busyMessage: String,
    messages: List<String>,
    selectedApk: ApkSource?,
    onSelectApk: () -> Unit,
    onCancelSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val sideBySide = availableWidth >= 600.dp

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
                    ControlsPane(
                        isBusy = isBusy,
                        progress = progress,
                        busyMessage = busyMessage,
                        selectedApk = selectedApk,
                        onSelectApk = onSelectApk,
                        onCancelSelection = onCancelSelection,
                    )
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
                ControlsPane(
                    isBusy = isBusy,
                    progress = progress,
                    busyMessage = busyMessage,
                    selectedApk = selectedApk,
                    onSelectApk = onSelectApk,
                    onCancelSelection = onCancelSelection,
                )
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
    isBusy: Boolean,
    progress: Int,
    busyMessage: String,
    selectedApk: ApkSource?,
    onSelectApk: () -> Unit,
    onCancelSelection: () -> Unit,
) {
    when {
        isBusy -> LoadingScreen(message = busyMessage, progress = progress)

        selectedApk != null -> SelectedApkCard(
            metadata = selectedApk.metadata,
            usedCacheCopy = selectedApk.usedCacheCopy,
            onCancel = onCancelSelection,
            onSelectApk = onSelectApk,
        )

        else -> IdleScreen(onSelectApk = onSelectApk)
    }
}
