package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.ui.editor.EditorViewModel
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Levels a photo whose lines lean, and says so when a photo has none to go by. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoLevelFlowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            // Squares turned 4 degrees clockwise, and noise with no lines in it at all.
            addPhoto(File(folder, "DSC00001.JPG"), capturedAt = 2_000) { x, y ->
                val u = (x - 150) * cos(Math.toRadians(4.0)) + (y - 100) * sin(Math.toRadians(4.0))
                val v = -(x - 150) * sin(Math.toRadians(4.0)) + (y - 100) * cos(Math.toRadians(4.0))
                if ((floor(u / 24).toInt() + floor(v / 24).toInt()) % 2 == 0) 224 else 48
            }
            val random = Random(5)
            addPhoto(File(folder, "DSC00002.JPG"), capturedAt = 1_000) { _, _ -> 40 + random.nextInt(180) }
        }

        /** Draws a gray photo; [level] gives the brightness at a place, and is asked for several places in each pixel. */
        private fun addPhoto(file: File, capturedAt: Long, level: (Double, Double) -> Int) {
            val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
            for (y in 0 until 200) for (x in 0 until 300) {
                var sum = 0
                for (sy in 0 until 8) for (sx in 0 until 8) sum += level(x + (sx + 0.5) / 8, y + (sy + 0.5) / 8)
                val gray = sum / 64
                bitmap.setPixel(x, y, (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            runBlocking {
                container.database.mediaDao().insert(
                    MediaItemEntity(path = file.absolutePath, fileName = file.name, kind = MediaKind.PHOTO, sizeBytes = file.length(), capturedAt = capturedAt, importedAt = 1),
                )
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openCropTool(fileName: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(fileName).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("편집").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("편집").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("노출").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
        compose.onNodeWithText("자르기").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("자동 수평").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun turnsAPhotoBackByHowFarItLeans() {
        openCropTool("DSC00001.JPG")
        val editor = ViewModelProvider(compose.activity)[EditorViewModel::class.java]

        compose.onNodeWithText("자동 수평").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); editor.state.value.edit.framing.angle != 0f }

        // Leaning clockwise, so it is turned counter-clockwise: a negative angle.
        assertEquals(-4f, editor.state.value.edit.framing.angle, 0.5f)
    }

    @Test
    fun saysWhenThereIsNothingToLevelBy() {
        openCropTool("DSC00002.JPG")

        compose.onNodeWithText("자동 수평").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("기준이 될 뚜렷한 수평선이나 수직선을 찾지 못했습니다.").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0f, ViewModelProvider(compose.activity)[EditorViewModel::class.java].state.value.edit.framing.angle, 0f)
    }
}
