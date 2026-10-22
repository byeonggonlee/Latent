package com.byeonggon.latent

import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.byeonggon.latent.camera.SyncService
import com.byeonggon.latent.camera.SyncState
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cancelling a sync from its screen stops it once the file being transferred is in: what came in stays, what did not
 * stays on the camera for the next sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncCancelTest {
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
            SlowCard.release = CountDownLatch(1)
            SlowCard.slowListing = false
            SlowCard.install()
            card = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).parentFile, "Card")
            for (number in 1..3) {
                File(card, "DCIM/100MSDCF/DSC0000$number.JPG").apply {
                    parentFile!!.mkdirs()
                    writeText("picture $number")
                    // Transferred oldest first.
                    setLastModified(1_790_000_000_000L + number * 60_000L)
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @After
    fun forgetTheCard() {
        SlowCard.release.countDown()
        card.deleteRecursively()
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 20_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun stopsOnceTheFileBeingTransferredIsInAndKeepsWhatCameIn() {
        compose.setContent { SyncScreen() }
        val start = Intent(application, SyncService::class.java).setAction("com.byeonggon.latent.action.START_SYNC").putExtra("folder", tree)
        Robolectric.buildService(SyncService::class.java, start).create().startCommand(0, 1)

        waitForText("1 / 3 · DSC00001.JPG")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        waitForText("Stops once the current file is transferred.")
        assertEquals(0, compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().size)

        SlowCard.release.countDown()
        compose.waitUntil(timeoutMillis = 20_000) { container.syncEngine.state.value is SyncState.Finished }
        waitForText("Sync stopped")
        waitForText("1 file imported")

        val finished = container.syncEngine.state.value as SyncState.Finished
        assertTrue(finished.cancelled)
        assertEquals(1, finished.imported)
        assertEquals(listOf("DSC00001.JPG"), runBlocking { container.database.mediaDao().all() }.map { it.fileName })
        // Nothing is taken off the camera.
        assertEquals(3, File(card, "DCIM/100MSDCF").list()!!.size)
    }

    @Test
    fun importsNothingWhenCancelledWhileTheCameraIsRead() {
        SlowCard.slowListing = true
        compose.setContent { SyncScreen() }
        val start = Intent(application, SyncService::class.java).setAction("com.byeonggon.latent.action.START_SYNC").putExtra("folder", tree)
        Robolectric.buildService(SyncService::class.java, start).create().startCommand(0, 1)

        waitForText("Reading the camera's list of files…")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        SlowCard.release.countDown()

        compose.waitUntil(timeoutMillis = 20_000) { container.syncEngine.state.value is SyncState.Finished }
        waitForText("Sync stopped")
        waitForText("Nothing was imported.")
        assertEquals(emptyList<String>(), runBlocking { container.database.mediaDao().all() }.map { it.fileName })
        assertEquals(emptyList<String>(), runBlocking { container.database.syncRecordDao().allKeys() })
    }
}

/** The phone's storage as [SystemFilesProvider] shows it, whose first photo, or folder of photos, takes until [release] to be read. */
internal class SlowCard : DocumentsProvider() {
    private val files = SystemFilesProvider()

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = files.queryRoots(projection)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = files.queryDocument(documentId, projection)

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (slowListing && parentDocumentId.endsWith("100MSDCF")) release.await(20, TimeUnit.SECONDS)
        return files.queryChildDocuments(parentDocumentId, projection, sortOrder)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = files.isChildDocument(parentDocumentId, documentId)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (!slowListing && documentId.endsWith("DSC00001.JPG")) release.await(20, TimeUnit.SECONDS)
        return files.openDocument(documentId, mode, signal)
    }

    companion object {
        lateinit var release: CountDownLatch

        /** Whether the card's folder of photos is what takes until [release] to be read, rather than its first photo. */
        var slowListing = false

        fun install() {
            Robolectric.buildContentProvider(SlowCard::class.java).create(
                ProviderInfo().apply {
                    authority = "com.android.externalstorage.documents"
                    exported = true
                    grantUriPermissions = true
                    readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
                },
            )
        }
    }
}
