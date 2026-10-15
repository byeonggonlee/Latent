package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.ui.library.LibraryViewModel
import kotlinx.coroutines.flow.first
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

/** Albums: put photos in one from the selection, look at only those, take one out, delete the album. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumFlowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
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
                    container.database.mediaDao().insert(
                        MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000L - number, importedAt = 1),
                    )
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForPhotos() = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty()
    }

    private fun members(albumId: Long) = runBlocking { container.database.albumDao().observeMembers(albumId).first() }

    @Test
    fun putsPhotosInAnAlbumShowsOnlyThemAndTakesThemOut() {
        waitForPhotos()
        val model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        compose.runOnUiThread { model.createAlbum("여행", withSelected = false) }
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); runBlocking { container.database.albumDao().observeAll().first() }.isNotEmpty() }
        val albumId = runBlocking { container.database.albumDao().observeAll().first() }.single().album.id

        // Pick two photos and put them in the album.
        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("DSC00002.JPG").performClick()
        compose.onNodeWithText("앨범에 추가").performClick()
        compose.onNodeWithText("여행 (0)").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); members(albumId).size == 2 }
        assertEquals(setOf(ids[0], ids[1]), members(albumId).toSet())

        // Looking at the album shows those two and not the third.
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("앨범…").performClick()
        compose.onNodeWithText("여행 (2)").assertIsDisplayed()
        compose.onNodeWithText("여행 (2)").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithContentDescription("DSC00003.JPG").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("DSC00001.JPG").assertIsDisplayed()
        compose.onNodeWithText("여행").assertIsDisplayed()

        // One of them leaves the album, and so the view of it.
        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { longClick() }
        compose.onNodeWithText("앨범에서 빼기").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); members(albumId) == listOf(ids[1]) }
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isEmpty() }

        // Deleting the album brings the whole library back; the photos were never in danger.
        compose.onNodeWithContentDescription("보관함 메뉴").performClick()
        compose.onNodeWithText("앨범…").performClick()
        compose.onNodeWithContentDescription("앨범 삭제").performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); compose.onAllNodesWithContentDescription("DSC00003.JPG").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, runBlocking { container.database.mediaDao().all() }.size)
        compose.onAllNodesWithText("앨범에서 빼기").assertCountEquals(0)
    }
}
