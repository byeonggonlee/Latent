package com.byeonggon.latent

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.byeonggon.latent.camera.SyncService
import com.byeonggon.latent.camera.SyncState
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.ui.sync.SyncScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/**
 * What the sync screen says when the camera holds files that look like the library's photos: a few are copied over and
 * compared without asking, many wait for the person's "Copy over and compare", which starts the same sync again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncLookAlikesTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val container get() = application.appContainer
    private val tree: Uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Card")
    private lateinit var card: File

    @get:Rule(order = 0)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            SystemFilesProvider.install()
            card = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).parentFile, "Card")
            runBlocking { container.settings.update { it.copy(cardFolder = tree.toString()) } }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @After
    fun forgetTheCard() {
        runBlocking { container.settings.update { AppSettings() } }
        card.deleteRecursively()
    }

    /** [count] photos on the card that the library holds too, by the same name and size, not known as imported from it. */
    private fun lookAlikes(count: Int) {
        val library = File(container.storage.root, "2026/2026-10-20").apply { mkdirs() }
        for (number in 1..count) {
            val name = "DSC%05d.JPG".format(number)
            val content = "picture $name"
            File(card, "DCIM/100MSDCF/$name").apply { parentFile!!.mkdirs() }.writeText(content)
            val kept = File(library, name).apply { writeText(content) }
            runBlocking {
                container.database.mediaDao().insert(
                    MediaItemEntity(path = kept.path, fileName = name, kind = MediaKind.PHOTO, sizeBytes = kept.length(), capturedAt = number.toLong(), importedAt = 1),
                )
            }
        }
    }

    private fun newOnTheCard(name: String) = File(card, "DCIM/100MSDCF/$name").apply { parentFile!!.mkdirs() }.writeText("a new picture")

    /** Runs a sync from the card as the screen's Import button would, and waits for it to finish. */
    private fun syncFromTheCard() {
        val start = Intent(application, SyncService::class.java).setAction("com.byeonggon.latent.action.START_SYNC").putExtra("folder", tree)
        Robolectric.buildService(SyncService::class.java, start).create().startCommand(0, 1)
        compose.waitUntil(timeoutMillis = 20_000) { container.syncEngine.state.value is SyncState.Finished }
        compose.waitForIdle()
    }

    @Test
    fun asksBeforeCopyingOverManyLookAlikesAndStartsTheSyncAgainToCompareThem() {
        lookAlikes(21)
        newOnTheCard("DSC09999.JPG")
        compose.setContent { SyncScreen() }

        syncFromTheCard()

        val finished = container.syncEngine.state.value as SyncState.Finished
        assertEquals(21, finished.toCompare?.files)
        compose.onNodeWithText("1 file imported · 0 geotagged").performScrollTo()
        compose.onNodeWithText("21 files on the camera look like photos already in the library", substring = true).performScrollTo()
        compose.onNodeWithText("Only what holds the same as the library's photo is then left out", substring = true).performScrollTo()
        compose.onNodeWithText("Copy over and compare").performScrollTo().performClick()

        val again = shadowOf(application).nextStartedService
        assertEquals(tree, again.getParcelableExtra("folder", Uri::class.java))
        assertTrue(again.getBooleanExtra("compare", false))
    }

    @Test
    fun comparesAFewLookAlikesWithoutAsking() {
        lookAlikes(3)
        compose.setContent { SyncScreen() }

        syncFromTheCard()

        val finished = container.syncEngine.state.value as SyncState.Finished
        assertEquals(null, finished.toCompare)
        compose.onNodeWithText("There is nothing new to import.").performScrollTo()
        compose.onAllNodesWithText("Copy over and compare").assertCountEquals(0)
        compose.onAllNodesWithText("look like photos already in the library", substring = true).assertCountEquals(0)
    }

    @Test
    fun asksNothingWhenNothingLooksLikeTheLibrarys() {
        newOnTheCard("DSC00001.JPG")
        compose.setContent { SyncScreen() }

        syncFromTheCard()

        compose.onNodeWithText("1 file imported · 0 geotagged").performScrollTo()
        compose.onAllNodesWithText("Copy over and compare").assertCountEquals(0)
    }
}
