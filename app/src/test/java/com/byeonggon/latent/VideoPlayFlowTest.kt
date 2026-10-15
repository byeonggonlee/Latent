package com.byeonggon.latent

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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

/** A video is played in the viewer; one that this phone cannot play is handed to another app instead of getting stuck. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoPlayFlowTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            // Not a video, whatever its name says: the player has to give up on it.
            val video = File(folder, "C0001.MP4").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
            runBlocking {
                container.database.mediaDao().insert(
                    MediaItemEntity(path = video.absolutePath, fileName = video.name, kind = MediaKind.VIDEO, sizeBytes = video.length(), capturedAt = 10_000, importedAt = 1),
                )
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsPlayingInTheViewerAndLeavesItBeIfTheVideoCannotBePlayed() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("C0001.MP4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("C0001.MP4").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription("재생").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("재생").performClick()

        // The player takes the button's place and, finding nothing it can play, gives it back.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("재생").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithContentDescription("재생").fetchSemanticsNodes().isNotEmpty())
    }
}
