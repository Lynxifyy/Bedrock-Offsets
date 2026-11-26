package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which background treatment the app should use. */
enum class ThemeMode { System, Light, Dark, Amoled }

private val LightColors = lightColorScheme(
    primary = Accent600,
    onPrimary = Ink50,
    primaryContainer = Graphite200,
    onPrimaryContainer = Ink900,
    secondary = Secondary700,
    onSecondary = Ink50,
    secondaryContainer = Graphite200,
    onSecondaryContainer = Ink900,
    tertiary = Accent500,
    onTertiary = Ink50,
    background = Graphite100,
    onBackground = Ink900,
    surface = Graphite100,
    onSurface = Ink900,
    surfaceVariant = Graphite150,
    onSurfaceVariant = Ink600,
    surfaceContainer = Graphite150,
    surfaceContainerHigh = Graphite200,
    surfaceContainerHighest = Graphite300,
    outline = Graphite500,
    outlineVariant = Graphite300,
    error = Error700,
    onError = Ink50
)

private val DarkColors = darkColorScheme(
    primary = Accent400,
    onPrimary = Graphite950,
    primaryContainer = Graphite700,
    onPrimaryContainer = Ink50,
    secondary = Secondary300,
    onSecondary = Graphite950,
    secondaryContainer = Graphite700,
    onSecondaryContainer = Ink50,
    tertiary = Accent500,
    onTertiary = Graphite950,
    background = Graphite950,
    onBackground = Ink50,
    surface = Graphite900,
    onSurface = Ink50,
    surfaceVariant = Graphite850,
    onSurfaceVariant = Ink300,
    surfaceContainer = Graphite900,
    surfaceContainerHigh = Graphite800,
    surfaceContainerHighest = Graphite700,
    outline = Graphite500,
    outlineVariant = Graphite600,
    error = Error300,
    onError = Graphite950
)

private val AmoledColors = DarkColors.copy(
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceVariant = Graphite900,
    surfaceContainer = AmoledBlack
)

/** Corner radius shared by cards and panels. */
val PanelCorner: Dp = 14.dp

/** Standard gap between panels. */
val PanelGap: Dp = 12.dp

/**
 * Semantic colours that Material's scheme has no slot for.
 *
 * Provided once by [BedrockOffsetExtractorTheme] so a screen never reaches for
 * a literal colour: `LocalSemanticColors.current.success` instead of a hex.
 */
val LocalSemanticColors = staticCompositionLocalOf { DarkSemantics }

/**
 * Applies the graphite colour scheme and the JetBrains Mono data styles.
 *
 * @param themeMode requested palette; [ThemeMode.System] follows the device.
 * @param content the themed content.
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

    CompositionLocalProvider(
        LocalSemanticColors provides if (dark) DarkSemantics else LightSemantics
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
