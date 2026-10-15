package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.ui.library.FlagFilter
import com.byeonggon.latent.ui.library.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** A cull: pick and reject from the viewer with the keyboard, from the selection, look at only some, trash the rejected. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagFlowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()
    private val ids = LongArray(3)

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            for (number in 1..3) {
                val photo = File(folder, "DSC0000$number.JPG")
                photo.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt() + number * 0x101010) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ids[number - 1] = runBlocking {
                    mediaDao.insert(MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000L - number, importedAt = 1))
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForPhotos() = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty()
    }

    private fun flagOf(index: Int) = runBlocking { mediaDao.byId(ids[index])!!.flag }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); condition() }

    @Test
    fun picksAndRejectsFromTheViewerWithTheKeyboard() {
        waitForPhotos()
        compose.onNodeWithContentDescription("DSC00001.JPG").performClick()
        waitFor { compose.onAllNodesWithText("편집").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        compose.onRoot().performKeyInput { pressKey(Key.P) }
        waitFor { flagOf(0) == FLAG_PICKED }
        compose.onRoot().performKeyInput { pressKey(Key.X) }
        waitFor { flagOf(0) == FLAG_REJECTED }
        // The same key again takes the flag back.
        compose.onRoot().performKeyInput { pressKey(Key.X) }
        waitFor { flagOf(0) == 0 }
        compose.onRoot().performKeyInput { pressKey(Key.Three) }
        waitFor { runBlocking { mediaDao.byId(ids[0])!!.rating } == 3 }
    }

    @Test
    fun flagsTheSelectionLooksAtOnlyThePickedAndTrashesTheRejected() {
        waitForPhotos()
        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { longClick() }
        compose.onNodeWithText("플래그").performClick()
        compose.onNodeWithText("채택").performClick()
        waitFor { flagOf(0) == FLAG_PICKED }
        compose.onNodeWithContentDescription("DSC00002.JPG").performTouchInput { longClick() }
        compose.onNodeWithText("플래그").performClick()
        compose.onNodeWithText("제외").performClick()
        waitFor { flagOf(1) == FLAG_REJECTED }
        assertEquals(0, flagOf(2))
        // The picked one is marked in the grid.
        compose.onNodeWithContentDescription("채택").assertIsDisplayed()

        val model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        compose.runOnUiThread { model.setFlagFilter(FlagFilter.PICKED) }
        waitFor { compose.onAllNodesWithContentDescription("DSC00002.JPG").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("DSC00001.JPG").assertIsDisplayed()
        compose.runOnUiThread { model.setFlagFilter(FlagFilter.NOT_REJECTED) }
        waitFor { compose.onAllNodesWithContentDescription("DSC00003.JPG").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { model.setFlagFilter(FlagFilter.ALL) }

        // Only the rejected one goes to the trash, after the question.
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("제외한 사진 휴지통으로…").performClick()
        compose.onNodeWithText("제외한 사진을 휴지통에 넣을까요?").assertIsDisplayed()
        compose.onNodeWithText("삭제").performClick()
        waitFor { runBlocking { mediaDao.byId(ids[1])!!.trashedAt } != null }
        assertNull(runBlocking { mediaDao.byId(ids[0])!!.trashedAt })
        assertNull(runBlocking { mediaDao.byId(ids[2])!!.trashedAt })
        assertNotNull(runBlocking { mediaDao.byId(ids[1])!!.trashedFrom })
    }
}
