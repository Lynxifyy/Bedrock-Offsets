package com.chimerateam.offsetextractor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun theLowMemoryCallbackRaisesTheNotice() {
        // onTrimMemory(TRIM_MEMORY_RUNNING_LOW) is not the only callback the
        // system sends; onLowMemory is delivered on the same pressure and must
        // reach the same notice.
        composeTestRule.activity.onLowMemory()

        composeTestRule.onNodeWithText("Low on memory").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationSwitchesToSettings() {
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dark mode").assertIsDisplayed()
    }
}
