package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.SectionInfo
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import com.chimerateam.offsetextractor.ui.state.ScannerPhase
import com.chimerateam.offsetextractor.ui.state.ScannerUiState
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders the scanner at a tablet width, where the controls and the log sit side
 * by side.
 *
 * The side-by-side branch wraps its content in a scrollable column. A lazily
 * scrolled list inside a vertically scrollable parent is measured with an
 * infinite maximum height and throws, which is a crash on exactly the devices
 * this app targets. This test exercises that layout so the mistake cannot come
 * back unnoticed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w900dp-h1000dp")
class ScannerScreenWideLayoutTest {

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
        displayName = "minecraft.apk",
        sha256 = "abc",
        sizeBytes = 4096,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 33,
        textSection = text,
        buildId = "deadbeef",
        buildIdSource = "note",
        patternHits = listOf(
            PatternHit(
                name = "clientInstance",
                sectionOffset = 0x1000,
                fileOffset = 0x1000,
                address = 0x401000,
                addressHex = "0x401000",
                prologueKind = "frame_push"
            )
        ),
        expectedPatterns = listOf("clientInstance")
    )

    private fun renderWide(state: ScannerUiState) {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                // Wider than the 600dp breakpoint, so the side-by-side layout is
                // the one under test.
                ScannerScreen(
                    state = state,
                    entries = emptyList(),
                    onCopyLog = {},
                    onClearLog = {},
                    onSelectApk = {},
                    onSelectApks = {},
                    onCancelSelection = {},
                    onRetry = {},
                    onCopyJson = {},
                    onShareJson = {},
                    onSaveJson = {}
                )
            }
        }
    }

    @Test
    fun theSuccessPaneRendersSideBySideWithoutCrashing() {
        renderWide(
            ScannerUiState(
                phase = ScannerPhase.Success,
                progress = 100,
                progressTask = "Mapping complete",
                result = summary
            )
        )

        composeTestRule.onNodeWithText("Offsets").assertExists()
        // The target appears in both the summary panel and the results list.
        composeTestRule.onAllNodesWithText("clientInstance")[0].assertExists()
    }

    @Test
    fun theIdlePaneRendersSideBySideWithoutCrashing() {
        renderWide(ScannerUiState(phase = ScannerPhase.Idle))

        composeTestRule.onNodeWithText("Select APK").assertExists()
    }
}
