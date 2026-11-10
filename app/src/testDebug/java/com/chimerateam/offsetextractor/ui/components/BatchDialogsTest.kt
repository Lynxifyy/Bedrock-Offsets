package com.chimerateam.offsetextractor.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.ui.state.BatchProgress
import com.chimerateam.offsetextractor.ui.state.BatchSummary
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the batch progress and summary dialogs show the queue state and
 * wire up their single action.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BatchDialogsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun theProgressDialogNamesThePositionAndCounts() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                BatchProgressDialog(
                    progress = BatchProgress(
                        total = 3,
                        completed = 1,
                        succeeded = 1,
                        failed = 0,
                        currentName = "minecraft-1.21.50.apk",
                    ),
                    onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithText("2 of 3 APKs").assertExists()
        composeTestRule.onNodeWithText("minecraft-1.21.50.apk").assertExists()
        composeTestRule.onNodeWithText("1 succeeded · 0 failed").assertExists()
    }

    @Test
    fun cancellingTheBatchInvokesItsCallback() {
        var cancelled = 0
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                BatchProgressDialog(
                    progress = BatchProgress(total = 2),
                    onCancel = { cancelled++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel batch").performClick()

        assertEquals(1, cancelled)
    }

    @Test
    fun theSummaryDialogReportsTheCounts() {
        var dismissed = 0
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                BatchSummaryDialog(
                    summary = BatchSummary(total = 3, succeeded = 2, failed = 1),
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeTestRule
            .onNodeWithText("Processed 3 APKs. 2 succeeded, 1 failed.")
            .assertExists()
        composeTestRule.onNodeWithText("Done").performClick()

        assertEquals(1, dismissed)
    }
}
