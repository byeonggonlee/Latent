package com.byeonggon.latent

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.byeonggon.latent.data.db.FLAG_PICKED
import com.byeonggon.latent.data.db.FLAG_REJECTED
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

/**
 * What a thumbnail says in its four corners (docs/UI_GUIDE.md §8.8), and an edit that could not be saved staying in
 * view, on the thumbnail and in the viewer, until it is tried again or given up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w600dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailBadgesTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private lateinit var folder: File

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
            add("PICKED.JPG", flag = FLAG_PICKED, rating = 3)
            add("REJECTED.JPG", flag = FLAG_REJECTED)
            add("STARRED.JPG", rating = 2)
            add("RAW.ARW", kind = MediaKind.RAW)
            add("EDITED.JPG", edit = PhotoEdit(Adjustments(exposure = 0.5f)).toJson(), geoSource = GeoSource.TRACK)
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun add(
        name: String,
        kind: MediaKind = MediaKind.PHOTO,
        flag: Int = 0,
        rating: Int = 0,
        edit: String? = null,
        geoSource: GeoSource = GeoSource.NONE,
        written: Boolean = true,
    ): MediaItemEntity {
        val file = File(folder, name)
        if (written) {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val item = MediaItemEntity(
            path = file.absolutePath, fileName = name, kind = kind, sizeBytes = 1, capturedAt = 1, importedAt = 1,
            flag = flag, rating = rating, edit = edit, geoSource = geoSource,
        )
        val id = runBlocking { container.database.mediaDao().insert(item) }
        return item.copy(id = id)
    }

    private fun waitForDescription(description: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitUntilGone(description: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty()
    }

    /** An edit of a photo whose file is gone, which cannot be saved. */
    private fun failToSave(): MediaItemEntity {
        val lost = add("LOST.JPG", written = false)
        container.editSaver.save(lost, PhotoEdit(Adjustments(exposure = 1f)))
        compose.waitUntil(timeoutMillis = 10_000) { lost.id in container.editSaver.failed.value }
        return lost
    }

    @Test
    fun eachCornerSaysOneThingInItsTurn() {
        waitForDescription("PICKED.JPG")
        // Rejected before picked before stars: a picked photo with stars shows the flag, not the stars.
        compose.onNodeWithContentDescription("Rejected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Picked").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("★2").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("★3").fetchSemanticsNodes().size)
        // An edited photo says so; whether it has a location is left to its details.
        compose.onNodeWithContentDescription("Edited").assertIsDisplayed()
        // A raw file says what it is, in a cell with room for it: the badge, besides the filter chip of the same name.
        assertEquals(2, compose.onAllNodesWithText("RAW").fetchSemanticsNodes().size)
    }

    @Test
    fun largeTextKeepsTheDecisionAndDropsTheKind() {
        RuntimeEnvironment.setFontScale(1.5f)
        waitForDescription("PICKED.JPG")
        compose.onNodeWithContentDescription("Picked").assertIsDisplayed()
        // Only the filter chip says RAW: the kind badge has gone from the crowded cell.
        assertEquals(1, compose.onAllNodesWithText("RAW").fetchSemanticsNodes().size)
    }

    @Test
    fun aFailedSaveStaysOnItsThumbnailAlongsideTheSelection() {
        waitForDescription("PICKED.JPG")
        failToSave()
        waitForDescription("Edit not saved")

        // Picking photos out does not hide the failure.
        compose.onNodeWithContentDescription("LOST.JPG").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit not saved").assertIsDisplayed()
    }

    @Test
    fun tryingAgainOnceTheFileIsBackSavesAndClearsTheFailure() {
        waitForDescription("PICKED.JPG")
        val lost = failToSave()
        waitForDescription("Edit not saved")

        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        File(lost.path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        container.editSaver.retry(lost.id)

        compose.waitUntil(timeoutMillis = 10_000) { lost.id !in container.editSaver.saving.value && lost.id !in container.editSaver.failed.value }
        waitUntilGone("Edit not saved")
        assertEquals(PhotoEdit(Adjustments(exposure = 1f)), PhotoEdit.fromJson(runBlocking { container.database.mediaDao().byId(lost.id)!!.edit }))
    }

    @Test
    fun theViewerKeepsTheFailureUntilItIsGivenUp() {
        waitForDescription("PICKED.JPG")
        val lost = failToSave()
        waitForDescription("LOST.JPG")
        compose.onNodeWithContentDescription("LOST.JPG").performClick()

        compose.onNodeWithText("Could not save LOST.JPG.").assertIsDisplayed()
        // Trying again with the file still gone fails again, and the failure is still there to act on.
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { lost.id !in container.editSaver.saving.value && lost.id in container.editSaver.failed.value }
        compose.onNodeWithText("Could not save LOST.JPG.").assertIsDisplayed()

        compose.onNodeWithText("Give up the edit").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Could not save LOST.JPG.").fetchSemanticsNodes().isEmpty() }
        assertNull(container.editSaver.failed.value[lost.id])
        waitUntilGone("Edit not saved")
    }
}
