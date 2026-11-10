package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the empty and busy states render a real message rather than a blank
 * pane, and that the welcome state's only action is wired up.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmptyStatesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun theIdleScreenExplainsItselfAndOffersSelection() {
        var selections = 0
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                IdleScreen(onSelectApk = { selections++ })
            }
        }

        composeTestRule.onNodeWithText("Bedrock Offset Extractor").assertExists()
        composeTestRule.onNodeWithText("Nothing leaves the device", substring = true).assertExists()
        composeTestRule.onNodeWithText("Select APK").performClick()

        assertEquals(1, selections)
    }

    @Test
    fun theLoadingScreenShowsTheTaskAndClampsProgress() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                LoadingScreen(message = "Verifying prologues", progress = 250)
            }
        }

        composeTestRule.onNodeWithText("Verifying prologues").assertExists()
        composeTestRule.onNodeWithText("100%").assertExists()
    }
}
