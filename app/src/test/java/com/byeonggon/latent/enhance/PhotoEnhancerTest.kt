package com.byeonggon.latent.enhance

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
    private val autoEnhanced = PhotoEdit(autoEnhance = true)

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

        PhotoEnhancer.saveEdited(photo, backup, autoEnhanced)

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
    fun appliesWhatTheSlidersSay() {
        val before = BitmapFactory.decodeFile(photo.path)

        PhotoEnhancer.saveEdited(photo, backup, PhotoEdit(Adjustments(exposure = -1f)))

        val after = BitmapFactory.decodeFile(photo.path)
        assertTrue((after.getPixel(48, 32) and 0xFF) < (before.getPixel(48, 32) and 0xFF) - 20)
    }

    @Test
    fun loadsThePhotoUprightAndNoLargerThanAsked() {
        // Stored 96 wide and 64 high, to be turned on its side for viewing.
        val whole = PhotoEnhancer.load(photo, maxEdge = 1000)
        val small = PhotoEnhancer.load(photo, maxEdge = 48)

        assertEquals(64 to 96, whole.width to whole.height)
        assertEquals(32 to 48, small.width to small.height)
    }

    @Test
    fun cutsAPhotoWhoseExifPointsFarOutsideItself() {
        // A picture that decodes, with an Exif block whose directory offset, added up in Int, wrapped round.
        val picture = File(folder, "DAMAGED.JPG")
        val encoded = java.io.ByteArrayOutputStream().also { Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val tiff = "II".toByteArray() + byteArrayOf(42, 0, 0xF5.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        picture.writeBytes(encoded.copyOfRange(0, 2) + app1 + encoded.copyOfRange(2, encoded.size))
        val cut = File(folder, "DAMAGED_cut.jpg")

        PhotoEnhancer.exportEdited(picture, cut, PhotoEdit(framing = Framing(bottom = 0.5f)))

        assertEquals(32 to 12, BitmapFactory.decodeFile(cut.path).let { it.width to it.height })
    }

    @Test
    fun cutsAndTurnsThePhotoAsItIsSeenUpright() {
        // Stored 96 wide and 64 high and shown on its side: 64 wide and 96 high, of which the top half is kept.
        PhotoEnhancer.saveEdited(photo, backup, PhotoEdit(framing = Framing(bottom = 0.5f)))

        val after = BitmapFactory.decodeFile(photo.path)
        assertEquals(64 to 48, after.width to after.height)
        val exif = ExifInterface(photo)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("SONY", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(42.5, exif.latLong!![0], 1e-4)
        // The brightest corner of the stored picture, bottom right, is at the bottom left once upright, and was cut off.
        val brightest = (0 until 48).maxOf { y -> (0 until 64).maxOf { x -> after.getPixel(x, y) and 0xFF } }
        assertTrue("brightest is $brightest", brightest < 122)

        PhotoEnhancer.saveEdited(photo, backup, PhotoEdit(framing = Framing(quarterTurns = 1)))
        val turned = BitmapFactory.decodeFile(photo.path)
        assertEquals(96 to 64, turned.width to turned.height)
    }

    @Test
    fun keepsWhatTheCameraRecordedAboutThePhoto() {
        PhotoEnhancer.saveEdited(photo, backup, autoEnhanced)

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
        PhotoEnhancer.saveEdited(photo, backup, autoEnhanced)
        val once = photo.readBytes()

        PhotoEnhancer.saveEdited(photo, backup, autoEnhanced)

        assertArrayEquals(original, backup.readBytes())
        assertArrayEquals(once, photo.readBytes())
    }

    @Test
    fun bringsTheOriginalBack() {
        val original = photo.readBytes()
        PhotoEnhancer.saveEdited(photo, backup, autoEnhanced)

        PhotoEnhancer.restoreOriginal(photo, backup)

        assertArrayEquals(original, photo.readBytes())
        assertFalse(backup.exists())
        assertEquals(listOf("DSC00001.JPG"), folder.listFiles { file -> file.isFile }!!.map { it.name })
    }

    @Test
    fun leavesThePhotoAloneWhenItCannotBeDecoded() {
        photo.writeText("not a picture")

        assertThrows(IOException::class.java) { PhotoEnhancer.saveEdited(photo, backup, autoEnhanced) }

        assertEquals("not a picture", photo.readText())
        assertFalse(backup.exists())
    }
}
