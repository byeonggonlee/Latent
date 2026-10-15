package com.byeonggon.latent

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.runBlocking
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

/** Searching the library by how the photos were taken. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FindFlowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            // Two by the camera at low ISO, one by a phone at a high one.
            for (number in 1..3) {
                val photo = File(folder, "DSC0000$number.JPG")
                val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt() + number * 0x101010) }
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ExifInterface(photo).apply {
                    setAttribute(ExifInterface.TAG_MODEL, if (number < 3) "ZV-E10" else "SM-S928N")
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, if (number < 3) "100" else "3200")
                    saveAttributes()
                }
                val item = MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000L - number, importedAt = 1)
                runBlocking { container.database.mediaDao().insert(item) }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitUntilShown(text: String) = compose.waitUntil(timeoutMillis = 15_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun photosShown() = listOf("DSC00001.JPG", "DSC00002.JPG", "DSC00003.JPG").filter {
        compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForPhotos(vararg names: String) = compose.waitUntil(timeoutMillis = 15_000) {
        compose.waitForIdle()
        photosShown() == names.toList()
    }

    private fun openFind() {
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithContentDescription("찾기").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("찾기").performClick()
    }

    @Test
    fun narrowsTheLibraryByCameraAndIsoAndSaysWhenNothingMatches() {
        waitForPhotos("DSC00001.JPG", "DSC00002.JPG", "DSC00003.JPG")
        openFind()
        // The cameras are read out of the files in the background; they are offered once they are.
        waitUntilShown("ZV-E10 (2)")
        waitUntilShown("SM-S928N (1)")

        compose.onNodeWithText("SM-S928N (1)").performClick()
        waitForPhotos("DSC00003.JPG")

        compose.onNodeWithText("ZV-E10 (2)").performClick()
        waitForPhotos("DSC00001.JPG", "DSC00002.JPG", "DSC00003.JPG")
        compose.onNodeWithText("SM-S928N (1)").performClick()
        waitForPhotos("DSC00001.JPG", "DSC00002.JPG")

        compose.onNodeWithText("6400 초과").performScrollTo().performClick()
        waitUntilShown("조건에 맞는 사진이 없습니다.")
        assertTrue(photosShown().isEmpty())

        compose.onNodeWithText("지우기").performClick()
        waitForPhotos("DSC00001.JPG", "DSC00002.JPG", "DSC00003.JPG")
    }

    @Test
    fun findsByPartOfTheFileName() {
        waitForPhotos("DSC00001.JPG", "DSC00002.JPG", "DSC00003.JPG")
        openFind()
        waitUntilShown("파일 이름의 일부")

        compose.onNodeWithText("파일 이름의 일부").performTextInput("0002")
        waitForPhotos("DSC00002.JPG")
        // With a condition set, the library says so beside its filters.
        assertTrue(compose.onAllNodesWithText("찾기 · 조건 1개").fetchSemanticsNodes().isNotEmpty())
    }
}
