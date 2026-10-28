package com.byeonggon.latent

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.byeonggon.latent.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment

/** The map starts out off: it says why it would use the internet, asks first, and asks nothing of the internet until told to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapTabTest {
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

    @After
    fun leaveOff() = runBlocking { container.settings.update { AppSettings() } }

    @Test
    fun explainsAsksAndStaysOffUntilTheAnswerIsYes() {
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("온라인 지도가 꺼져 있습니다").assertIsDisplayed()
        assertEquals(false, runBlocking { container.settings.settings.first().onlineMaps })

        compose.onNodeWithText("온라인 지도 켜기").performClick()
        compose.onNodeWithText("지도를 위해 인터넷을 사용할까요?").assertIsDisplayed()
        // Saying no leaves everything as it was.
        compose.onNodeWithText("취소").performClick()
        compose.onAllNodesWithText("지도를 위해 인터넷을 사용할까요?").assertCountEquals(0)
        compose.onNodeWithText("온라인 지도가 꺼져 있습니다").assertIsDisplayed()
        assertEquals(false, runBlocking { container.settings.settings.first().onlineMaps })
    }
}
