package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
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

/** Setting photos side by side to choose between them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompareFlowTest {
    private val ids = LongArray(3)
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            for (number in 1..3) {
                val photo = File(folder, "DSC0000$number.JPG")
                val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt() + number * 0x101010) }
                photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                val item = MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000L - number, importedAt = 1)
                ids[number - 1] = runBlocking { mediaDao.insert(item) }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 15_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun setsTwoPhotosSideBySideAndPicksAndRatesThemThere() {
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("DSC00001.JPG").performTouchInput { longClick() }
        // One photo alone has nothing to be compared with.
        waitForText("비교")
        assertTrue(compose.onAllNodesWithText("비교").fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.Disabled) != null)
        compose.onNodeWithContentDescription("DSC00003.JPG").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("비교").fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithText("비교").performClick()

        // Both are there, with their names; the one left out is not.
        waitForText("DSC00001.JPG")
        waitForText("DSC00003.JPG")
        assertTrue(compose.onAllNodesWithText("DSC00002.JPG").fetchSemanticsNodes().isEmpty())

        // The first one is picked, the second given stars.
        compose.onAllNodesWithContentDescription("채택")[0].performClick()
        compose.waitUntil(timeoutMillis = 15_000) { compose.waitForIdle(); runBlocking { mediaDao.byId(ids[0])!!.flag } == FLAG_PICKED }
        compose.onAllNodesWithContentDescription("별점 4")[1].performClick()
        compose.waitUntil(timeoutMillis = 15_000) { compose.waitForIdle(); runBlocking { mediaDao.byId(ids[2])!!.rating } == 4 }
        assertEquals(0, runBlocking { mediaDao.byId(ids[0])!!.rating })

        // Back to the library, with the photo that was left out in it.
        compose.onNodeWithContentDescription("닫기").performClick()
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithContentDescription("DSC00002.JPG").fetchSemanticsNodes().isNotEmpty() }
    }
}
