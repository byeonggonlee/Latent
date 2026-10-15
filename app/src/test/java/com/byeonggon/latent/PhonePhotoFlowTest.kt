package com.byeonggon.latent

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** A photo that another app made, opened from the library: edited and put back without the file ever changing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePhotoFlowTest {
    private lateinit var photo: File
    private var photoId = 0L
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            grantLibraryAccess()
            photo = File(Environment.getExternalStorageDirectory(), "DCIM/Camera/20261010_120000.jpg").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until 64) for (x in 0 until 96) bitmap.setPixel(x, y, (0xFF shl 24) or ((60 + x + y / 2) * 0x010101))
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            photoId = runBlocking {
                // The folder is one the person chose, or the library would not keep the photo.
                container.settings.update { it.copy(phoneFolders = setOf("DCIM/Camera/")) }
                mediaDao.insert(
                    MediaItemEntity(
                        path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(),
                        capturedAt = 5_000, importedAt = 1, origin = MediaOrigin.PHONE,
                    ),
                )
            }
        }
    }

    @get:Rule(order = 2)
    val cleanUp = object : ExternalResource() {
        // The settings live in one store for the whole test run.
        override fun after() = runBlocking { container.settings.update { com.byeonggon.latent.data.AppSettings() } }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun editsAndRestoresWithoutChangingTheFile() {
        val original = photo.readBytes()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription(photo.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(photo.name).performClick()
        waitForText("편집")
        compose.onNodeWithText("편집").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("노출").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
        compose.onNodeWithContentDescription("노출").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("완료").performClick()

        waitForText("원본으로 되돌리기")
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            container.editSaver.saving.value.isEmpty() && runBlocking { mediaDao.byId(photoId)!!.edit } != null
        }
        // Kept as settings: the file is as it was, with no copy of it set aside either.
        assertArrayEquals(original, photo.readBytes())
        assertEquals(PhotoEdit(Adjustments(exposure = 1f)), PhotoEdit.fromJson(runBlocking { mediaDao.byId(photoId)!!.edit }))
        assertFalse(File(container.storage.root, ".originals").exists())

        compose.onNodeWithText("원본으로 되돌리기").performClick()
        compose.onNodeWithText("되돌리기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); runBlocking { mediaDao.byId(photoId)!!.edit } == null }
        assertArrayEquals(original, photo.readBytes())
        assertNull(runBlocking { mediaDao.byId(photoId)!!.edit })
    }
}
