package com.byeonggon.latent.data

import android.graphics.Bitmap
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.SystemFilesProvider
import com.byeonggon.latent.appContainer
import com.byeonggon.latent.data.db.GeoSource
import com.byeonggon.latent.data.db.LocationPointEntity
import com.byeonggon.latent.data.db.LocationSource
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.PhotoEdit
import com.byeonggon.latent.grantLibraryAccess
import com.byeonggon.latent.raw.SyntheticArw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.io.IOException

/** How library files are moved and written over: never two edits of one photo at once, never a file half there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryMovesTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private val storage get() = container.storage
    private lateinit var photo: File

    @Before
    fun writeAPhoto() {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        photo = File(storage.root, "2026/2026-10-08/DSC00001.JPG").apply { parentFile!!.mkdirs() }
        photo.outputStream().use { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF404040.toInt()) }.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    @After
    fun clearTheLibrary() {
        storage.root.walk().forEach {
            it.setReadable(true)
            it.setWritable(true)
        }
        storage.root.deleteRecursively()
    }

    private fun item(): MediaItemEntity = runBlocking {
        val dao = container.database.mediaDao()
        dao.byId(dao.insert(MediaItemEntity(path = photo.path, fileName = photo.name, kind = MediaKind.PHOTO, sizeBytes = photo.length(), capturedAt = CAPTURED_AT, importedAt = 2)))!!
    }

    @Test
    fun twoEditsOfOnePhotoAtOnceKeepItsOriginal() = runBlocking {
        // Saved from the editor in the background while a batch pastes a look onto the same photo, say.
        val original = photo.readBytes()
        val item = item()

        listOf(0.5f, 1f).map { exposure ->
            async(Dispatchers.Default) { container.libraryEdits.apply(item, PhotoEdit(Adjustments(exposure = exposure))) }
        }.awaitAll()

        assertArrayEquals(original, storage.originalOf(photo).readBytes())
        assertFalse(original.contentEquals(photo.readBytes()))
    }

    @Test
    fun aFileThatCannotBeMovedKeepsItsName() {
        // The system's provider of the test cannot move a document from one folder to another.
        SystemFilesProvider.install()
        val original = storage.originalOf(photo).apply { parentFile!!.mkdirs() }
        photo.copyTo(original)

        assertFalse(storage.folder.move(original, File(storage.root, ".trash/7/original-${photo.name}")))

        // Under any other name, the photo would no longer find it as its original.
        assertTrue(original.exists())
        assertEquals(listOf(photo.name), original.parentFile!!.list()!!.toList())
    }

    @Test
    fun aPhotoThatCannotBeTakenOverStaysWholeWithNothingBesideItsNewPlace() {
        // Another installation's photo, which only the granted folder reads, and whose folder lets nothing go by its path:
        // copied beside its new place, it cannot be deleted where it is, and the copy is given up.
        SystemFilesProvider.install()
        val target = storage.originalOf(photo)
        val whole = photo.readBytes()
        photo.setReadable(false)
        photo.parentFile!!.setWritable(false)
        assumeFalse("The tests run as a user who reads every file", photo.canRead())

        assertThrows(IOException::class.java) { storage.move(photo, target) }

        assertEquals(emptyList<String>(), target.parentFile!!.list()!!.toList())
        photo.setReadable(true)
        assertArrayEquals(whole, photo.readBytes())
    }

    @Test
    fun aPlaceIsWrittenIntoACopyThatTakesThePhotosPlace() = runBlocking {
        item()
        val dao = container.database.locationDao()
        dao.insertAll(
            listOf(
                LocationPointEntity(timestamp = CAPTURED_AT - 60_000, latitude = 37.55, longitude = 126.92, source = LocationSource.APP),
                LocationPointEntity(timestamp = CAPTURED_AT + 60_000, latitude = 37.55, longitude = 126.92, source = LocationSource.APP),
            ),
        )
        val modifiedAt = photo.lastModified()

        assertEquals(1, container.geotagger.tagPending(maxGapMs = 5 * 60_000L))

        assertEquals(37.55, ExifInterface(photo).latLong!![0], 1e-4)
        assertEquals(modifiedAt, photo.lastModified())
        assertEquals(listOf(photo.name), photo.parentFile!!.list()!!.toList())
    }

    @Test
    fun aDevelopedRawFileComesInWholeWithItsPlaceAndNothingBesideIt() = runBlocking {
        item()
        val raw = File(photo.parentFile, "DSC00002.ARW").apply { writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700)) }
        val dao = container.database.mediaDao()
        val id = dao.insert(
            MediaItemEntity(
                path = raw.path, fileName = raw.name, kind = MediaKind.RAW, sizeBytes = raw.length(), capturedAt = CAPTURED_AT, importedAt = 2,
                latitude = 37.55, longitude = 126.92, geoSource = GeoSource.CAMERA,
            ),
        )

        val developed = container.libraryEdits.exportRaw(dao.byId(id)!!)

        assertEquals(setOf(photo.name, raw.name, "DSC00002_developed.jpg"), photo.parentFile!!.list()!!.toSet())
        assertEquals(developed.path, dao.all().single { it.fileName == developed.name }.path)
        assertEquals(37.55, ExifInterface(developed).latLong!![0], 1e-4)
        assertEquals(0, container.libraryIndex.adoptUnknownFiles())
    }

    private companion object {
        const val CAPTURED_AT = 1_760_000_000_000L
    }
}
