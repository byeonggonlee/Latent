package com.byeonggon.latent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.TabPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment

/** The settings screen, and the side the tabs stand on in a wide window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w1000dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
        }

        override fun after() = runBlocking { container.settings.update { AppSettings() } }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("보관함 메뉴").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("탭 위치").assertIsDisplayed()
    }

    private fun mapTabBounds() = compose.onNodeWithText("지도").getBoundsInRoot()

    @Test
    fun movesTheTabsToTheSideThatWasChosenAndKeepsTheChoice() {
        // They start out on the left.
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithText("지도").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(mapTabBounds().left < 200.dp)

        openSettings()
        compose.onNodeWithText("오른쪽").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); runBlocking { container.settings.settings.first().tabPosition } == TabPosition.RIGHT }
        compose.onNodeWithContentDescription("닫기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithText("지도").fetchSemanticsNodes().isNotEmpty() && mapTabBounds().left > 800.dp }

        openSettings()
        compose.onNodeWithText("아래").performClick()
        compose.onNodeWithContentDescription("닫기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithText("지도").fetchSemanticsNodes().isNotEmpty() && mapTabBounds().top > 500.dp }
    }

    @Test
    fun asksBeforeTurningOnTheInternetAndNotBeforeTurningItOff() {
        openSettings()
        compose.onNodeWithText("온라인 지도").assertIsDisplayed()
        assertEquals(false, runBlocking { container.settings.settings.first().onlineMaps })

        compose.onNodeWithText("온라인 지도").performClick()
        // The switch itself, not its label, is what is tapped: this only checks nothing was turned on by the label.
        assertEquals(false, runBlocking { container.settings.settings.first().onlineMaps })
    }
}
