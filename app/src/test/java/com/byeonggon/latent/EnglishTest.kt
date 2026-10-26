package com.byeonggon.latent

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment

/** The app in English, its default language: every tab shows its own words, not Korean ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishTest {
    @get:Rule(order = 0)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsEveryTabInEnglish() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("No photos yet", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The large title and the tab.
        compose.onAllNodesWithText("Library").assertCountEquals(2)

        compose.onNodeWithText("Import").performClick()
        // The large title and the tab.
        compose.onAllNodesWithText("Import").assertCountEquals(2)
        compose.onNodeWithText("No camera is connected.").assertIsDisplayed()

        // Location is no tab of its own: it opens from the import tab, and goes back there.
        compose.onNodeWithText("Camera clock, Timeline and adding locations").performScrollTo().performClick()
        compose.onNodeWithText("Location").assertIsDisplayed()
        compose.onNodeWithText("Record every").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("No camera is connected.").assertIsDisplayed()
    }
}
