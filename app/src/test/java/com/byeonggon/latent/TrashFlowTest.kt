package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/**
 * The trash deletes photos for good only once the person has confirmed it: emptying it all, or the photos picked out in
 * it. Cancelling leaves every file where it was, and nothing outside the trash is ever touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashFlowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()
    private val ids = LongArray(3)
    private lateinit var kept: File

    // Two photos are in the trash and one in the library before the activity starts.
    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-21").apply { mkdirs() }
            for (number in 1..3) {
                val photo = File(folder, "DSC0000$number.JPG")
                val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt() + number * 0x101010) }
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ids[number - 1] = runBlocking {
                    mediaDao.insert(MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000L - number, importedAt = 1))
                }
            }
            kept = File(folder, "DSC00003.JPG")
            runBlocking { container.trash.throwAway(listOf(mediaDao.byId(ids[0])!!, mediaDao.byId(ids[1])!!)) }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForPhoto(fileName: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openTrash() {
        waitForPhoto("DSC00003.JPG")
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        waitForText("휴지통 2")
        compose.onNodeWithText("휴지통 2").performClick()
        waitForPhoto("DSC00001.JPG")
        waitForPhoto("DSC00002.JPG")
    }

    /** Where the photo with [id] is now, in the trash or out of it; null once it is deleted for good. */
    private fun fileOf(id: Long): File? = runBlocking { mediaDao.byId(id) }?.let { File(it.path) }

    @Test
    fun emptiesTheTrashOnlyOnceAskedAndLeavesTheLibraryAlone() {
        val trashed = listOf(fileOf(ids[0])!!, fileOf(ids[1])!!)
        assertTrue(trashed.all(File::exists))
        openTrash()

        compose.onNodeWithText("비우기").performClick()
        compose.onNodeWithText("휴지통을 비울까요?").assertIsDisplayed()
        compose.onNodeWithText("2장이 완전히 삭제되며 되돌릴 수 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithText("휴지통을 비울까요?").fetchSemanticsNodes().size)
        assertTrue(trashed.all(File::exists))
        assertNotNull(runBlocking { mediaDao.byId(ids[0]) })

        compose.onNodeWithText("비우기").performClick()
        compose.onNodeWithText("삭제").performClick()
        waitForText("2장을 완전히 삭제했습니다.")

        compose.waitUntil(timeoutMillis = 10_000) { trashed.none(File::exists) }
        assertNull(runBlocking { mediaDao.byId(ids[0]) })
        assertNull(runBlocking { mediaDao.byId(ids[1]) })
        // The photo in the library is where it was.
        assertTrue(kept.exists())
        assertEquals(kept.path, fileOf(ids[2])!!.path)
        compose.onNodeWithText("비우기").assertIsNotEnabled()
    }

    @Test
    fun deletesForGoodOnlyThePhotoPickedOutAndOnlyOnceAsked() {
        val first = fileOf(ids[0])!!
        val second = fileOf(ids[1])!!
        openTrash()

        compose.onNodeWithContentDescription("DSC00001.JPG").performClick()
        waitForText("✕ 1장 선택")
        compose.onNodeWithText("완전히 삭제").performClick()
        compose.onNodeWithText("완전히 삭제할까요?").assertIsDisplayed()
        compose.onNodeWithText("1장이 삭제되며 되돌릴 수 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.waitForIdle()
        assertTrue(first.exists())

        compose.onNodeWithText("완전히 삭제").performClick()
        compose.onNodeWithText("삭제").performClick()
        waitForText("1장을 완전히 삭제했습니다.")

        compose.waitUntil(timeoutMillis = 10_000) { !first.exists() }
        assertNull(runBlocking { mediaDao.byId(ids[0]) })
        assertTrue(second.exists())
        assertNotNull(runBlocking { mediaDao.byId(ids[1])!!.trashedAt })
        assertTrue(kept.exists())
    }

    @Test
    fun putsThePhotosPickedOutBackWhereTheyWere() {
        val was = File(kept.parentFile, "DSC00002.JPG")
        assertFalse(was.exists())
        openTrash()

        compose.onNodeWithContentDescription("DSC00002.JPG").performClick()
        waitForText("✕ 1장 선택")
        compose.onNodeWithText("되돌리기").performClick()
        waitForText("1장을 되돌렸습니다.")

        compose.waitUntil(timeoutMillis = 10_000) { was.exists() }
        val back = runBlocking { mediaDao.byId(ids[1])!! }
        assertEquals(was.path, back.path)
        assertNull(back.trashedAt)
        assertNotNull(runBlocking { mediaDao.byId(ids[0])!!.trashedAt })
    }
}
