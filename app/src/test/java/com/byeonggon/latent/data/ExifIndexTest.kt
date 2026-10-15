package com.byeonggon.latent.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import com.byeonggon.latent.data.db.AppDatabase
import com.byeonggon.latent.data.db.MediaItemEntity
import com.byeonggon.latent.data.db.MediaKind
import com.byeonggon.latent.data.db.MediaOrigin
import com.byeonggon.latent.grantLibraryAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class ExifIndexTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: AppDatabase
    private lateinit var storage: LibraryStorage
    private lateinit var index: ExifIndex

    @Before
    fun setUp() {
        ShadowEnvironment.addExternalDir("shared")
        grantLibraryAccess()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        storage = LibraryStorage(context)
        index = ExifIndex(storage, database)
    }

    @After
    fun tearDown() {
        database.close()
        storage.root.deleteRecursively()
    }

    private fun add(name: String, kind: MediaKind = MediaKind.PHOTO, origin: MediaOrigin = MediaOrigin.LIBRARY, tags: Map<String, String> = emptyMap(), write: Boolean = true): Long {
        val file = File(storage.root, "2026/2026-10-08/$name").apply { parentFile!!.mkdirs() }
        if (write && kind == MediaKind.PHOTO) {
            file.outputStream().use { Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file).apply { tags.forEach { (tag, value) -> setAttribute(tag, value) }; saveAttributes() }
        } else if (write) {
            file.writeText("video")
        }
        return runBlocking {
            database.mediaDao().insert(MediaItemEntity(path = file.path, fileName = name, kind = kind, sizeBytes = file.length(), capturedAt = 1, importedAt = 1, origin = origin))
        }
    }

    @Test
    fun readsHowAPhotoWasTakenOnceAndTakesTheEquivalentFocalLength() = runBlocking {
        val id = add(
            "DSC00001.JPG",
            tags = mapOf(
                ExifInterface.TAG_MODEL to " ZV-E10 ",
                ExifInterface.TAG_LENS_MODEL to "E PZ 18-105mm F4 G OSS",
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "1600",
                ExifInterface.TAG_FOCAL_LENGTH to "23/1",
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "35",
            ),
        )
        val plain = add("DSC00002.JPG", tags = mapOf(ExifInterface.TAG_FOCAL_LENGTH to "6/1"))

        assertEquals(2, index.run())

        val photo = database.mediaDao().byId(id)!!
        assertEquals("ZV-E10", photo.camera)
        assertEquals("E PZ 18-105mm F4 G OSS", photo.lens)
        assertEquals(1600, photo.iso)
        assertEquals(35.0, photo.focalLength!!, 0.0)
        assertTrue(photo.exifRead)
        // Without the 35 mm figure the lens's own is taken; what the file does not say stays empty.
        val other = database.mediaDao().byId(plain)!!
        assertEquals(6.0, other.focalLength!!, 0.01)
        assertNull(other.camera)
        assertNull(other.iso)
        // Read once: nothing is left for the next time.
        assertEquals(0, index.run())
        assertEquals(listOf("ZV-E10"), database.mediaDao().observeCameras().first().map { it.name })
    }

    @Test
    fun countsTheCamerasAndLensesOfThePhotosThatAreNotInTheTrash() = runBlocking {
        add("DSC00001.JPG", tags = mapOf(ExifInterface.TAG_MODEL to "ZV-E10"))
        add("DSC00002.JPG", tags = mapOf(ExifInterface.TAG_MODEL to "ZV-E10"))
        val gone = add("DSC00003.JPG", tags = mapOf(ExifInterface.TAG_MODEL to "SM-S928N"))
        index.run()
        database.mediaDao().setPlace(gone, File(storage.root, "2026/2026-10-08/DSC00003.JPG").path, 5L, "/x")

        val cameras = database.mediaDao().observeCameras().first()
        assertEquals(listOf("ZV-E10" to 2), cameras.map { it.name to it.count })
    }

    @Test
    fun marksVideosAsReadWithoutOpeningThemAndLeavesWhatCannotBeReadForLater() = runBlocking {
        val video = add("MOV00001.MP4", kind = MediaKind.VIDEO)
        val phone = add("20261008_1.jpg", origin = MediaOrigin.PHONE, tags = mapOf(ExifInterface.TAG_MODEL to "SM-S928N"))
        // A file that is not there just now may be on storage that is not reachable: not given up on.
        val missing = add("DSC00009.JPG", write = false)

        // Without the permission to read other apps' photos, the phone's are left alone.
        assertEquals(1, index.run(canReadPhoneFiles = false))
        assertTrue(database.mediaDao().byId(video)!!.exifRead)
        assertFalse(database.mediaDao().byId(phone)!!.exifRead)
        assertFalse(database.mediaDao().byId(missing)!!.exifRead)

        assertEquals(1, index.run(canReadPhoneFiles = true))
        assertEquals("SM-S928N", database.mediaDao().byId(phone)!!.camera)
    }
}
