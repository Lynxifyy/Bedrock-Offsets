package com.chimerateam.offsetextractor.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations reachable from the navigation bar or rail.
 *
 * @property label the name shown under the icon.
 * @property icon the Material Symbols Rounded glyph for the destination.
 */
enum class AppTab(val label: String, val icon: ImageVector) {
    Scanner("Scanner", Icons.Filled.Radar),
    Apks("APKs", Icons.Filled.Download),
    Settings("Settings", Icons.Filled.Settings)
}
