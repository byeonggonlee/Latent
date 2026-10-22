package com.byeonggon.latent.camera

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A file copied off a camera or card is the one in the library when it holds the same, a JPEG's EXIF aside. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SameAsImportedTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun jpeg(name: String, grey: Int): File = folder.newFile(name).apply {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() or grey * 0x010101) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(this).apply {
            setAttribute(ExifInterface.TAG_MODEL, "ZV-E10")
            saveAttributes()
        }
    }

    @Test
    fun knowsAJpegWhosePlaceWasWrittenIn() {
        val copy = jpeg("copy.jpg", 0x80)
        val imported = copy.copyTo(File(folder.root, "imported.jpg"))
        ExifInterface(imported).apply {
            setLatLong(37.5, 127.0)
            saveAttributes()
        }

        assertFalse(copy.readBytes().contentEquals(imported.readBytes()))
        assertTrue(sameAsImported(copy, imported))
    }

    @Test
    fun tellsApartTheSamePixelsTurnedAnotherWay() {
        val upright = jpeg("upright.jpg", 0x80)
        val turned = upright.copyTo(File(folder.root, "turned.jpg"))
        ExifInterface(turned).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        assertFalse(sameAsImported(upright, turned))
    }

    @Test
    fun tellsApartTheSamePixelsTakenAtAnotherTime() {
        val first = jpeg("first.jpg", 0x80)
        val later = first.copyTo(File(folder.root, "later.jpg"))
        ExifInterface(later).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:01 12:15:00")
            saveAttributes()
        }

        assertFalse(sameAsImported(first, later))
    }

    @Test
    fun tellsApartJpegsWithTheSameExifAndOtherPictures() {
        assertFalse(sameAsImported(jpeg("a.jpg", 0x80), jpeg("b.jpg", 0x81)))
    }

    @Test
    fun comparesOtherFilesWhole() {
        val a = folder.newFile("a.arw").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val b = folder.newFile("b.arw").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val c = folder.newFile("c.arw").apply { writeBytes(byteArrayOf(9, 2, 3, 4)) }

        assertTrue(sameAsImported(a, b))
        assertFalse(sameAsImported(a, c))
    }
}
