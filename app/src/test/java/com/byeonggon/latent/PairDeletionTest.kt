package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.raw.SyntheticArw
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * A raw file and the JPEG the camera wrote with it, shown as one: deleting the one shown takes the other along into
 * the trash, from the viewer and from a selection; shown apart, each goes alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairDeletionTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()
    private lateinit var jpeg: File
    private lateinit var raw: File
    private lateinit var alone: File
    private val ids = LongArray(3)

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            jpeg = File(folder, "DSC00001.JPG")
            alone = File(folder, "DSC00002.JPG")
            for (file in listOf(jpeg, alone)) {
                file.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            raw = File(folder, "DSC00001.ARW").apply { writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700)) }
            runBlocking {
                for ((index, entry) in listOf(Triple(jpeg, MediaKind.PHOTO, 9_000L), Triple(raw, MediaKind.RAW, 9_000L), Triple(alone, MediaKind.PHOTO, 8_000L)).withIndex()) {
                    val (file, kind, at) = entry
                    ids[index] = mediaDao.insert(MediaItemEntity(path = file.absolutePath, fileName = file.name, kind = kind, sizeBytes = file.length(), capturedAt = at, importedAt = 1))
                }
            }
        }

        override fun after() = runBlocking { container.settings.update { AppSettings() } }
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

    private fun trashed(index: Int) = runBlocking { mediaDao.byId(ids[index])!!.trashedAt } != null

    private fun waitUntilTrashed(index: Int) = compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); trashed(index) }

    @Test
    fun takesTheRawFileAlongFromTheViewer() {
        waitForText("RAW+J")
        compose.onNodeWithContentDescription(jpeg.name).performClick()
        waitForText("편집")

        compose.onNodeWithText("삭제").performClick()

        waitUntilTrashed(0)
        waitUntilTrashed(1)
        assertTrue(!jpeg.exists() && !raw.exists())
        assertTrue(File(runBlocking { mediaDao.byId(ids[1])!!.path }).exists())
        assertNull(runBlocking { mediaDao.byId(ids[2])!!.trashedAt })
    }

    @Test
    fun takesTheRawFileAlongFromASelection() {
        waitForText("RAW+J")
        compose.onNodeWithContentDescription(jpeg.name).performTouchInput { longClick() }
        compose.selectionAction("삭제").performClick()

        waitUntilTrashed(0)
        waitUntilTrashed(1)
        assertNull(runBlocking { mediaDao.byId(ids[2])!!.trashedAt })
    }

    @Test
    fun deletesOnlyTheFileShownWhenTheyAreShownApart() {
        waitForPhoto(jpeg.name)
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("RAW+JPEG 묶어 보기").performClick()
        waitForPhoto(raw.name)

        compose.onNodeWithContentDescription(jpeg.name).performClick()
        waitForText("편집")
        compose.onNodeWithText("삭제").performClick()

        waitUntilTrashed(0)
        compose.waitForIdle()
        assertEquals(false, trashed(1))
        assertTrue(raw.exists())
        assertNotNull(runBlocking { mediaDao.byId(ids[1]) })
        assertEquals(0, compose.onAllNodesWithText("휴지통 2").fetchSemanticsNodes().size)
    }
}
