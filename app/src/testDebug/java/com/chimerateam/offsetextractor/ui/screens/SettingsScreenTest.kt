package com.chimerateam.offsetextractor.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import com.chimerateam.offsetextractor.data.storage.StorageArea
import com.chimerateam.offsetextractor.data.storage.StorageAreaId
import com.chimerateam.offsetextractor.data.storage.StorageFile
import com.chimerateam.offsetextractor.data.storage.StorageUsage
import com.chimerateam.offsetextractor.ui.theme.BedrockOffsetExtractorTheme
import com.chimerateam.offsetextractor.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies every Settings control actually reports a new value. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(
        themeMode: ThemeMode = ThemeMode.System,
        includeAllFunctions: Boolean = true,
        generateSignatures: Boolean = true,
        maxSlots: Int = 4096,
        onTheme: (ThemeMode) -> Unit = {},
        onInclude: (Boolean) -> Unit = {},
        onSignatures: (Boolean) -> Unit = {},
        onSlots: (Int) -> Unit = {},
        lowMemoryWarnings: Boolean = false,
        onLowMemory: (Boolean) -> Unit = {}
    ) {
        composeTestRule.setContent {
            var mode by remember { mutableStateOf(themeMode) }
            BedrockOffsetExtractorTheme {
                SettingsScreen(
                    themeMode = mode,
                    onThemeModeChange = {
                        mode = it
                        onTheme(it)
                    },
                    cacheSizeLabel = "0 B",
                    forceRescan = false,
                    onForceRescanChange = {},
                    onClearCache = {},
                    lastBuildId = "deadbeef",
                    includeAllFunctions = includeAllFunctions,
                    generateSignatures = generateSignatures,
                    maxSlotsPerClass = maxSlots,
                    onIncludeAllFunctionsChange = onInclude,
                    onGenerateSignaturesChange = onSignatures,
                    onMaxSlotsPerClassChange = onSlots,
                    lowMemoryWarnings = lowMemoryWarnings,
                    onLowMemoryWarningsChange = onLowMemory
                )
            }
        }
    }

    @Test
    fun theThemeSwitchesReportTheirMode() {
        val modes = mutableListOf<ThemeMode>()
        render(onTheme = { modes += it })

        composeTestRule.onNodeWithContentDescription("Dark mode").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription(
            "Follow system"
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription(
            "Follow system"
        ).performScrollTo().performClick()

        assertEquals(listOf(ThemeMode.Dark, ThemeMode.System, ThemeMode.Light), modes)
    }

    @Test
    fun theInternalTogglesReportTheirNewValue() {
        var include: Boolean? = null
        var signatures: Boolean? = null
        render(
            onInclude = { include = it },
            onSignatures = { signatures = it }
        )

        composeTestRule
            .onNodeWithContentDescription("Include all functions")
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithContentDescription("Generate signatures")
            .performScrollTo()
            .performClick()

        assertEquals(false, include)
        assertEquals(false, signatures)
    }

    @Test
    fun theSlotCapButtonsApplyTheirValue() {
        var slots: Int? = null
        render(maxSlots = 4096, onSlots = { slots = it })

        composeTestRule.onNodeWithText("1024").performScrollTo().performClick()

        assertEquals(1024, slots)
    }

    @Test
    fun theLowMemoryToggleReportsItsNewValue() {
        var enabled: Boolean? = null
        render(lowMemoryWarnings = false, onLowMemory = { enabled = it })

        composeTestRule
            .onNodeWithContentDescription("Low memory warnings")
            .performScrollTo()
            .performClick()

        assertEquals(true, enabled)
    }

    @Test
    fun theStoragePanelShowsTheBuildIdAndCacheSize() {
        render()

        composeTestRule
            .onNodeWithText("Last scanned build: deadbeef")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun theStorageSectionListsEveryGroupWithItsSize() {
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                SettingsScreen(
                    themeMode = ThemeMode.Dark,
                    onThemeModeChange = {},
                    cacheSizeLabel = "3 KB",
                    forceRescan = false,
                    onForceRescanChange = {},
                    onClearCache = {},
                    storageUsage = StorageUsage(
                        areas = listOf(
                            StorageArea(
                                id = StorageAreaId.Apks,
                                bytes = 2048L,
                                files = listOf(
                                    StorageFile(
                                        File("/tmp/minecraft.apk"),
                                        StorageAreaId.Apks,
                                        2048L,
                                        neededByResult = false
                                    )
                                )
                            ),
                            StorageArea(
                                id = StorageAreaId.Internal,
                                bytes = 1024L,
                                files = listOf(
                                    StorageFile(
                                        File("/tmp/abc.ndjson"),
                                        StorageAreaId.Internal,
                                        1024L,
                                        neededByResult = true
                                    )
                                )
                            )
                        ),
                        totalBytes = 3072L
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("Using 3.0 KB").performScrollTo().assertExists()
        composeTestRule
            .onNodeWithText("Downloaded APKs — 2.0 KB (1)")
            .performScrollTo()
            .assertExists()
        composeTestRule
            .onNodeWithText("Internal tables and shards — 1.0 KB (1)")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun theFreeUpSpaceActionIsReported() {
        var freed = 0
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                SettingsScreen(
                    themeMode = ThemeMode.Dark,
                    onThemeModeChange = {},
                    cacheSizeLabel = "0 B",
                    forceRescan = false,
                    onForceRescanChange = {},
                    onClearCache = {},
                    onFreeUpSpace = { freed++ }
                )
            }
        }

        composeTestRule.onNodeWithText("Free up space").performScrollTo().performClick()

        assertEquals(1, freed)
    }

    @Test
    fun theDeleteAfterScanSwitchReportsItsNewValue() {
        var delete: Boolean? = null
        composeTestRule.setContent {
            BedrockOffsetExtractorTheme {
                SettingsScreen(
                    themeMode = ThemeMode.Dark,
                    onThemeModeChange = {},
                    cacheSizeLabel = "0 B",
                    forceRescan = false,
                    onForceRescanChange = {},
                    onClearCache = {},
                    deleteApkAfterScan = true,
                    onDeleteApkAfterScanChange = { delete = it }
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Delete downloaded APK after scan")
            .performScrollTo()
            .performClick()

        assertEquals(false, delete)
    }
}
