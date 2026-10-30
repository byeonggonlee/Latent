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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/** After an import, the sync screen says how many files came in without a place, and leads to the Location screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncUnplacedTest {
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

    private fun onTheCard(vararg names: String) = names.forEach { name ->
        File(card, "DCIM/100MSDCF/$name").apply { parentFile!!.mkdirs() }.writeText("a new picture $name")
    }

    /** Runs a sync from the card as the screen's Import button would, and waits for it to finish. */
    private fun syncFromTheCard() {
        val start = Intent(application, SyncService::class.java).setAction("com.byeonggon.latent.action.START_SYNC").putExtra("folder", tree)
        Robolectric.buildService(SyncService::class.java, start).create().startCommand(0, 1)
        compose.waitUntil(timeoutMillis = 20_000) { container.syncEngine.state.value is SyncState.Finished }
        compose.waitForIdle()
    }

    @Test
    fun saysHowManyCameWithoutAPlaceAndOpensTheLocationScreen() {
        // Nothing was recorded, so neither picture finds a place.
        onTheCard("DSC00001.JPG", "DSC00002.JPG")
        var locationOpened = false
        compose.setContent { SyncScreen(onOpenLocation = { locationOpened = true }) }

        syncFromTheCard()

        assertEquals(2, (container.syncEngine.state.value as SyncState.Finished).unplaced)
        compose.onNodeWithText("2 files imported · 0 geotagged").performScrollTo()
        compose.onNodeWithText("2 of them came without a location").performScrollTo()
        compose.onNodeWithText("A Google Timeline export, or the camera clock set right", substring = true).performScrollTo()
        compose.onNodeWithText("Add locations to photos").performScrollTo().performClick()

        assertTrue(locationOpened)
    }

    @Test
    fun saysNothingOfPlacesWhenNothingCameIn() {
        File(card, "DCIM/100MSDCF").mkdirs()
        compose.setContent { SyncScreen(onOpenLocation = {}) }

        syncFromTheCard()

        compose.onNodeWithText("There is nothing new to import.").performScrollTo()
        compose.onAllNodesWithText("came without a location", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Add locations to photos").assertCountEquals(0)
    }
}
