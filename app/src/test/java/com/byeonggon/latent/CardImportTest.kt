package com.byeonggon.latent

import com.byeonggon.latent.camera.SyncService
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.ui.sync.SyncScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/** The sync screen imports from the camera's card the folder picked for it before, and says when the card is out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardImportTest {
    private val application get() = RuntimeEnvironment.getApplication()
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
            File(card, "DCIM/100MSDCF/DSC00001.JPG").apply { parentFile!!.mkdirs() }.writeText("picture")
            runBlocking { application.appContainer.settings.update { it.copy(cardFolder = tree.toString()) } }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @After
    fun forgetTheCard() {
        runBlocking { application.appContainer.settings.update { AppSettings() } }
        card.deleteRecursively()
    }

    @Test
    fun importsFromTheFolderPickedBefore() {
        compose.setContent { SyncScreen() }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("폴더: Card").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("가져오기").performClick()

        val started = shadowOf(application).nextStartedService
        assertEquals(tree, started.getParcelableExtra("folder", Uri::class.java))
    }

    @Test
    fun startsTheLastSyncAgainToCompareWhatLooksLikeTheLibrarys() {
        val first = Intent(application, SyncService::class.java).setAction("com.byeonggon.latent.action.START_SYNC").putExtra("folder", tree)
        Robolectric.buildService(SyncService::class.java, first).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(SyncService.compareLookAlikes(application))

        val again = shadowOf(application).nextStartedService
        assertEquals(tree, again.getParcelableExtra("folder", Uri::class.java))
        assertTrue(again.getBooleanExtra("compare", false))
    }

    @Test
    fun saysWhenTheCardIsOut() {
        card.deleteRecursively()

        compose.setContent { SyncScreen() }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("전에 고른 폴더를 읽을 수 없습니다", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(0, compose.onAllNodesWithText("가져오기").fetchSemanticsNodes().size)
        compose.onNodeWithText("다른 폴더").performClick()
    }
}
