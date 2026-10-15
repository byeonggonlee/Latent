package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
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

/** What a mouse and a keyboard do: a right click on a photo, and plus, minus and 0 to zoom. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesktopInputTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            val photo = File(folder, "DSC00001.JPG")
            photo.outputStream().use { Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF607080.toInt()) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            runBlocking {
                container.database.mediaDao().insert(
                    MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000, importedAt = 1),
                )
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForPhoto() = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun offersAMenuOnARightClickThatPicksThePhoto() {
        waitForPhoto()
        compose.onNodeWithContentDescription("DSC00001.JPG").performMouseInput { rightClick() }

        compose.onNodeWithText("열기").assertIsDisplayed()
        // Share and Delete are in the menu, and in the bar that the picked photo brings up.
        check(compose.onAllNodesWithText("공유").fetchSemanticsNodes().size == 2)
        check(compose.onAllNodesWithText("삭제").fetchSemanticsNodes().size == 2)
        // The photo is picked by the click, so the menu offers to let go of it.
        compose.onNodeWithText("선택 해제").assertIsDisplayed()
        // A right click does not open the photo.
        compose.onAllNodesWithText("편집").assertCountEquals0()
        compose.onNodeWithText("열기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("편집").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun zoomsTheOpenPhotoWithPlusMinusAndZero() {
        waitForPhoto()
        compose.onNodeWithContentDescription("DSC00001.JPG").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("편집").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        compose.onRoot().performKeyInput { pressKey(Key.Equals) }
        compose.onNodeWithText("125%").assertIsDisplayed()
        compose.onRoot().performKeyInput { pressKey(Key.Equals) }
        compose.onNodeWithText("156%").assertIsDisplayed()
        compose.onRoot().performKeyInput { pressKey(Key.Minus) }
        compose.onNodeWithText("125%").assertIsDisplayed()
        compose.onRoot().performKeyInput { pressKey(Key.Zero) }
        compose.onAllNodesWithText("125%").assertCountEquals0()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() = this.also {
    check(it.fetchSemanticsNodes().isEmpty()) { "expected no nodes but found ${it.fetchSemanticsNodes().size}" }
}
