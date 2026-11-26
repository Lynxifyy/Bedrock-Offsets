package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.ui.graphics.Color

// Graphite palette: neutral surfaces that let the data be the only colour, one
// precise accent (a cool blue) and one secondary (a warm amber) for the second
// meaning on a screen. Screens read these through `MaterialTheme.colorScheme`
// or the semantic tokens below; no screen declares a colour of its own.

/** Dark-first graphite surfaces, one step per elevation. */
val Graphite950 = Color(0xFF0B0C0E)
val Graphite900 = Color(0xFF121417)
val Graphite850 = Color(0xFF171A1E)
val Graphite800 = Color(0xFF1D2126)
val Graphite700 = Color(0xFF262B31)
val Graphite600 = Color(0xFF333A42)
val Graphite500 = Color(0xFF4A525C)

/** Light theme surfaces, the same steps read the other way. */
val Graphite100 = Color(0xFFF6F7F9)
val Graphite150 = Color(0xFFEFF1F4)
val Graphite200 = Color(0xFFE4E7EC)
val Graphite300 = Color(0xFFD3D8E0)

/** Pure black, for the AMOLED theme. */
val AmoledBlack = Color(0xFF000000)

/** Text on graphite. */
val Ink50 = Color(0xFFF2F4F7)
val Ink300 = Color(0xFFA7AFBB)
val Ink600 = Color(0xFF4C545F)
val Ink900 = Color(0xFF14171B)

/** The one accent: a precise cool blue, bright enough on graphite. */
val Accent400 = Color(0xFF6EA8FF)
val Accent500 = Color(0xFF3B82F6)
val Accent600 = Color(0xFF2563EB)

/** The one secondary: warm amber, used for the second meaning only. */
val Secondary300 = Color(0xFFF7C86B)
val Secondary500 = Color(0xFFE3A02A)
val Secondary700 = Color(0xFF9A6512)

/** Semantic colours, the same hue in both themes so meaning is recognisable. */
val Success400 = Color(0xFF4ADE80)
val Success700 = Color(0xFF15803D)
val Warning400 = Color(0xFFFBBF24)
val Warning700 = Color(0xFFB45309)
val Error300 = Color(0xFFFCA5A5)
val Error700 = Color(0xFFB91C1C)
val Info400 = Color(0xFF67E8F9)
val Info700 = Color(0xFF0E7490)

// Log level accents: one hue per origin tag, readable on graphite.
val LogRustPurple = Color(0xFFC4B5FD)
val LogUiBlue = Color(0xFF93C5FD)
val LogErrorRed = Error300
val LogCacheTeal = Color(0xFF7DD3FC)
val LogSafGreen = Color(0xFF86EFAC)

/**
 * The colours a screen needs that Material's scheme has no slot for.
 *
 * @property success completed work.
 * @property warning something the user should read.
 * @property info neutral context.
 * @property accentGraphic the accent as a drawing colour.
 * @property secondaryGraphic the secondary as a drawing colour.
 */
data class SemanticColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val accentGraphic: Color,
    val secondaryGraphic: Color
)

/** Semantic colours for the dark scheme. */
val DarkSemantics = SemanticColors(
    success = Success400,
    warning = Warning400,
    info = Info400,
    accentGraphic = Accent400,
    secondaryGraphic = Secondary300
)

/** Semantic colours for the light scheme. */
val LightSemantics = SemanticColors(
    success = Success700,
    warning = Warning700,
    info = Info700,
    accentGraphic = Accent600,
    secondaryGraphic = Secondary700
)
