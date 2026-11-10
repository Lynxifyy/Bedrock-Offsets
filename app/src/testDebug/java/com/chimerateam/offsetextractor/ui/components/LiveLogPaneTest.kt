package com.chimerateam.offsetextractor.ui.components
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.ui.log.LogEntry
import com.chimerateam.offsetextractor.ui.log.LogLevel
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
/**
 * Verifies the live log renders tagged entries and exposes its actions.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiveLogPaneTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val entries = listOf(
        LogEntry(0, LogLevel.Info, "SAF", "Opened APK File Descriptor successfully."),
        LogEntry(1, LogLevel.Info, "RUST", "Parallel scan complete."),
        LogEntry(2, LogLevel.Error, "ERROR", "Streaming extraction failed.")
    )

    @Test
    fun rendersEveryEntryWithItsTag() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                LiveLogPane(entries = entries, onCopyLog = {}, onClearLog = {})
            }
        }
        composeTestRule.onNodeWithText(
            "Opened APK File Descriptor successfully.",
            substring = true
        ).assertExists()
        composeTestRule.onNodeWithText("Parallel scan complete.", substring = true).assertExists()
        composeTestRule.onNodeWithText(
            "Streaming extraction failed.",
            substring = true
        ).assertExists()
        composeTestRule.onNodeWithText(
            "[SAF] Parallel scan complete.",
            substring = true
        ).assertDoesNotExist()
    }

    @Test
    fun anEmptyLogExplainsItself() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                LiveLogPane(entries = emptyList(), onCopyLog = {}, onClearLog = {})
            }
        }
        composeTestRule.onNodeWithText("Live log will appear here.").assertExists()
    }

    @Test
    fun copyAndClearInvokeTheirCallbacks() {
        var copies = 0
        var clears = 0
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                LiveLogPane(
                    entries = entries,
                    onCopyLog = { copies++ },
                    onClearLog = { clears++ }
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Copy log").performClick()
        composeTestRule.onNodeWithContentDescription("Clear log").performClick()
        assertEquals(1, copies)
        assertEquals(1, clears)
    }
}
