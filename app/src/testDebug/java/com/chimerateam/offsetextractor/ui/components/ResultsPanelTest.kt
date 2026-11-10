package com.chimerateam.offsetextractor.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies that the results panel renders the address information a downstream
 * loader needs: the `.text` boundaries and the load delta.
 *
 * Lives in the debug source set because `createComposeRule` hosts content in an
 * activity that ships only with the debug test manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ResultsPanelTest {

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
        occupiesFile = true
    )

    private val summary = ScanResultSummary(
        displayName = "minecraft-1.21.50.apk",
        sha256 = "0123456789abcdef0123456789abcdef",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        textSection = text
    )

    @Test
    fun rendersTextBoundariesAndLoadDelta() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                ResultsPanel(result = summary)
            }
        }

        // The panel is taller than the test viewport, so the lower fields are
        // present in the tree without being on screen.
        composeTestRule.onNodeWithText(".text start").assertExists()
        composeTestRule.onNodeWithText("0x400000").assertExists()
        composeTestRule.onNodeWithText("0xba0000").assertExists()
        composeTestRule.onNodeWithText("0x3fd000").assertExists()
    }

    @Test
    fun rendersLibraryIdentity() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                ResultsPanel(result = summary)
            }
        }

        composeTestRule.onNodeWithText("minecraft-1.21.50.apk").assertExists()
        composeTestRule.onNodeWithText("AArch64").assertExists()
        composeTestRule.onNodeWithText("0x4000").assertExists()
        composeTestRule.onNodeWithText("33").assertExists()
    }

    @Test
    fun omitsSectionBlockWhenTheLayoutIsUnknown() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                ResultsPanel(result = summary.copy(textSection = null))
            }
        }

        composeTestRule.onNodeWithText("Executable section").assertDoesNotExist()
        composeTestRule.onNodeWithText("AArch64").assertExists()
    }
}
