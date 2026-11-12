package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which background treatment the app should use. */
enum class ThemeMode { System, Light, Dark, Amoled }

private val LightColors = lightColorScheme(
    primary = Caramel600,
    onPrimary = Color.White,
    primaryContainer = Caramel100,
    onPrimaryContainer = Caramel900,
    secondary = Amber600,
    onSecondary = Color.White,
    secondaryContainer = Amber100,
    onSecondaryContainer = Caramel900,
    tertiary = Amber700,
    background = Neutral50,
    onBackground = Neutral900,
    surface = Neutral50,
    onSurface = Neutral900,
    surfaceVariant = Caramel50,
    onSurfaceVariant = Caramel800,
    outline = Caramel600,
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Caramel400,
    onPrimary = Caramel900,
    primaryContainer = Caramel800,
    onPrimaryContainer = Caramel100,
    secondary = Amber500,
    onSecondary = Neutral900,
    secondaryContainer = Amber700,
    onSecondaryContainer = Amber100,
    tertiary = Amber200,
    background = SurfaceDark,
    onBackground = Neutral100,
    surface = SurfaceDark,
    onSurface = Neutral100,
    surfaceVariant = Neutral800,
    onSurfaceVariant = Honey100,
    outline = Caramel400,
    error = LogErrorRed
)

private val AmoledColors = DarkColors.copy(
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceVariant = Neutral900
)

/** Corner radius shared by cards and panels. */
val PanelCorner: Dp = 14.dp

/** Standard gap between panels. */
val PanelGap: Dp = 12.dp

/**
 * Applies the Caramel/Honey colour scheme and the JetBrains Mono data styles.
 *
 * @param themeMode requested palette; [ThemeMode.System] follows the device.
 */
@Composable
fun BedrockOffsetExtractorTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark, ThemeMode.Amoled -> true
    }

    val colorScheme = when (themeMode) {
        ThemeMode.Amoled -> AmoledColors
        ThemeMode.Dark -> DarkColors
        ThemeMode.Light -> LightColors
        ThemeMode.System -> if (dark) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
