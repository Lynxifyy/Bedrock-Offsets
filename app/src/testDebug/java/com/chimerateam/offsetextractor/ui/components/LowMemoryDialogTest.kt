package com.chimerateam.offsetextractor.ui.components

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
 * Verifies the low-memory notice explains itself and offers the useful action.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LowMemoryDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun itExplainsThatTheScanCanContinue() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                LowMemoryDialog(onDismiss = {}, onClearCache = {})
            }
        }

        composeTestRule.onNodeWithText("Low on memory").assertExists()
        composeTestRule.onNodeWithText("keeps streaming", substring = true).assertExists()
    }

    @Test
    fun clearingTheCacheInvokesItsCallback() {
        var cleared = 0
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                LowMemoryDialog(onDismiss = {}, onClearCache = { cleared++ })
            }
        }

        composeTestRule.onNodeWithText("Clear cache").performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun continuingInvokesDismiss() {
        var dismissed = 0
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                LowMemoryDialog(onDismiss = { dismissed++ }, onClearCache = {})
            }
        }

        composeTestRule.onNodeWithText("Continue").performClick()

        assertEquals(1, dismissed)
    }
}
