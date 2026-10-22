package com.byeonggon.latent.data

import com.byeonggon.latent.grantLibraryAccess
import android.content.Context
import java.time.OffsetDateTime
import com.byeonggon.latent.raw.SyntheticArw
import com.byeonggon.latent.raw.SyntheticDng
import com.byeonggon.latent.enhance.PhotoEdit
import androidx.exifinterface.media.ExifInterface
import android.graphics.Bitmap
import android.os.Environment
import androidx.room.Room
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
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
import java.time.ZoneId

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

    private companion object {
        /** Long enough for a check that does not wait to have finished. */
        const val WAIT_MS = 300L
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
    fun aPixelsRawFileTakesThePlaceOfItsCoverJpeg() = runBlocking {
        val folder = File(storage.root, "2026/2026-10-21").apply { mkdirs() }
        val cover = File(folder, "PXL_20261021_101500123.RAW-01.COVER.jpg")
        cover.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(cover).apply {
            setLatLong(37.55, 126.92)
            saveAttributes()
        }
        File(folder, "PXL_20261021_101500123.RAW-02.ORIGINAL.dng").writeBytes(SyntheticDng.file(200, 400, 300))

        assertEquals(2, index.adoptUnknownFiles())

        val raw = database.mediaDao().all().single { it.kind == MediaKind.RAW }
        assertEquals(37.55, raw.latitude!!, 1e-4)
    }

    @Test
    fun leavesAPhotoAloneWhileItIsBeingMoved() = runBlocking {
        // Saving an edit, or putting a photo in the trash, moves it aside for a moment before its entry says where it is.
        val moment = addToLibrary("DSC00004.JPG", onDisk = false, edited = true)

        val check = storage.moving.withLock {
            launch { index.forgetMissingFiles() }.also {
                delay(WAIT_MS)
                assertTrue("The check waits for the move to finish", it.isActive)
                moment.writeText("edited picture")
            }
        }
        check.join()

        assertEquals(listOf("DSC00004.JPG"), database.mediaDao().all().map { it.fileName })
        assertTrue(storage.originalOf(moment).exists())
    }

    @Test
    fun takesNoPhotoInTwiceThatIsBeingPutBackFromTheTrash() = runBlocking {
        val photo = addToLibrary("DSC00005.JPG", onDisk = false, edited = false)
        val id = database.mediaDao().all().single().id
        val trashed = File(storage.root, ".trash/$id/${photo.name}").apply { parentFile!!.mkdirs() }
        database.mediaDao().setPlace(id, trashed.path, 3, photo.path)

        // The photo is back in its place a moment before its entry says so.
        val adopting = storage.moving.withLock {
            photo.writeText("photo")
            async { index.adoptUnknownFiles() }.also {
                delay(WAIT_MS)
                assertTrue("Taking files in waits for the photo to be put back", it.isActive)
                database.mediaDao().setPlace(id, photo.path, null, null)
            }
        }

        assertEquals(0, adopting.await())
        assertEquals(listOf(id), database.mediaDao().all().map { it.id })
    }

    @Test
    fun touchesNothingWhileSharedStorageIsUnavailable() = runBlocking {
        val unreachable = addToLibrary("DSC00003.JPG", onDisk = false, edited = true)
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_UNMOUNTED)

        index.forgetMissingFiles()

        assertEquals(1, database.mediaDao().all().size)
        assertTrue(storage.originalOf(unreachable).exists())
    }

    private fun photoShotAt(name: String, wallClock: String, offset: String? = null): File {
        val photo = File(storage.root, "2026/2026-10-09/$name").apply { parentFile!!.mkdirs() }
        photo.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(photo).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, wallClock)
            if (offset != null) setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            saveAttributes()
        }
        return photo
    }

    @Test
    fun readsATimeWithoutAZoneInTheCamerasZone() = runBlocking {
        val seoul = ZoneId.of("Asia/Seoul")
        val abroad = LibraryIndex(storage, database, cameraZone = { seoul })
        photoShotAt("DSC02001.JPG", "2026:10:09 15:42:00")
        photoShotAt("DSC02002.JPG", "2026:10:09 15:42:00", offset = "+02:00")

        abroad.adoptUnknownFiles()

        val byName = database.mediaDao().all().associateBy { it.fileName }
        assertEquals(OffsetDateTime.parse("2026-10-09T15:42:00+09:00").toInstant().toEpochMilli(), byName.getValue("DSC02001.JPG").capturedAt)
        // A photo that says its own zone is not second-guessed.
        assertEquals(OffsetDateTime.parse("2026-10-09T15:42:00+02:00").toInstant().toEpochMilli(), byName.getValue("DSC02002.JPG").capturedAt)
    }

    @Test
    fun readsTheTimesOfPhotosAlreadyInTheLibraryAgainAndCountsTheOnesThatChanged() = runBlocking {
        photoShotAt("DSC02003.JPG", "2026:10:09 15:42:00")
        photoShotAt("DSC02004.JPG", "2026:10:09 15:42:00", offset = "+09:00")
        // Read in the zone of the phone, wherever that is, as an earlier version did.
        LibraryIndex(storage, database, cameraZone = { ZoneId.of("Europe/Paris") }).adoptUnknownFiles()
        val parisAssumed = database.mediaDao().all().associateBy { it.fileName }.getValue("DSC02003.JPG").capturedAt

        val changed = index.rereadCaptureTimes(ZoneId.of("Asia/Seoul"))

        val byName = database.mediaDao().all().associateBy { it.fileName }
        val seoul = OffsetDateTime.parse("2026-10-09T15:42:00+09:00").toInstant().toEpochMilli()
        assertEquals(seoul, byName.getValue("DSC02003.JPG").capturedAt)
        assertEquals(seoul, byName.getValue("DSC02004.JPG").capturedAt)
        assertTrue(parisAssumed != seoul)
        assertEquals(1, changed)
        assertEquals(0, index.rereadCaptureTimes(ZoneId.of("Asia/Seoul")))
    }
}
