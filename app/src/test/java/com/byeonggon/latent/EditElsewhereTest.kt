package com.byeonggon.latent

import android.content.Intent
import android.graphics.Bitmap
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/** "Edit in another app" offers a copy of the photo to editors such as Snapseed, to read and not to write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditElsewhereTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            val photo = File(folder, "DSC00001.JPG")
            photo.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt()) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            runBlocking {
                container.database.mediaDao().insert(
                    MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000, importedAt = 1),
                )
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun handsACopyToAnEditorWithoutLettingItWrite() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("DSC00001.JPG").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("편집").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("더 보기").performClick()
        compose.onNodeWithText("다른 앱에서 편집…").performClick()

        val application = RuntimeEnvironment.getApplication()
        val shadow = Shadows.shadowOf(application)
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); shadow.peekNextStartedActivity() != null }
        val chooser = shadow.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val edit = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_EDIT, edit.action)
        assertEquals("image/jpeg", edit.type)
        assertNotNull(edit.data)
        assertTrue(edit.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, edit.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // The copy is in the app's cache, not the library.
        assertTrue(edit.data.toString().contains("share"))
    }
}
