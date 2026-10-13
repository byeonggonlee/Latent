package com.byeonggon.cameramanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment

/** Launches the real activity and walks through every tab, to catch wiring mistakes that only show at runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    // Robolectric starts without shared storage, which the storage-permission check cannot cope with.
    @get:Rule(order = 0)
    val sharedStorage = object : ExternalResource() {
        override fun before() {
            ShadowEnvironment.addExternalDir("shared")
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyTabRenders() {
        // The library is filled from the database, off the test's own dispatcher.
        val emptyLibrary = "아직 가져온 사진이 없습니다"
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(emptyLibrary, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(emptyLibrary, substring = true).assertIsDisplayed()

        compose.onNodeWithText("동기화").performClick()
        compose.onNodeWithText("카메라 동기화").assertIsDisplayed()
        compose.onNodeWithText("연결된 카메라가 없습니다.").assertIsDisplayed()

        compose.onNodeWithText("위치").performClick()
        compose.onNodeWithText("위치 기록").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("저장된 위치 0개", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
