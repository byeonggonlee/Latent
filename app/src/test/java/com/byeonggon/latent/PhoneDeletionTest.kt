package com.byeonggon.latent

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.byeonggon.latent.data.AppSettings
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * A photo another app made is deleted from the viewer by the system, which asks the person first: the app never deletes
 * the file itself, and the viewer stays on the photo when the person says no, and moves on once the system has done it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneDeletionTest {
    private lateinit var first: File
    private lateinit var second: File
    private lateinit var own: File
    private val ids = LongArray(3)
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val mediaDao get() = container.database.mediaDao()

    @get:Rule(order = 0)
    val library = object : ExternalResource() {
        override fun before() {
            forgetTheApplicationOfEarlierTests()
            ShadowEnvironment.addExternalDir("shared")
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            grantLibraryAccess()
            FakeMediaStore.asked.clear()
            FakeMediaStore.unknown.clear()
            FakeMediaStore.install()
            val folder = File(Environment.getExternalStorageDirectory(), "DCIM/Camera").apply { mkdirs() }
            first = File(folder, "20261010_120000.jpg")
            second = File(folder, "20261010_110000.jpg")
            runBlocking {
                // The folder is one the person chose, or the library would not keep the photos.
                container.settings.update { it.copy(phoneFolders = setOf("DCIM/Camera/")) }
                listOf(first, second).forEachIndexed { index, photo ->
                    photo.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    ids[index] = mediaDao.insert(
                        MediaItemEntity(
                            path = photo.absolutePath, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(),
                            capturedAt = 5_000L - index, importedAt = 1, origin = MediaOrigin.PHONE,
                        ),
                    )
                }
                // And one photo of the library's own, older than both.
                own = File(container.storage.root, "2026/2026-10-09/DSC00001.JPG").apply { parentFile!!.mkdirs() }
                own.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ids[2] = mediaDao.insert(
                    MediaItemEntity(path = own.absolutePath, fileName = own.name, kind = MediaKind.PHOTO, sizeBytes = own.length(), capturedAt = 1_000L, importedAt = 1),
                )
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 2)
    val cleanUp = object : ExternalResource() {
        // The settings live in one store for the whole test run.
        override fun after() = runBlocking { container.settings.update { AppSettings() } }
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun open(fileName: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(fileName).performClick()
        waitForText("편집")
    }

    /** Taps Delete and waits for the system's question, which the test answers. */
    private fun deleteAndAnswer(answer: Int, beforeAnswering: () -> Unit = {}) {
        compose.onNodeWithText("삭제").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); shadowOf(compose.activity).lastIntentSenderRequest != null }
        val asked = shadowOf(compose.activity).lastIntentSenderRequest
        assertEquals(FakeMediaStore.request.intentSender, asked.intentSender)
        beforeAnswering()
        shadowOf(compose.activity).callOnActivityResult(asked.requestCode, answer, null)
        compose.waitForIdle()
    }

    @Test
    fun asksTheSystemAndStaysOnThePhotoWhenThePersonSaysNo() {
        open(first.name)

        deleteAndAnswer(Activity.RESULT_CANCELED)

        assertEquals(listOf("create_trash_request" to listOf(FakeMediaStore.uriOf(first))), FakeMediaStore.asked)
        assertTrue(first.exists())
        assertNotNull(runBlocking { mediaDao.byId(ids[0]) })
        // Still on the photo, not moved on to the next.
        compose.onNodeWithText(first.name).assertExists()
        assertEquals(0, compose.onAllNodesWithText(second.name).fetchSemanticsNodes().size)
    }

    @Test
    fun movesOnOnceTheSystemHasDeletedThePhoto() {
        open(first.name)

        // The system puts the file in its own trash, then answers.
        deleteAndAnswer(Activity.RESULT_OK) { first.delete() }

        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); runBlocking { mediaDao.byId(ids[0]) } == null }
        assertTrue(second.exists())
        assertNotNull(runBlocking { mediaDao.byId(ids[1]) })
        // On to the next photo.
        waitForText(second.name)
    }

    private fun waitForPhotos() = compose.waitUntil(timeoutMillis = 10_000) {
        listOf(first, second, own).all { compose.onAllNodesWithContentDescription(it.name).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun trashesTheLibrarysOwnAndLeavesThePhonesToTheSystemsQuestion() {
        waitForPhotos()
        compose.onNodeWithContentDescription(first.name).performTouchInput { longClick() }
        compose.onNodeWithContentDescription(own.name).performClick()
        compose.onNodeWithText("삭제").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); shadowOf(compose.activity).lastIntentSenderRequest != null }
        assertEquals(listOf("create_trash_request" to listOf(FakeMediaStore.uriOf(first))), FakeMediaStore.asked)
        compose.waitUntil(timeoutMillis = 10_000) { runBlocking { mediaDao.byId(ids[2])!!.trashedAt } != null }
        // Only what the app put in its trash is said to be there: the phone's photo waits for the person's answer.
        waitForText("1장을 휴지통으로 옮겼습니다.")

        val asked = shadowOf(compose.activity).lastIntentSenderRequest
        shadowOf(compose.activity).callOnActivityResult(asked.requestCode, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertTrue(first.exists())
        assertNotNull(runBlocking { mediaDao.byId(ids[0]) })
    }

    @Test
    fun saysNothingIsInTheTrashWhileOnlyTheSystemHasBeenAsked() {
        waitForPhotos()
        compose.onNodeWithContentDescription(first.name).performTouchInput { longClick() }
        compose.onNodeWithText("삭제").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 10_000) { compose.waitForIdle(); shadowOf(compose.activity).lastIntentSenderRequest != null }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, compose.onAllNodesWithText("휴지통으로 옮겼습니다", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("휴지통으로 옮기지 못했습니다.").fetchSemanticsNodes().size)
    }

    @Test
    fun saysSoWhenTheSystemDoesNotKnowAPhotoOfThePhone() {
        FakeMediaStore.unknown += second.absolutePath
        waitForPhotos()
        compose.onNodeWithContentDescription(second.name).performTouchInput { longClick() }
        compose.onNodeWithText("삭제").performScrollTo().performClick()

        waitForText("휴지통으로 옮기지 못했습니다.")
        assertNull(shadowOf(compose.activity).lastIntentSenderRequest)
        assertTrue(second.exists())
        assertNotNull(runBlocking { mediaDao.byId(ids[1]) })
        assertEquals(0, compose.onAllNodesWithText("1장을 휴지통으로 옮겼습니다.").fetchSemanticsNodes().size)
    }
}

