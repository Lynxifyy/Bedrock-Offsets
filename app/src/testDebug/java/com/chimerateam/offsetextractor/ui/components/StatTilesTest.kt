package com.chimerateam.offsetextractor.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.data.native.InternalCoverage
import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the stat tiles and the completeness badge read the summary, not a
 * hard-coded number.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatTilesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun result(
        coverage: InternalCoverage,
        verification: VerificationSummary = VerificationSummary.NONE
    ) = ScanResultSummary(
        displayName = "minecraft.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        internal = InternalSummary(
            classes = 6386,
            vtableSlots = 84210,
            functions = 791952,
            signatures = 883574,
            coverage = coverage
        ),
        internalResolved = true,
        jniSymbolCount = 95,
        verification = verification
    )

    @Test
    fun everyCountIsShownWithThousandsSeparators() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                StatTiles(result = result(InternalCoverage()))
            }
        }

        composeTestRule.onNodeWithTag(STAT_TILES_TAG).assertExists()
        composeTestRule.onNodeWithText("791,952").assertExists()
        composeTestRule.onNodeWithText("6,386").assertExists()
        composeTestRule.onNodeWithText("84,210").assertExists()
        composeTestRule.onNodeWithText("883,574").assertExists()
        composeTestRule.onNodeWithText("95").assertExists()
    }

    @Test
    fun aCompleteTableSaysComplete() {
        val coverage = InternalCoverage(
            functions = 791952,
            fdeFunctions = 791952,
            functionsMatchFdes = true,
            classes = 6386,
            slots = 84210
        )
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                StatTiles(result = result(coverage))
            }
        }

        composeTestRule.onNodeWithTag(COMPLETENESS_BADGE_TAG).assertExists()
        composeTestRule.onNodeWithText(
            "Complete: 791,952 of 791,952 functions"
        ).assertExists()
    }

    @Test
    fun aCappedPassSaysPartial() {
        val coverage = InternalCoverage(
            functions = 700000,
            fdeFunctions = 791952,
            fdeMissing = 91952,
            functionsMatchFdes = false,
            truncated = true
        )
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                StatTiles(result = result(coverage))
            }
        }

        composeTestRule.onNodeWithText("Partial: 608,048 of 791,952 functions").assertExists()
    }

    @Test
    fun aScanWithoutAnInternalTableSaysSo() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                StatTiles(
                    result = ScanResultSummary(
                        displayName = "minecraft.apk",
                        sha256 = "abc",
                        sizeBytes = 4096,
                        architecture = "AArch64",
                        entryPoint = "0x4000",
                        sectionCount = 33
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("No internal table to check").assertExists()
    }
}
