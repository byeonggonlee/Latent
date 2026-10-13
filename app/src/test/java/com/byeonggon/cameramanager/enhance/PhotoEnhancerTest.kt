package com.byeonggon.cameramanager.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEnhancerTest {
    private val folder = File(RuntimeEnvironment.getApplication().cacheDir, "photos")
    private val photo = File(folder, "DSC00001.JPG")
    private val backup = File(folder, ".originals/DSC00001.JPG")

    @Before
    fun writeCameraPhoto() {
        folder.deleteRecursively()
        folder.mkdirs()
        // A dull, low-contrast picture: something for the enhancement to change.
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 96) {
            val level = 90 + (x + y) / 4
            bitmap.setPixel(x, y, (0xFF shl 24) or (level shl 16) or (level shl 8) or level)
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(photo).apply {
            setAttribute(ExifInterface.TAG_MAKE, "SONY")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:08 10:42:43")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(42.5, 141.25)
            saveAttributes()
        }
    }

    @Test
    fun replacesThePhotoWithItsEnhancedVersionAndKeepsTheOriginal() {
        val original = photo.readBytes()

        PhotoEnhancer.enhanceInPlace(photo, backup)

        assertArrayEquals(original, backup.readBytes())
        assertFalse(photo.readBytes().contentEquals(original))
        val before = BitmapFactory.decodeByteArray(original, 0, original.size)
        val after = BitmapFactory.decodeFile(photo.path)
        assertEquals(before.width to before.height, after.width to after.height)
        // The darkest corner got darker and the brightest brighter.
        assertTrue((after.getPixel(1, 1) and 0xFF) < (before.getPixel(1, 1) and 0xFF))
        assertTrue((after.getPixel(94, 62) and 0xFF) > (before.getPixel(94, 62) and 0xFF))
    }

    @Test
    fun keepsWhatTheCameraRecordedAboutThePhoto() {
        PhotoEnhancer.enhanceInPlace(photo, backup)

        val exif = ExifInterface(photo)
        assertEquals("SONY", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("2026:10:08 10:42:43", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(42.5, exif.latLong!![0], 1e-4)
        assertEquals(141.25, exif.latLong!![1], 1e-4)
    }

    @Test
    fun enhancingAgainStartsOverFromTheOriginal() {
        val original = photo.readBytes()
        PhotoEnhancer.enhanceInPlace(photo, backup)
        val once = photo.readBytes()

        PhotoEnhancer.enhanceInPlace(photo, backup)

        assertArrayEquals(original, backup.readBytes())
        assertArrayEquals(once, photo.readBytes())
    }

    @Test
    fun bringsTheOriginalBack() {
        val original = photo.readBytes()
        PhotoEnhancer.enhanceInPlace(photo, backup)

        PhotoEnhancer.restoreOriginal(photo, backup)

        assertArrayEquals(original, photo.readBytes())
        assertFalse(backup.exists())
        assertEquals(listOf("DSC00001.JPG"), folder.listFiles { file -> file.isFile }!!.map { it.name })
    }

    @Test
    fun leavesThePhotoAloneWhenItCannotBeDecoded() {
        photo.writeText("not a picture")

        assertThrows(IOException::class.java) { PhotoEnhancer.enhanceInPlace(photo, backup) }

        assertEquals("not a picture", photo.readText())
        assertFalse(backup.exists())
    }
}
