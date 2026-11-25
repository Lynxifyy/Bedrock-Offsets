package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chimerateam.offsetextractor.data.native.InternalSummary
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
        composeTestRule.onNodeWithTag(SEARCH_FIELD_TAG).assertExists()
        composeTestRule.onNodeWithTag(DOWNLOAD_ZIP_TAG).assertExists()
        composeTestRule.onNodeWithTag(RESULTS_LIST_TAG)
            .performScrollToNode(hasText("clientInstance"))
        composeTestRule.onNodeWithText("clientInstance").assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1920dp-h1200dp-land")
    fun aLandscapeTabletKeepsTheSearchFieldAndTheDownloadVisible() {
        renderWide(
            ScannerUiState(
                phase = ScannerPhase.Success,
                progress = 100,
                progressTask = "Mapping complete",
                result = summary
            )
        )

        composeTestRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DOWNLOAD_ZIP_TAG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Download BEDROCK_OFFSETS_deadbeef.zip")
            .assertIsDisplayed()
    }

    /**
     * The summary card must show the whole "Verified signatures" block.
     *
     * A device screenshot showed the card cut off after its heading, with
     * Matches, Discarded and Peak resident missing, so this asserts the three
     * rows are actually laid out and visible on a landscape tablet.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w1920dp-h1200dp-land")
    fun theResultCardShowsEveryVerifiedSignatureRow() {
        renderWide(
            ScannerUiState(
                phase = ScannerPhase.Success,
                progress = 100,
                progressTask = "Mapping complete",
                result = summary.copy(
                    discardedMatches = 7,
                    peakResidentBytes = 154L * 1024 * 1024
                )
            )
        )

        composeTestRule.onNodeWithText("Matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discarded").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peak resident").assertIsDisplayed()
    }

    /**
     * The same three rows must survive the narrow, phone-shaped layout.
     *
     * This is the layout the device screenshot came from: one column, a pinned
     * footer under the list and a log pane taking the remaining height. If the
     * footer grows, the list viewport shrinks and the card is cut mid-row.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun theResultCardShowsEveryVerifiedSignatureRowOnAPhone() {
        renderWide(
            ScannerUiState(
                phase = ScannerPhase.Success,
                progress = 100,
                progressTask = "Mapping complete",
                result = summary.copy(
                    discardedMatches = 7,
                    peakResidentBytes = 154L * 1024 * 1024
                )
            )
        )

        composeTestRule.onNodeWithText("Matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discarded").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peak resident").assertIsDisplayed()
    }

    /**
     * A summary taller than the list viewport must still be readable.
     *
     * A LazyColumn clips an item taller than its viewport and the clipped part
     * cannot be reached by scrolling the list, so the summary needs its own
     * scroll container. This is the shape of the real result card: a dozen
     * internal rows below the signature block.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun aTallSummaryStaysReachableOnAPhone() {
        renderWide(
            ScannerUiState(
                phase = ScannerPhase.Success,
                progress = 100,
                progressTask = "Mapping complete",
                internalAvailable = true,
                result = summary.copy(
                    discardedMatches = 7,
                    peakResidentBytes = 154L * 1024 * 1024,
                    internal = InternalSummary(
                        classes = 7396,
                        vtableSlots = 140501,
                        functions = 803306,
                        named = 131770,
                        verified = 129000,
                        signatures = 131770,
                        signaturesUnique = 4000,
                        dataSymbols = 1432,
                        path = "/data/scan-data/internal.ndjson",
                        tableBytes = 247L * 1024 * 1024,
                        rows = 945000
                    ),
                    internalResolved = true
                )
            )
        )

        composeTestRule.onNodeWithText("Peak resident").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Table").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(DOWNLOAD_ZIP_TAG).assertIsDisplayed()
    }

    @Test
    fun theIdlePaneRendersSideBySideWithoutCrashing() {
        renderWide(ScannerUiState(phase = ScannerPhase.Idle))

        composeTestRule.onNodeWithText("Select APK").assertExists()
    }
}
