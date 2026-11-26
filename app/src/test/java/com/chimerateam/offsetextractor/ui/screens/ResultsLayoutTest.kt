package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.chimerateam.offsetextractor.data.native.InternalSummary
import com.chimerateam.offsetextractor.data.native.PatternHit
import com.chimerateam.offsetextractor.data.native.VerificationSummary
import com.chimerateam.offsetextractor.ui.components.ResultsPanel
import com.chimerateam.offsetextractor.ui.state.ScanResultSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the result card is complete, not cut where the pinned controls begin,
 * at a phone size and at a landscape tablet size.
 */
@RunWith(RobolectricTestRunner::class)
class ResultsLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hit(index: Int) = PatternHit(
        name = "pattern_$index",
        sectionOffset = 0x10L * index,
        fileOffset = 0x10L * index,
        address = 0x1000L + index,
        addressHex = "0x${(0x1000 + index).toString(16)}"
    )

    private fun result() = ScanResultSummary(
        displayName = "minecraft-26-60-arm64-v8a-xbox.apk",
        sha256 = "86311e0d",
        sizeBytes = 1059863217,
        architecture = "AArch64",
        entryPoint = "0x4000",
        sectionCount = 26,
        buildId = "86311e0d",
        apkVersion = "26.60.3",
        internal = InternalSummary(
            classes = 6386,
            vtableSlots = 40000,
            functions = 791952,
            named = 1000,
            dataSymbols = 20,
            rows = 798358
        ),
        verification = VerificationSummary(verified = 60, conflicts = 1, unverified = 7),
        patternHits = List(68) { hit(it) },
        discardedMatches = 12,
        peakResidentBytes = 512L * 1024 * 1024,
        internalResolved = true
    )

    private fun assertCardVisible() {
        compose.setContent {
            ResultsScreen(
                result = result(),
                onCopyJson = {},
                onShareJson = {},
                onSaveJson = {},
                onStartNewScan = {},
                modifier = Modifier.fillMaxSize(),
                fillHeight = true,
                header = { ResultsPanel(result = result()) }
            )
        }
        // These rows sit at the bottom of the card, which is exactly where the
        // pane used to be cut.
        compose.onNodeWithText("Matches").assertIsDisplayed()
        compose.onNodeWithText("Discarded").assertIsDisplayed()
        compose.onNodeWithText("Peak resident").assertIsDisplayed()
        compose.onNodeWithText("Offsets").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun theCardIsCompleteOnAPhone() {
        assertCardVisible()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1920dp-h1200dp-land-xhdpi")
    fun theCardIsCompleteOnALandscapeTablet() {
        assertCardVisible()
    }
}
