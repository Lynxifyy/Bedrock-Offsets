package com.chimerateam.offsetextractor.ui.log

import androidx.compose.ui.graphics.Color
import com.chimerateam.offsetextractor.ui.theme.LogCacheTeal
import com.chimerateam.offsetextractor.ui.theme.LogErrorRed
import com.chimerateam.offsetextractor.ui.theme.LogRustPurple
import com.chimerateam.offsetextractor.ui.theme.LogSafGreen
import com.chimerateam.offsetextractor.ui.theme.LogUiBlue

/**
 * Colour assigned to each log tag, so a tag always reads the same way.
 *
 * A tag the palette does not know falls back to the theme's secondary text
 * colour, which keeps a new tag visible without a code change here.
 */
object LogPalette {

    /** Colour used for the tag of [entry]. */
    fun colorFor(entry: LogEntry): Color = colorForTag(entry.tag)

    /** Colour used for [tag]. */
    fun colorForTag(tag: String): Color = when (tag) {
        "RUST" -> LogRustPurple
        "UI" -> LogUiBlue
        "CACHE" -> LogCacheTeal
        "SAF" -> LogSafGreen
        "ERROR" -> LogErrorRed
        else -> LogUiBlue
    }

    /** Colour used for the message body of [entry]. */
    fun bodyColorFor(entry: LogEntry): Color = when (entry.level) {
        LogLevel.Error -> LogErrorRed
        LogLevel.Warning -> LogUiBlue
        LogLevel.Info -> LogUiBlue
    }
}
