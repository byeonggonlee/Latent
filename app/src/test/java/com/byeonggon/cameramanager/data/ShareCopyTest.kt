package com.byeonggon.cameramanager.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.cameramanager.appContainer
import com.byeonggon.cameramanager.data.db.MediaItemEntity
import com.byeonggon.cameramanager.data.db.MediaKind
import com.byeonggon.cameramanager.raw.SyntheticArw
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCopyTest {
    private val container get() = RuntimeEnvironment.getApplication().appContainer
    private lateinit var photo: MediaItemEntity
    private lateinit var raw: MediaItemEntity

    @Before
    fun writeLibrary() {
        ShadowEnvironment.addExternalDir("shared")
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val folder = File(container.storage.root, "2026/2026-10-08").apply { mkdirs() }
        val photoFile = File(folder, "DSC00001.JPG")
        Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
            .let { bitmap -> photoFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        ExifInterface(photoFile).apply {
            setAttribute(ExifInterface.TAG_MODEL, "ZV-E10")
            saveAttributes()
        }
        val rawFile = File(folder, "DSC00002.ARW").apply { writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700, orientation = 6)) }
        photo = MediaItemEntity(path = photoFile.path, fileName = photoFile.name, kind = MediaKind.PHOTO, sizeBytes = photoFile.length(), capturedAt = 1, importedAt = 1, latitude = 37.5, longitude = 127.0)
        raw = photo.copy(path = rawFile.path, fileName = rawFile.name, kind = MediaKind.RAW, sizeBytes = rawFile.length())
    }

    private fun sizeOf(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(file.path, it) }
        .let { it.outWidth to it.outHeight }

    @Test
    fun sharesAJpegAsItIsAtFullSize() {
        assertSame(File(photo.path).path, runBlocking { container.libraryEdits.shareCopy(photo, null) }.path)
    }

    @Test
    fun sharesASmallerCopyOfAJpegWithWhatTheCameraRecorded() {
        val copy = runBlocking { container.libraryEdits.shareCopy(photo, 1080) }

        assertNotEquals(photo.path, copy.path)
        assertEquals(1080 to 720, sizeOf(copy))
        val exif = ExifInterface(copy)
        assertEquals("ZV-E10", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(37.5, exif.latLong!![0], 1e-4)
    }

    @Test
    fun sharesARawFileDevelopedUpright() {
        val copy = runBlocking { container.libraryEdits.shareCopy(raw, 2048) }

        // Taken on its side: half the sensor's 56 by 8 pixels, stood upright.
        assertEquals(4 to 28, sizeOf(copy))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(copy).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("DSC00002.jpg", copy.name)
    }
}
