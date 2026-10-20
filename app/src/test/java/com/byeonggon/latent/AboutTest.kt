package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

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

    @Test
    fun staysUpWhenTheLibraryFillsUnderIt() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("보관함 메뉴").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("Latent 정보").performClick()
        compose.onNodeWithText("개인정보 처리방침").assertIsDisplayed()

        // A photo arrives, and the library goes from its empty message to the grid: the title is built again, the dialog is not.
        val container = RuntimeEnvironment.getApplication().appContainer
        val photo = File(container.storage.root, "2026/2026-10-08/DSC00001.JPG").apply { parentFile!!.mkdirs() }
        photo.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        runBlocking {
            container.database.mediaDao().insert(MediaItemEntity(path = photo.path, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 1, importedAt = 1))
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("개인정보 처리방침").assertIsDisplayed()
    }
}
