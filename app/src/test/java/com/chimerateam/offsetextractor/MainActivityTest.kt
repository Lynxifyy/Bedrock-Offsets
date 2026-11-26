package com.chimerateam.offsetextractor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies that the activity launches and that the shell navigates between
 * its two destinations.
 *
 * Runs on the JVM through Robolectric so it is part of `./gradlew test`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesOnScannerAndOffersSelection() {
        composeTestRule.onNodeWithText("Select APK").assertIsDisplayed()
    }

    @Test
    fun theLowMemoryCallbackRaisesTheNoticeOnlyWhenTheSettingIsOn() {
        // The notice is advisory, so it is off until the user asks for it.
        composeTestRule.activity.onLowMemory()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Low on memory").assertDoesNotExist()

        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule
            .onNodeWithContentDescription("Low memory warnings")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("Scanner").performClick()
        composeTestRule.waitForIdle()

        // onLowMemory is delivered on the same pressure as onTrimMemory, and
        // both must reach the same notice.
        composeTestRule.activity.onLowMemory()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Low on memory").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationSwitchesToSettings() {
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Dark mode").performScrollTo().assertIsDisplayed()
    }
}
