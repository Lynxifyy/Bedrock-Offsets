package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the theme picks the palette its mode asks for.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Renders [modes] in turn and returns the Material scheme each produced.
     *
     * The mode is a state read inside one composition because a test may call
     * `setContent` only once; changing it recomposes with the new palette.
     */
    private fun schemesFor(vararg modes: ThemeMode): Map<ThemeMode, ColorScheme> {
        val captured = mutableMapOf<ThemeMode, ColorScheme>()
        var current by mutableStateOf(modes.first())
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme(themeMode = current) {
                captured[current] = MaterialTheme.colorScheme
            }
        }
        composeTestRule.waitForIdle()
        for (mode in modes.drop(1)) {
            current = mode
            composeTestRule.waitForIdle()
        }
        return captured
    }

    @Test
    fun amoledDrawsOnPureBlack() {
        val scheme = schemesFor(ThemeMode.Amoled).getValue(ThemeMode.Amoled)

        assertEquals(AmoledBlack, scheme.background)
        assertEquals(AmoledBlack, scheme.surface)
    }

    @Test
    fun amoledIsNotThePlainDarkPalette() {
        val schemes = schemesFor(ThemeMode.Dark, ThemeMode.Amoled)

        assertEquals(SurfaceDark, schemes.getValue(ThemeMode.Dark).background)
        assertEquals(AmoledBlack, schemes.getValue(ThemeMode.Amoled).background)
    }

    @Test
    fun theLightPaletteUsesItsOwnBackground() {
        val schemes = schemesFor(ThemeMode.Light, ThemeMode.Dark)

        assertEquals(Neutral50, schemes.getValue(ThemeMode.Light).background)
        assertEquals(SurfaceDark, schemes.getValue(ThemeMode.Dark).background)
    }
}
