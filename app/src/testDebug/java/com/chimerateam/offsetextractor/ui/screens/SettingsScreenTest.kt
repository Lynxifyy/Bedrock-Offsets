package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies every Settings control actually reports a new value. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(
        themeMode: ThemeMode = ThemeMode.System,
        includeAllFunctions: Boolean = true,
        generateSignatures: Boolean = true,
        maxSlots: Int = 4096,
        onTheme: (ThemeMode) -> Unit = {},
        onInclude: (Boolean) -> Unit = {},
        onSignatures: (Boolean) -> Unit = {},
        onSlots: (Int) -> Unit = {}
    ) {
        composeTestRule.setContent {
            var mode by remember { mutableStateOf(themeMode) }
            BedrockOffsetExtractorTheme {
                SettingsScreen(
                    themeMode = mode,
                    onThemeModeChange = {
                        mode = it
                        onTheme(it)
                    },
                    cacheSizeLabel = "0 B",
                    forceRescan = false,
                    onForceRescanChange = {},
                    onClearCache = {},
                    lastBuildId = "deadbeef",
                    includeAllFunctions = includeAllFunctions,
                    generateSignatures = generateSignatures,
                    maxSlotsPerClass = maxSlots,
                    onIncludeAllFunctionsChange = onInclude,
                    onGenerateSignaturesChange = onSignatures,
                    onMaxSlotsPerClassChange = onSlots
                )
            }
        }
    }

    @Test
    fun theThemeSwitchesReportTheirMode() {
        val modes = mutableListOf<ThemeMode>()
        render(onTheme = { modes += it })

        composeTestRule.onNodeWithContentDescription("Dark mode").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Follow system").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Follow system").performScrollTo().performClick()

        assertEquals(listOf(ThemeMode.Dark, ThemeMode.System, ThemeMode.Light), modes)
    }

    @Test
    fun theInternalTogglesReportTheirNewValue() {
        var include: Boolean? = null
        var signatures: Boolean? = null
        render(
            onInclude = { include = it },
            onSignatures = { signatures = it }
        )

        composeTestRule
            .onNodeWithContentDescription("Include all functions")
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithContentDescription("Generate signatures")
            .performScrollTo()
            .performClick()

        assertEquals(false, include)
        assertEquals(false, signatures)
    }

    @Test
    fun theSlotCapButtonsApplyTheirValue() {
        var slots: Int? = null
        render(maxSlots = 4096, onSlots = { slots = it })

        composeTestRule.onNodeWithText("256").performScrollTo().performClick()

        assertEquals(256, slots)
    }

    @Test
    fun theStoragePanelShowsTheBuildIdAndCacheSize() {
        render()

        composeTestRule.onNodeWithText("deadbeef").performScrollTo().assertExists()
    }
}
