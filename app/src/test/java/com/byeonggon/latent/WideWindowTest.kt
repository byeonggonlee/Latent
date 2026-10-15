package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.PhotoEdit
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
import java.io.File

/** The app in a laptop's window: tabs down the side, panels beside the picture, and a keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class WideWindowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val ids = LongArray(2)

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            for (number in 1..2) {
                val photo = File(folder, "DSC0000$number.JPG")
                val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                // Dark, so that auto has something to do.
                for (x in 0 until 96) for (y in 0 until 64) bitmap.setPixel(x, y, (0xFF shl 24) or ((10 + x / 3) * 0x010101))
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ExifInterface(photo).apply {
                    setAttribute(ExifInterface.TAG_MODEL, "ZV-E10")
                    setAttribute(ExifInterface.TAG_F_NUMBER, "4")
                    saveAttributes()
                }
                val item = MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10L - number, importedAt = 1)
                ids[number - 1] = runBlocking { container.database.mediaDao().insert(item) }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** The editor's controls come alive once the picture has been read and drawn. */
    private fun waitForEditor() = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithContentDescription("노출").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
    }

    private fun open(fileName: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(fileName).performClick()
        waitForText("편집")
    }

    @Test
    fun putsTheTabsDownTheSideAndThePanelsBesideThePicture() {
        compose.onNodeWithText("카메라").assertIsDisplayed()
        open("DSC00001.JPG")

        // The information stands to the right of the photo, not under it.
        val photo = compose.onNodeWithContentDescription("DSC00001.JPG").fetchSemanticsNode().boundsInRoot
        val edit = compose.onNodeWithText("편집").fetchSemanticsNode().boundsInRoot
        assertTrue("photo $photo, panel $edit", edit.left >= photo.right - 1f)

        compose.onNodeWithText("편집").performClick()
        waitForEditor()
        val picture = compose.onNodeWithContentDescription("DSC00001.JPG").fetchSemanticsNode().boundsInRoot
        val slider = compose.onNodeWithContentDescription("노출").fetchSemanticsNode().boundsInRoot
        assertTrue("picture $picture, slider $slider", slider.left >= picture.right - 1f)
    }

    @Test
    fun listsEveryExifValueBesideThePhotoOnlyWhenTheSettingIsOn() {
        try {
            open("DSC00001.JPG")
            compose.onNodeWithText("ZV-E10 · f/4").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("FNumber").fetchSemanticsNodes().isEmpty())

            runBlocking { container.settings.update { it.copy(allExif = true) } }
            waitForText("FNumber")
            // Under the name the standard gives it, in the part it belongs to.
            assertTrue(compose.onAllNodesWithText("카메라와 렌즈").fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithText("LensModel").fetchSemanticsNodes().isEmpty())
        } finally {
            runBlocking { container.settings.update { AppSettings() } }
        }
    }

    @Test
    fun turnsPagesAndDeletesWithTheKeyboard() {
        open("DSC00001.JPG")

        compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionRight) }
        waitForText("DSC00002.JPG")
        compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Delete) }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            runBlocking { container.database.mediaDao().byId(ids[1])!!.trashedAt } != null
        }
    }

    @Test
    fun picksToolsAndShowsTheOriginalWithTheKeyboard() {
        open("DSC00001.JPG")
        compose.onNodeWithText("편집").performClick()
        waitForEditor()
        // Each tool shows its key beside the picture.
        compose.onNodeWithText("C").assertIsDisplayed()

        compose.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.C) }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("색온도").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodes(isRoot())[0].performKeyInput { keyDown(Key.Backslash) }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("원본").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(isRoot())[0].performKeyInput { keyUp(Key.Backslash) }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("원본").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun undoesWithTheKeyboardInTheEditor() {
        open("DSC00001.JPG")
        compose.onNodeWithText("편집").performClick()
        waitForEditor()
        compose.onNodeWithText("자동").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("완료").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }

        compose.onAllNodes(isRoot())[0].performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("완료").fetchSemanticsNodes().all { it.config.getOrNull(SemanticsProperties.Disabled) != null }
        }
        compose.onAllNodes(isRoot())[0].performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        assertEquals(null, runBlocking { container.database.mediaDao().byId(ids[0])!!.edit }?.let(PhotoEdit::fromJson))
    }
}