/** Stands in for the system's MediaStore: knows the phone's photos by path, and makes a request to put them in its trash. */
internal class FakeMediaStore : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID)
        val cursor = MatrixCursor(columns)
        // Asked for one file by its path: its id. Listing the phone's photos finds nothing new.
        val path = selectionArgs?.singleOrNull()
        if (selection?.contains(MediaStore.MediaColumns.DATA) == true && path != null && path !in unknown && File(path).exists()) cursor.addRow(arrayOf<Any>(idOf(path)))
        return cursor
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle {
        val clip = extras!!.getParcelable("clip_data", ClipData::class.java)!!
        asked += method to List(clip.itemCount) { clip.getItemAt(it).uri }
        return Bundle().apply { putParcelable("result", request) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("The app deleted $uri itself")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("The app changed $uri itself")

    companion object {
        val asked = ArrayList<Pair<String, List<Uri>>>()

        /** Paths of files the system has not taken in yet, or no longer knows. */
        val unknown = HashSet<String>()
        val request: PendingIntent by lazy {
            PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 0, Intent("test.SYSTEM_TRASH_QUESTION"), PendingIntent.FLAG_IMMUTABLE)
        }

        private fun idOf(path: String) = path.hashCode().toLong() and 0xFFFFFF

        fun uriOf(file: File): Uri = Uri.withAppendedPath(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), idOf(file.absolutePath).toString())

        fun install() {
            Robolectric.buildContentProvider(FakeMediaStore::class.java).create(ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        }
    }
}
