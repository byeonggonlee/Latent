package com.byeonggon.cameramanager.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val thumbnails = Thumbnails(RuntimeEnvironment.getApplication())

    private fun jpeg(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /**
     * A JPEG as a camera writes it: [primary] with an MPF block listing [preview], which follows it,
     * as an image of [previewType].
     */
    private fun withMpf(primary: ByteArray, preview: ByteArray, previewType: Int = 0x010001): ByteArray {
        // SOI, then APP2: marker, length, "MPF\0", and a little-endian TIFF block with one IFD entry.
        val tiffBytes = 8 + 2 + 12 + 4 + 32
        val segmentLength = 2 + 4 + tiffBytes
        val tiffStart = 2 + 2 + 2 + 4
        val previewStart = primary.size + 2 + segmentLength
        val tiff = ByteBuffer.allocate(tiffBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("II".toByteArray()); putShort(42); putInt(8)
            putShort(1)
            putShort(0xB002.toShort()); putShort(7); putInt(32); putInt(8 + 2 + 12 + 4)
            putInt(0)
            // The photo itself, then the preview, its place counted from the TIFF block.
            putInt(0x20030000); putInt(primary.size + 2 + segmentLength); putInt(0); putInt(0)
            putInt(previewType); putInt(preview.size); putInt(previewStart - tiffStart); putInt(0)
        }.array()
        val segment = ByteArrayOutputStream().apply {
            write(0xFF); write(0xE2); write(segmentLength shr 8); write(segmentLength and 0xFF)
            write("MPF\u0000".toByteArray(Charsets.ISO_8859_1)); write(tiff)
        }.toByteArray()
        return primary.copyOfRange(0, 2) + segment + primary.copyOfRange(2, primary.size) + preview
    }

    private fun file(name: String, bytes: ByteArray) = temporary.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun findsTheCamerasPreviewInsideAJpeg() {
        val preview = jpeg(64, 48, 0xFF2040C0.toInt())
        val photo = file("camera.jpg", withMpf(jpeg(320, 240, 0xFFC04020.toInt()), preview))

        assertArrayEquals(preview, MpfPreview.of(photo))
    }

    @Test
    fun takesNeitherAGainMapNorAPlainJpegForAPreview() {
        // An HDR photo lists its gain map in the same block, as an image of no particular type.
        val hdr = file("hdr.jpg", withMpf(jpeg(320, 240, 0xFFC04020.toInt()), jpeg(64, 48, 0xFF808080.toInt()), previewType = 0))
        val plain = file("plain.jpg", jpeg(320, 240, 0xFFC04020.toInt()))

        assertNull(MpfPreview.of(hdr))
        assertNull(MpfPreview.of(plain))
    }

    @Test
    fun makesAThumbnailFromThePreviewOnce() {
        // The preview has a colour of its own, which shows which picture the thumbnail came from.
        val photo = file("camera.jpg", withMpf(jpeg(1200, 800, 0xFFC04020.toInt()), jpeg(900, 600, 0xFF2040C0.toInt())))

        val thumbnail = runBlocking { thumbnails.of(photo, photo.length()) }!!
        val made = thumbnail.lastModified()
        val bitmap = BitmapFactory.decodeFile(thumbnail.path)
        assertEquals(400, minOf(bitmap.width, bitmap.height))
        assertTrue("from the preview", (bitmap.getPixel(10, 10) and 0xFF) > 0x80)

        assertEquals(thumbnail, runBlocking { thumbnails.of(photo, photo.length()) })
        assertEquals(made, thumbnail.lastModified())
    }

    @Test
    fun turnsAThumbnailTheWayTheCameraWasHeld() {
        val photo = file("portrait.jpg", jpeg(1200, 800, 0xFF406080.toInt()))
        ExifInterface(photo).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val bitmap = BitmapFactory.decodeFile(runBlocking { thumbnails.of(photo, photo.length()) }!!.path)
        assertTrue("${bitmap.width}x${bitmap.height}", bitmap.height > bitmap.width)
    }

    @Test
    fun dropsTheThumbnailsOfPhotosThatChanged() {
        val photo = file("photo.jpg", jpeg(800, 600, 0xFF406080.toInt()))
        val before = runBlocking { thumbnails.of(photo, photo.length()) }!!
        photo.writeBytes(jpeg(800, 600, 0xFF804060.toInt()) + ByteArray(10))
        val after = runBlocking { thumbnails.of(photo, photo.length()) }!!

        thumbnails.keepOnly(listOf(thumbnails.fileFor(photo.path, photo.length())))

        assertFalse(before.exists())
        assertTrue(after.exists())
    }
}
