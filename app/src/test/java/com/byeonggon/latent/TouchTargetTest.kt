package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
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

/** Everything that can be tapped is at least as big as a fingertip needs, on each screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchTargetTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            val photo = File(folder, "DSC00001.JPG")
            photo.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF607080.toInt()) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            runBlocking {
                container.database.mediaDao().insert(
                    MediaItemEntity(path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = 10_000, importedAt = 1),
                )
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    /** What on the screen can be tapped but is smaller than 48 dp either way, described for a failure message. */
    private fun tooSmall(): List<String> {
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            // Material components reach 48 dp by taking in the space around them: the touch bounds show that.
            val bounds = node.touchBoundsInRoot
            val enabled = node.config.getOrNull(SemanticsProperties.Disabled) == null
            // Something scrolled out of a row or a list has no place yet, and so no size.
            enabled && !bounds.isEmpty && (bounds.width / density < 47.5f || bounds.height / density < 47.5f)
        }.map { node ->
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "?"
            "$label ${node.touchBoundsInRoot.width / density}x${node.touchBoundsInRoot.height / density}dp"
        }
    }

    @Test
    fun everyScreenHasBigEnoughTargets() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("DSC00001.JPG").fetchSemanticsNodes().isNotEmpty() }
        val found = linkedMapOf<String, List<String>>()
        found["library"] = tooSmall()
        compose.onNodeWithText("카메라").performClick()
        found["camera"] = tooSmall()
        compose.onNodeWithText("지도").performClick()
        found["map"] = tooSmall()
        compose.onNodeWithText("위치").performClick()
        found["location"] = tooSmall()
        compose.onNodeWithText("보관함").performClick()
        compose.onNodeWithContentDescription("DSC00001.JPG").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("편집").fetchSemanticsNodes().isNotEmpty() }
        found["viewer"] = tooSmall()
        compose.onNodeWithText("편집").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("노출").fetchSemanticsNodes().isNotEmpty() }
        found["editor"] = tooSmall()
        for (tab in listOf("필터", "자르기", "색상", "효과", "부분", "잡티", "세부")) {
            compose.onAllNodesWithText(tab).fetchSemanticsNodes().firstOrNull() ?: continue
            compose.onNodeWithText(tab).performClick()
            found["editor/$tab"] = tooSmall()
        }
        val report = found.filterValues { it.isNotEmpty() }.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        assertEquals(report, "", report)
    }
}
