package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.ui.graphics.Color

// Blue / white / pink palette.
//
// The token names predate the palette change and are kept so call sites and
// previews do not churn; each name now carries its blue-family value.

/** Brand blue, used as the light-theme primary. */
val Caramel600 = Color(0xFF2563EB)

/** Brighter blue for dark-theme primary contrast. */
val Caramel400 = Color(0xFF7AA2FF)

/** Pink highlight used for accents, badges and the scanning glow. */
val Honey300 = Color(0xFFFF7EB6)
val Honey100 = Color(0xFFFFD6E7)

/** Deep navy for text on light surfaces. */
val Caramel900 = Color(0xFF0B1F4B)
val Caramel800 = Color(0xFF16357A)
val Caramel100 = Color(0xFFDCE8FF)
val Caramel50 = Color(0xFFF3F7FF)

/** Secondary blue family. */
val Amber700 = Color(0xFF1D4ED8)
val Amber600 = Color(0xFF3B82F6)
val Amber500 = Color(0xFF60A5FA)
val Amber200 = Color(0xFFBFDBFE)
val Amber100 = Color(0xFFDBEAFE)

/** Neutral slate family. */
val Neutral900 = Color(0xFF0E1526)
val Neutral800 = Color(0xFF1B2740)
val Neutral100 = Color(0xFFF4F7FF)
val Neutral50 = Color(0xFFFBFCFE)

val SurfaceDark = Color(0xFF0B1220)

/** Pure black used by the AMOLED theme. */
val AmoledBlack = Color(0xFF000000)

/** Pink used for the accent half of the brand gradient. */
val BrandPink = Color(0xFFFF5C9E)

/** Blue used for the primary half of the brand gradient. */
val BrandBlue = Color(0xFF2563EB)

/** White used for the highlight stop of the brand gradient. */
val BrandWhite = Color(0xFFFFFFFF)

// Log level accents.
val LogRustPurple = Color(0xFFC4B5FD)
val LogUiBlue = Color(0xFF93C5FD)
val LogErrorRed = Color(0xFFFCA5A5)
val LogCacheTeal = Color(0xFF7DD3FC)
val LogSafGreen = Color(0xFF86EFAC)
