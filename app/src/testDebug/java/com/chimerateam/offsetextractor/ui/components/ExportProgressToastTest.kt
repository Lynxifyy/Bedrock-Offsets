package com.chimerateam.offsetextractor.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.data.export.ExportProgress
import com.chimerateam.offsetextractor.data.export.ExportStage
import com.chimerateam.offsetextractor.data.export.ExportState
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies the progress pill and the interrupted-job card. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExportProgressToastTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(progress: ExportProgress) {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                ExportProgressToast(
                    progress = progress,
                    onCancel = {},
                    onRetry = {},
                    onShare = {},
                    onDismiss = {}
                )
            }
        }
    }

    @Test
    fun aRunningJobShowsTheRingPercentageNameAndCancel() {
        render(
            ExportProgress(
                stage = ExportStage.Building,
                bytesDone = 12_400_000,
                bytesTotal = 38_100_000,
                fraction = 0.5f,
                fileName = "BEDROCK_OFFSETS_26.60.3.zip",
                state = ExportState.Running,
                jobId = "job"
            )
        )

        composeTestRule.onNodeWithTag(EXPORT_TOAST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("BEDROCK_OFFSETS_26.60.3.zip")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Building ZIP…", substring = true).assertExists()
        composeTestRule.onNodeWithTag(EXPORT_CANCEL_TAG).assertIsDisplayed()
    }

    @Test
    fun aFinishedJobShowsACheckAndShare() {
        render(
            ExportProgress(
                stage = ExportStage.Saving,
                fraction = 1f,
                fileName = "BEDROCK_OFFSETS_26.60.3.zip",
                state = ExportState.Done,
                zipPath = "/tmp/BEDROCK_OFFSETS_26.60.3.zip",
                jobId = "job"
            )
        )

        composeTestRule
            .onNodeWithContentDescription("Download complete")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Saved BEDROCK_OFFSETS_26.60.3.zip")
            .assertExists()
        composeTestRule.onNodeWithTag(EXPORT_SHARE_TAG).assertIsDisplayed()
    }

    @Test
    fun aFailedJobTurnsRedAndOffersRetry() {
        render(
            ExportProgress(
                stage = ExportStage.Saving,
                fraction = 0.4f,
                fileName = "BEDROCK_OFFSETS_26.60.3.zip",
                state = ExportState.Failed,
                error = "storage full",
                jobId = "job"
            )
        )

        composeTestRule.onNodeWithText("storage full").assertExists()
        composeTestRule.onNodeWithTag(EXPORT_RETRY_TAG).assertIsDisplayed()
    }

    @Test
    fun theInterruptedCardOffersRetryAndDiscard() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                ExportInterruptedCard(
                    progress = ExportProgress(
                        fileName = "BEDROCK_OFFSETS_26.60.3.zip",
                        state = ExportState.Interrupted,
                        error = "The destination was revoked"
                    ),
                    onRetry = {},
                    onDiscard = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(EXPORT_INTERRUPTED_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Download was interrupted").assertExists()
        composeTestRule.onNodeWithTag(EXPORT_RETRY_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(EXPORT_DISCARD_TAG).assertIsDisplayed()
    }
}
