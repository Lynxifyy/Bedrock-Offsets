package com.chimerateam.offsetextractor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies that the activity launches and renders its title.
 *
 * Runs on the JVM through Robolectric so it is part of `./gradlew test`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndShowsTitle() {
        composeTestRule.onNodeWithText("Bedrock Offset Extractor").assertIsDisplayed()
    }
}
