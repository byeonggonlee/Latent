package com.byeonggon.latent

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.byeonggon.latent.camera.SyncService
import com.byeonggon.latent.camera.SyncState
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.SyncRecordEntity
import com.byeonggon.latent.ui.sync.SyncScreen
import kotlinx.coroutines.runBlocking
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

/** The sync screen forgets the imports of files deleted from the phone only once asked, and says why a sync failed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncScreenTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val container get() = application.appContainer
    private val records get() = container.database.syncRecordDao()

    @get:Rule(order = 0)
    val environment = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            grantLibraryAccess()
            SystemFilesProvider.install()
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun forgetsTheImportsOfFilesDeletedFromThePhoneOnlyOnceAsked() {
        val folder = File(container.storage.root, "2026/2026-10-18").apply { mkdirs() }
        val kept = File(folder, "DSC00003.JPG").apply { writeText("kept") }
        runBlocking {
            records.insert(SyncRecordEntity("ZV-E10/DSC00001.JPG/100", 1))
            records.insert(SyncRecordEntity("ZV-E10/DSC00002.JPG/100", 1))
            records.insert(SyncRecordEntity("ZV-E10/DSC00003.JPG/100", 1))
            container.database.mediaDao().insert(
                MediaItemEntity(path = kept.path, fileName = kept.name, kind = MediaKind.PHOTO, sizeBytes = kept.length(), capturedAt = 1, importedAt = 1, sourceKey = "ZV-E10/DSC00003.JPG/100"),
            )
        }
        compose.setContent { SyncScreen() }
        waitForText("2 files imported and then deleted from the phone are skipped when syncing.")

        compose.onNodeWithText("Let them be imported again").performScrollTo().performClick()
        compose.onNodeWithText("Forget these imports").assertIsDisplayed()
        compose.onNodeWithText("Forgets that 2 files deleted from the phone were imported", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(3, runBlocking { records.allKeys() }.size)

        compose.onNodeWithText("Let them be imported again").performScrollTo().performClick()
        compose.onNodeWithText("Forget").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("Files deleted from the phone").fetchSemanticsNodes().isEmpty()
        }
        // The file still in the library is still known as imported, and stays.
        assertEquals(listOf("ZV-E10/DSC00003.JPG/100"), runBlocking { records.allKeys() })
        assertTrue(kept.exists())
    }

    @Test
    fun saysWhenTheSyncFailedAndWhy() {
        val gone: Uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:CardThatIsOut")
        compose.setContent { SyncScreen() }

        val start = Intent(application, SyncService::class.java).setAction("com.byeonggon.latent.action.START_SYNC").putExtra("folder", gone)
        Robolectric.buildService(SyncService::class.java, start).create().startCommand(0, 1)
        compose.waitUntil(timeoutMillis = 20_000) { container.syncEngine.state.value is SyncState.Failed }

        compose.onNodeWithText("Sync failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText((container.syncEngine.state.value as SyncState.Failed).message).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check the cable and the camera's USB connection setting (MTP).").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Sync finished").assertCountEquals(0)
    }
}
