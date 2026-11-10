package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the results list renders grouped offsets and filters by search.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ResultsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val text = SectionInfo(
        name = ".text",
        kind = "text",
        address = 0x400000,
        size = 0x7a0000,
        offset = 0x3000,
        align = 16,
        delta = 0x3fd000,
        allocated = true,
        executable = true,
        writable = false,
        occupiesFile = true,
    )

    private fun hit(name: String, address: Long) = PatternHit(
        name = name,
        sectionOffset = address - text.address,
        fileOffset = address - text.delta,
        address = address,
        addressHex = "0x%x".format(address),
        prologueKind = "frame_push",
        prologueInstruction = "stp x29, x30, [sp, #-16]!",
    )

    private val summary = ScanResultSummary(
        displayName = "minecraft.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        textSection = text,
        buildId = "deadbeef",
        buildIdSource = "note",
        patternHits = listOf(hit("clientInstance", 0x401000)),
        expectedPatterns = listOf("clientInstance", "absentSignature"),
    )

    private fun content() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                ResultsScreen(
                    result = summary,
                    onCopyJson = {},
                    onShareJson = {},
                    onStartNewScan = {},
                )
            }
        }
    }

    @Test
    fun rendersMatchedAndMissingRows() {
        content()

        composeTestRule.onNodeWithText("clientInstance").assertExists()
        composeTestRule.onNodeWithText("0x401000").assertExists()
        // The signature that did not match is still listed.
        composeTestRule.onNodeWithText("absentSignature").assertExists()
        composeTestRule.onNodeWithText("2 of 2 entries").assertExists()
    }

    @Test
    fun filteringBySearchHidesNonMatchingRows() {
        content()

        composeTestRule.onNodeWithTag("results-search").performTextInput("absent")

        composeTestRule.onNodeWithText("absentSignature").assertExists()
        composeTestRule.onNodeWithText("clientInstance").assertDoesNotExist()
        composeTestRule.onNodeWithText("1 of 2 entries", substring = true).assertExists()
    }

    @Test
    fun exportActionsArePresent() {
        content()

        composeTestRule.onNodeWithText("Copy JSON").assertExists()
        composeTestRule.onNodeWithText("Share JSON").assertExists()
        composeTestRule.onNodeWithText("Start new scan").assertExists()
    }
}
