package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the settings destination surfaces the persisted scan state.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(lastBuildId: String) {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                SettingsScreen(
                    themeMode = ThemeMode.Dark,
                    onThemeModeChange = {},
                    cacheSizeLabel = "0 B",
                    forceRescan = false,
                    onForceRescanChange = {},
                    onClearCache = {},
                    lastBuildId = lastBuildId
                )
            }
        }
    }

    @Test
    fun theStoredBuildIdIsShown() {
        render("abcdef0123456789")

        composeTestRule.onNodeWithText("Last scanned build").assertExists()
        composeTestRule.onNodeWithText("abcdef0123456789").assertExists()
    }

    @Test
    fun anEmptyBuildIdReadsAsNeverScanned() {
        render("")

        composeTestRule.onNodeWithText("None yet").assertExists()
    }
}
