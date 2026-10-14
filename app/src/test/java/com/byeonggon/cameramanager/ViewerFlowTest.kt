package com.byeonggon.cameramanager

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import kotlinx.coroutines.runBlocking
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
import java.io.File

/** Looks through the library the way a gallery is used: open a photo, swipe to the next, give it stars. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerFlowTest {
    private val ids = LongArray(3)
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()

    // Runs before the activity starts, so the library already holds the photos when it first shows.
    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            for (number in 1..3) {
                val photo = File(folder, "DSC0000$number.JPG")
                val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt() + number * 0x101010) }
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ExifInterface(photo).apply {
                    setAttribute(ExifInterface.TAG_MODEL, "ZV-E10")
                    setAttribute(ExifInterface.TAG_F_NUMBER, "4")
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, (100 * number).toString())
                    saveAttributes()
                }
                // Listed newest first: number 1 comes first.
                val item = MediaItemEntity(
                    path = photo.absolutePath,
                    fileName = photo.name,
                    kind = MediaKind.PHOTO,
                    sizeBytes = photo.length(),
                    capturedAt = 10_000L - number,
                    importedAt = 1,
                )
                ids[number - 1] = runBlocking { mediaDao.insert(item) }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun open(fileName: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(fileName).performClick()
        waitForText("보정")
    }

    @Test
    fun swipesFromOnePhotoToTheNextAndBack() {
        open("DSC00001.JPG")
        waitForText("ISO 100")

        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { swipeLeft() }
        waitForText("DSC00002.JPG")
        waitForText("ISO 200")

        compose.onNodeWithContentDescription("DSC00002.JPG").performTouchInput { swipeLeft() }
        waitForText("ISO 300")
        compose.onNodeWithContentDescription("DSC00003.JPG").performTouchInput { swipeRight() }
        waitForText("ISO 200")
        compose.onNodeWithText("ZV-E10 · f/4 · ISO 200").assertIsDisplayed()
    }

    @Test
    fun givesAPhotoStarsAndNarrowsTheLibraryDownByThem() {
        open("DSC00002.JPG")
        compose.onNodeWithContentDescription("별점 4").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { mediaDao.byId(ids[1])!!.rating } == 4 }
        // The same star again takes the stars away; another one changes them.
        compose.onNodeWithContentDescription("별점 4").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { mediaDao.byId(ids[1])!!.rating } == 0 }
        compose.onNodeWithContentDescription("별점 3").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { mediaDao.byId(ids[1])!!.rating } == 3 }
        compose.onNodeWithContentDescription("닫기").performClick()

        compose.onNodeWithText("별점").performClick()
        compose.onNodeWithText("★★★ 이상").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("DSC00002.JPG").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("DSC00003.JPG").assertCountEquals(0)
        assertEquals(0, runBlocking { mediaDao.byId(ids[0])!!.rating })

        // In the viewer, only the photos that are listed can be swiped to: here that is the one.
        compose.onNodeWithContentDescription("DSC00002.JPG").performClick()
        waitForText("보정")
        compose.onNodeWithContentDescription("DSC00002.JPG").performTouchInput { swipeLeft() }
        compose.onNodeWithText("DSC00002.JPG").assertIsDisplayed()
    }

    @Test
    fun showsARawFileAndItsJpegAsOneAndSwitchesBetweenThem() {
        val folder = File(container.storage.root, "2026/2026-10-08")
        val raw = File(folder, "DSC00001.ARW").apply { writeBytes(com.byeonggon.cameramanager.raw.SyntheticArw.file(red = 600, green = 900, blue = 700)) }
        runBlocking {
            mediaDao.insert(MediaItemEntity(path = raw.absolutePath, fileName = raw.name, kind = MediaKind.RAW, sizeBytes = raw.length(), capturedAt = 9_999, importedAt = 1))
        }

        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("RAW+J").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("DSC00001.ARW").assertCountEquals(0)
        open("DSC00001.JPG")
        compose.onNodeWithText("RAW 보기").performClick()
        waitForText("JPEG 보기")
        compose.onNodeWithText("DSC00001.ARW").assertIsDisplayed()
        compose.onNodeWithText("JPEG 보기").performClick()
        waitForText("RAW 보기")

        // Unstacked, both are listed.
        compose.onNodeWithContentDescription("닫기").performClick()
        compose.onNodeWithText("RAW+JPEG 묶기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("DSC00001.ARW").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
