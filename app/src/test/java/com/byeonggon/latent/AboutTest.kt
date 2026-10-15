package com.byeonggon.latent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** The library's menu says what the app is, with its version and where to read its privacy policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutTest {
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
    fun showsTheVersionAndWhereThePrivacyPolicyIs() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("보관함 메뉴").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("Latent 정보").performClick()

        compose.onNodeWithText("버전", substring = true).assertIsDisplayed()
        compose.onNodeWithText("개인정보 처리방침").assertIsDisplayed()
        compose.onNodeWithText("소스 코드와 라이선스").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.onAllNodesWithText("개인정보 처리방침").fetchSemanticsNodes().let { check(it.isEmpty()) }
    }
}
