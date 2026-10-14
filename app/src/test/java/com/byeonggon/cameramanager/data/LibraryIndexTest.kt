package com.byeonggon.cameramanager.data

import com.byeonggon.cameramanager.grantLibraryAccess
import android.content.Context
import java.time.OffsetDateTime
import com.byeonggon.cameramanager.raw.SyntheticArw
import com.byeonggon.cameramanager.enhance.PhotoEdit
import androidx.exifinterface.media.ExifInterface
import android.graphics.Bitmap
import android.os.Environment
import androidx.room.Room
import com.byeonggon.cameramanager.data.db.AppDatabase
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryIndexTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: AppDatabase
    private lateinit var storage: LibraryStorage
    private lateinit var index: LibraryIndex

    @Before
    fun setUp() {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        storage = LibraryStorage(context)
        index = LibraryIndex(storage, database)
    }

    @After
    fun tearDown() {
        database.close()
        storage.root.deleteRecursively()
    }

    private suspend fun addToLibrary(name: String, onDisk: Boolean, edited: Boolean): File {
        val file = File(storage.root, "2026/2026-10-08/$name")
        file.parentFile!!.mkdirs()
        if (onDisk) file.writeText("edited picture")
        if (edited) storage.originalOf(file).apply { parentFile!!.mkdirs() }.writeText("original picture")
        database.mediaDao().insert(
            MediaItemEntity(path = file.path, fileName = name, kind = MediaKind.PHOTO, sizeBytes = 14, capturedAt = 1, importedAt = 2),
        )
        return file
    }

    @Test
    fun keepsOriginalsOutOfSightUnderTheSamePath() {
        val original = storage.originalOf(File(storage.root, "2026/2026-10-08/DSC00001.JPG"))

        assertEquals(File(storage.root, ".originals/2026/2026-10-08/DSC00001.JPG"), original)
    }

    @Test
    fun forgetsPhotosDeletedElsewhereTogetherWithTheirHiddenOriginals() = runBlocking {
        val deleted = addToLibrary("DSC00001.JPG", onDisk = false, edited = true)
        val kept = addToLibrary("DSC00002.JPG", onDisk = true, edited = true)

        index.forgetMissingFiles()

        assertEquals(listOf("DSC00002.JPG"), database.mediaDao().all().map { it.fileName })
        assertFalse(storage.originalOf(deleted).exists())
        assertTrue(storage.originalOf(kept).exists())
    }

    @Test
    fun takesInFilesPutInItsFolderFromElsewhere() = runBlocking {
        val folder = File(storage.root, "2026/2026-10-09").apply { mkdirs() }
        val photo = File(folder, "DSC01543.JPG")
        photo.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(photo).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:09 15:42:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+09:00")
            setLatLong(37.55, 126.92)
            saveAttributes()
        }
        File(folder, "DSC01543.ARW").writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700))
        val edited = File(folder, "DSC01544.JPG").apply { writeText("edited picture") }
        storage.originalOf(edited).apply { parentFile!!.mkdirs() }.writeText("original picture")
        // Not photos of the library: a sidecar, and a half-written edit.
        File(folder, "DSC01543.xmp").writeText("<x/>")
        File(folder, ".DSC01545-edited.JPG").writeText("partial")
        addToLibrary("DSC00002.JPG", onDisk = true, edited = false)

        assertEquals(3, index.adoptUnknownFiles())

        val byName = database.mediaDao().all().associateBy { it.fileName }
        assertEquals(setOf("DSC00002.JPG", "DSC01543.JPG", "DSC01543.ARW", "DSC01544.JPG"), byName.keys)
        val adopted = byName.getValue("DSC01543.JPG")
        assertEquals(OffsetDateTime.parse("2026-10-09T15:42:00+09:00").toInstant().toEpochMilli(), adopted.capturedAt)
        assertEquals(37.55, adopted.latitude!!, 1e-4)
        // The raw file was shot with the JPEG, at the same place.
        assertEquals(37.55, byName.getValue("DSC01543.ARW").latitude!!, 1e-4)
        assertEquals(MediaKind.RAW, byName.getValue("DSC01543.ARW").kind)
        // An edited photo can be put back to its original, though how it was edited is not known.
        assertEquals(PhotoEdit().toJson(), byName.getValue("DSC01544.JPG").edit)
        assertEquals(null, adopted.edit)

        assertEquals(0, index.adoptUnknownFiles())
    }

    @Test
    fun leavesAPhotoAloneWhileItsEditIsBeingSaved() = runBlocking {
        // Saving moves the photo aside for a moment, to keep its original.
        val moment = addToLibrary("DSC00004.JPG", onDisk = false, edited = true)
        val id = database.mediaDao().all().single().id

        LibraryIndex(storage, database) { setOf(id) }.forgetMissingFiles()

        assertEquals(listOf("DSC00004.JPG"), database.mediaDao().all().map { it.fileName })
        assertTrue(storage.originalOf(moment).exists())
    }

    @Test
    fun touchesNothingWhileSharedStorageIsUnavailable() = runBlocking {
        val unreachable = addToLibrary("DSC00003.JPG", onDisk = false, edited = true)
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_UNMOUNTED)

        index.forgetMissingFiles()

        assertEquals(1, database.mediaDao().all().size)
        assertTrue(storage.originalOf(unreachable).exists())
    }
}
