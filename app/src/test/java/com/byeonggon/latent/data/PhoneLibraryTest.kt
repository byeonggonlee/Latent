package com.byeonggon.latent.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.grantLibraryAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/** The photos of other apps, listed in the library from where they are, and edited without being touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneLibraryTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val dao get() = container.database.mediaDao()
    private val application get() = RuntimeEnvironment.getApplication()

    /** A MediaStore with a few rows: answers every query with them, or those whose path was asked for. */
    class FakeMediaStore : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf("_id")
            val cursor = MatrixCursor(columns)
            for ((index, row) in rows.withIndex()) {
                // Row: path, relative path, display name, size, date taken, media type.
                if (selection?.contains("_data = ?") == true && row[0] != selectionArgs?.firstOrNull()) continue
                cursor.addRow(columns.map { column ->
                    when (column) {
                        "_data" -> row[0]
                        "relative_path" -> row[1]
                        "_display_name" -> row[2]
                        "_size" -> row[3]
                        "datetaken" -> row[4]
                        "media_type" -> row[5]
                        "_id" -> index + 1L
                        else -> null
                    }
                })
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private companion object {
        /** What MediaStore says is on the phone, as path, folder, name, size, date taken and media type; set by each test. */
        val rows = ArrayList<Array<Any?>>()
    }

    private lateinit var camera: File
    private lateinit var screenshots: File

    @Before
    fun setUp() {
        ShadowEnvironment.addExternalDir("shared")
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        grantLibraryAccess()
        Robolectric.buildContentProvider(FakeMediaStore::class.java).create("media")
        val storage = Environment.getExternalStorageDirectory()
        camera = File(storage, "DCIM/Camera").apply { mkdirs() }
        screenshots = File(storage, "Pictures/Screenshots").apply { mkdirs() }
    }

    private fun photo(folder: File, name: String, color: Int = 0xFF808080.toInt(), location: Pair<Double, Double>? = null): File =
        File(folder, name).also { file ->
            file.outputStream().use { Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (location != null) ExifInterface(file).apply { setLatLong(location.first, location.second); saveAttributes() }
        }

    private fun list(vararg files: File) {
        rows.clear()
        for (file in files) {
            // The library's folder is where MediaStore says it is, wherever the test's storage puts it.
            val folder = if (file.path.startsWith(container.storage.root.path)) PhoneLibrary.LIBRARY_FOLDER + "2026/2026-10-08/" else PhoneLibrary.folderOf(file.path)
            val type = if (file.extension == "mp4") 3 else 1
            rows += arrayOf(file.path, folder, file.name, file.length(), 5_000L, type)
        }
    }

    @Test
    fun listsTheFoldersWithPhotosAndRawFilesAndLeavesOutTheLibrary() = runBlocking {
        val library = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
        list(
            photo(camera, "A.jpg"), photo(camera, "B.jpg"), File(camera, "C.mp4").apply { writeBytes(ByteArray(10)) },
            File(camera, "D.dng").apply { writeBytes(ByteArray(10)) }, File(camera, "E.ARW").apply { writeBytes(ByteArray(10)) },
            photo(screenshots, "S.jpg"), photo(library, "DSC1.JPG"),
        )

        // A phone's DNG counts, like a camera's ARW.
        assertEquals(listOf(PhoneLibrary.Folder("DCIM/Camera/", 5), PhoneLibrary.Folder("Pictures/Screenshots/", 1)), container.phoneLibrary.folders())
    }

    @Test
    fun listsOnlyTheChosenFoldersAndOnlyOnce() = runBlocking {
        val taken = photo(camera, "A.jpg", location = 37.5 to 127.0)
        list(taken, photo(screenshots, "S.jpg"))

        assertEquals(1, container.phoneLibrary.refresh(setOf("DCIM/Camera/")))
        assertEquals(0, container.phoneLibrary.refresh(setOf("DCIM/Camera/")))

        val item = dao.all().single()
        assertEquals(MediaOrigin.PHONE, item.origin)
        assertEquals(MediaKind.PHOTO, item.kind)
        assertEquals(taken.path, item.path)
        // The time MediaStore gives, and the place the file has.
        assertEquals(5_000L, item.capturedAt)
        assertEquals(37.5, item.latitude!!, 1e-4)
    }

    @Test
    fun forgetsWhatIsGoneAndWhatIsNoLongerChosenUnlessEdited() = runBlocking {
        val kept = photo(camera, "A.jpg")
        val removed = photo(camera, "B.jpg")
        val edited = photo(screenshots, "S.jpg")
        val plain = photo(screenshots, "T.jpg")
        list(kept, removed, edited, plain)
        container.phoneLibrary.refresh(setOf("DCIM/Camera/", "Pictures/Screenshots/"))
        dao.all().single { it.path == edited.path }.let { dao.setEdit(it.id, PhotoEdit(Adjustments(exposure = 1f)).toJson()) }

        removed.delete()
        list(kept, edited, plain)
        container.phoneLibrary.refresh(setOf("DCIM/Camera/"))

        // B is gone from the phone, T is in a folder no longer chosen; S is too, but it was edited.
        assertEquals(setOf(kept.path, edited.path), dao.all().map { it.path }.toSet())
    }

    @Test
    fun keepsAnEditAsSettingsAndNeverChangesThePhoto() = runBlocking {
        val original = photo(camera, "A.jpg", color = 0xFF606060.toInt())
        val bytes = original.readBytes()
        list(original)
        container.phoneLibrary.refresh(setOf("DCIM/Camera/"))
        val item = dao.all().single()
        val edits = container.libraryEdits

        edits.apply(item, PhotoEdit(Adjustments(exposure = 1f)))

        val stored = dao.byId(item.id)!!
        assertTrue(edits.isEdited(stored))
        assertEquals(1f, edits.editOf(stored)!!.adjustments.exposure, 0f)
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(original.length(), stored.sizeBytes)

        // An edit that changes nothing is none.
        edits.apply(stored, PhotoEdit())
        assertNull(dao.byId(item.id)!!.edit)
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test
    fun sharesAndSavesCopiesWithTheEditAppliedAndTheOriginalUntouched() = runBlocking {
        val original = photo(camera, "A.jpg", color = 0xFF404040.toInt(), location = 37.5 to 127.0)
        val bytes = original.readBytes()
        list(original)
        container.phoneLibrary.refresh(setOf("DCIM/Camera/"))
        val edits = container.libraryEdits
        edits.apply(dao.all().single(), PhotoEdit(Adjustments(exposure = 2f)))
        val item = dao.all().single()

        val shared = edits.shareCopy(item, longSide = null)
        assertTrue(shared.path != original.path)
        val brightness = { file: File -> android.graphics.BitmapFactory.decodeFile(file.path).getPixel(5, 5) and 0xFF }
        assertTrue("edited ${brightness(shared)} against ${brightness(original)}", brightness(shared) > brightness(original) + 20)

        val copy = edits.saveCopy(item)
        assertTrue(copy.path.startsWith(container.storage.root.path))
        val inLibrary = dao.all().single { it.path == copy.path }
        assertEquals(MediaOrigin.LIBRARY, inLibrary.origin)
        assertNull(inLibrary.edit)
        assertNotNull(ExifInterface(copy).latLong)
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test
    fun placesAPhoneFileInTheFolderItIsIn() {
        val storage = Environment.getExternalStorageDirectory()
        assertEquals("DCIM/Camera/", PhoneLibrary.folderOf(File(storage, "DCIM/Camera/a.jpg").path))
        assertEquals("DCIM/Camera/", PhoneLibrary.folderOf("/storage/1234-ABCD/DCIM/Camera/a.jpg"))
        assertEquals("Pictures/", PhoneLibrary.folderOf("/storage/emulated/0/Pictures/a.jpg"))
        // A folder sorted into years and days counts as the one it is kept in.
        assertEquals("DCIM/CameraManager/", PhoneLibrary.folderOf("/storage/emulated/0/DCIM/CameraManager/2026/2026-10-08/a.jpg"))
        assertEquals("DCIM/CameraManager/", PhoneLibrary.groupOf("DCIM/CameraManager/2026/2026-10-08/"))
        assertEquals("Download/", PhoneLibrary.groupOf("Download/2025-11-02/"))
        assertEquals("Pictures/Trip 2024/", PhoneLibrary.groupOf("Pictures/Trip 2024/"))
    }
}
