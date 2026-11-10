package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which background treatment the app should use. */
enum class ThemeMode { System, Light, Dark, Amoled }

/**
 * Colour roles that Material 3 does not model, such as log-tag accents.
 *
 * @property isAmoled whether the AMOLED pure-black background is active.
 * @property logRust accent for `[RUST]` log lines.
 * @property logUi accent for `[UI]` log lines.
 * @property logError accent for `[ERROR]` log lines.
 * @property logCache accent for `[CACHE]` log lines.
 * @property logSaf accent for `[SAF]` log lines.
 * @property honeyGlow colour of the scanning honeycomb glow.
 */
data class ExtendedColors(
    val isAmoled: Boolean,
    val logRust: Color,
    val logUi: Color,
    val logError: Color,
    val logCache: Color,
    val logSaf: Color,
    val honeyGlow: Color,
)

private val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        isAmoled = false,
        logRust = LogRustPurple,
        logUi = LogUiBlue,
        logError = LogErrorRed,
        logCache = LogCacheTeal,
        logSaf = LogSafGreen,
        honeyGlow = Honey300,
    )
}

/** Access the non-Material colour roles. */
object AppTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

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
    error = Color(0xFFBA1A1A),
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
    error = LogErrorRed,
)

private val AmoledColors = DarkColors.copy(
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceVariant = Neutral900,
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
    content: @Composable () -> Unit,
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

    val extended = ExtendedColors(
        isAmoled = themeMode == ThemeMode.Amoled,
        logRust = LogRustPurple,
        logUi = LogUiBlue,
        logError = LogErrorRed,
        logCache = LogCacheTeal,
        logSaf = LogSafGreen,
        honeyGlow = if (dark) Honey300 else Caramel600,
    )

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
