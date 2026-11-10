package com.chimerateam.offsetextractor.ui.log

import com.chimerateam.offsetextractor.ui.theme.LogCacheTeal
import com.chimerateam.offsetextractor.ui.theme.LogErrorRed
import com.chimerateam.offsetextractor.ui.theme.LogRustPurple
import com.chimerateam.offsetextractor.ui.theme.LogSafGreen
import com.chimerateam.offsetextractor.ui.theme.LogUiBlue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies every tag keeps a stable colour. */
class LogPaletteTest {

    @Test
    fun eachKnownTagHasItsOwnColour() {
        assertEquals(LogRustPurple, LogPalette.colorForTag("RUST"))
        assertEquals(LogUiBlue, LogPalette.colorForTag("UI"))
        assertEquals(LogCacheTeal, LogPalette.colorForTag("CACHE"))
        assertEquals(LogSafGreen, LogPalette.colorForTag("SAF"))
        assertEquals(LogErrorRed, LogPalette.colorForTag("ERROR"))
    }

    @Test
    fun anUnknownTagStillGetsAColour() {
        assertEquals(LogUiBlue, LogPalette.colorForTag("SOMETHING_NEW"))
    }

    @Test
    fun theTagColourComesFromTheEntryTag() {
        val entry = Logs.warn("RUST", "careful", nowMillis = 0)

        assertEquals(LogRustPurple, LogPalette.colorFor(entry))
    }

    @Test
    fun errorsAreColouredDistinctlyFromInformation() {
        val info = Logs.info("UI", "fine", nowMillis = 0)
        val error = Logs.error("UI", "broken", nowMillis = 0)

        assertEquals(LogErrorRed, LogPalette.bodyColorFor(error))
        assertEquals(LogUiBlue, LogPalette.bodyColorFor(info))
    }
}
