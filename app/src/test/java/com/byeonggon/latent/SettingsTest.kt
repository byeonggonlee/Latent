package com.byeonggon.latent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.ExportOptions
import com.byeonggon.latent.data.GridSize
import com.byeonggon.latent.data.TabPosition
import com.byeonggon.latent.data.ThemeMode
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
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); runBlocking { container.settings.settings.first().tabPosition } == TabPosition.BOTTOM }
        compose.onNodeWithContentDescription("닫기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithText("지도").fetchSemanticsNodes().isNotEmpty() && mapTabBounds().top > 500.dp }
    }

    @Test
    fun asksBeforeTurningOnTheInternetAndNotBeforeTurningItOff() {
        openSettings()
        compose.onNodeWithText("온라인 지도").performScrollTo().assertIsDisplayed()
        assertEquals(false, runBlocking { container.settings.settings.first().onlineMaps })

        compose.onNodeWithText("온라인 지도").performClick()
        // The switch itself, not its label, is what is tapped: this only checks nothing was turned on by the label.
        assertEquals(false, runBlocking { container.settings.settings.first().onlineMaps })
    }

    @Test
    fun keepsTheChosenThemeGridSizeAndDaysInTheTrash() {
        openSettings()
        compose.onNodeWithText("어둡게").performScrollTo().performClick()
        compose.onNodeWithText("크게").performScrollTo().performClick()
        compose.onNodeWithText("7일").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            runBlocking { container.settings.settings.first() }.let { it.theme == ThemeMode.DARK && it.gridSize == GridSize.LARGE && it.trashKeptDays == 7 }
        }
        // The trash holds photos for a month until told otherwise.
        assertEquals(30, AppSettings().trashKeptDays)
    }

    @Test
    fun choosesHowFinelyCopiesAreCompressedAndWhetherSharingLeavesThePlaceOut() {
        openSettings()
        assertEquals(95, runBlocking { container.settings.settings.first() }.jpegQuality)
        compose.onNodeWithText("공유할 때 위치 빼기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("최고 · 100").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); runBlocking { container.settings.settings.first() }.jpegQuality == 100 }

        assertEquals(false, runBlocking { container.settings.settings.first() }.exportOptions().removeLocation)
        runBlocking { container.settings.update { it.copy(removeLocationWhenSharing = true, jpegQuality = 1000) } }
        // A quality out of range is held to the range.
        assertEquals(ExportOptions(quality = 100, removeLocation = true), runBlocking { container.settings.settings.first() }.exportOptions())
    }

    @Test
    fun turnsOnEveryExifValueAndKeepsTheChoice() {
        openSettings()
        assertEquals(false, runBlocking { container.settings.settings.first() }.allExif)
        compose.onNodeWithText("모든 EXIF 값").performScrollTo().assertIsDisplayed()

        runBlocking { container.settings.update { it.copy(allExif = true) } }
        assertEquals(true, runBlocking { container.settings.settings.first() }.allExif)
    }

    @Test
    fun showsWhichTimeZoneTheCameraIsSetToAndKeepsTheChoice() {
        openSettings()
        // Until one is chosen, the camera is taken to keep the phone's zone.
        compose.onNodeWithText("카메라 시간대").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("휴대전화와 같음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("보관함 사진의 시각 다시 읽기").performScrollTo().assertIsDisplayed()

        runBlocking { container.settings.update { it.copy(cameraZoneId = "Asia/Tokyo") } }
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithText("Tokyo · UTC+09:00", substring = true).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("도쿄 · UTC+09:00", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Asia/Tokyo", runBlocking { container.settings.settings.first() }.cameraZone().id)

        runBlocking { container.settings.update { it.copy(cameraZoneId = null) } }
        assertEquals(null, runBlocking { container.settings.settings.first() }.cameraZoneId)
    }
}
