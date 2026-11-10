package com.chimerateam.offsetextractor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.chimerateam.offsetextractor.ui.screens.ScannerScreen
import com.chimerateam.offsetextractor.ui.screens.SettingsScreen
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.ThemeMode

/** MIME type of an Android application package. */
private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * Root composable of the application.
 *
 * Owns the shell state: the selected destination, the theme palette, the busy
 * flag and the live log buffer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedrockOffsetExtractorApp() {
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    var tab by rememberSaveable { mutableStateOf(AppTab.Scanner) }
    var isBusy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var busyMessage by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<String>() }

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        isBusy = false
        progress = 0
        if (uri == null) {
            messages.add("[SAF] Selection cancelled.")
        } else {
            messages.add("[SAF] Selected APK: $uri")
            messages.add(
                "[SAF] MIME type: ${context.contentResolver.getType(uri) ?: APK_MIME}",
            )
        }
    }

    val clearCache: () -> Unit = {
        messages.clear()
        progress = 0
        messages.add("[CACHE] Cache cleared.")
        Unit
    }

    BedrockOffsetExtractorTheme(themeMode = themeMode) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bedrock Offset Extractor") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    actions = {
                        IconButton(onClick = clearCache) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = "Clear cache",
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationItem(
                        tab = AppTab.Scanner,
                        selected = tab == AppTab.Scanner,
                        icon = Icons.Filled.Radar,
                        onSelect = { tab = AppTab.Scanner },
                    )
                    NavigationItem(
                        tab = AppTab.Settings,
                        selected = tab == AppTab.Settings,
                        icon = Icons.Filled.Settings,
                        onSelect = { tab = AppTab.Settings },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (tab) {
                    AppTab.Scanner -> ScannerScreen(
                        isBusy = isBusy,
                        progress = progress,
                        busyMessage = busyMessage,
                        messages = messages,
                        onSelectApk = {
                            busyMessage = "Opening file picker"
                            progress = 0
                            isBusy = true
                            picker.launch(arrayOf(APK_MIME))
                        },
                    )

                    AppTab.Settings -> SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { themeMode = it },
                        cacheSizeLabel = "0 B",
                        onClearCache = clearCache,
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
    onSelect: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onSelect,
        icon = { Icon(imageVector = icon, contentDescription = tab.label) },
        label = { Text(tab.label) },
    )
}
