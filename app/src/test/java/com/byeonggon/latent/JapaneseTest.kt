package com.byeonggon.latent

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** The app in Japanese on a Japanese phone: every tab shows its own words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JapaneseTest {
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
    fun showsEveryTabInJapanese() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("まだ写真がありません", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The large title and the tab.
        compose.onAllNodesWithText("ライブラリ").assertCountEquals(2)

        compose.onNodeWithText("取り込み").performClick()
        // The large title and the tab.
        compose.onAllNodesWithText("取り込み").assertCountEquals(2)
        compose.onNodeWithText("カメラがつながっていません。").assertIsDisplayed()

        compose.onNodeWithText("地図").performClick()
        compose.onNodeWithText("写真を地図で見る").assertIsDisplayed()

        compose.onNodeWithText("取り込み").performClick()
        compose.onNodeWithText("カメラの時計、タイムライン、位置情報の追加").performScrollTo().performClick()
        compose.onNodeWithText("位置情報の記録").assertIsDisplayed()
    }

    @Test
    fun countsWithoutPlurals() {
        // Japanese has one form for every number: a count reads the same for one as for many.
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals("写真 1 枚をゴミ箱に移動しました。", resources.getQuantityString(R.plurals.trashed_count, 1, 1))
        assertEquals("写真 12 枚をゴミ箱に移動しました。", resources.getQuantityString(R.plurals.trashed_count, 12, 12))
    }
}
