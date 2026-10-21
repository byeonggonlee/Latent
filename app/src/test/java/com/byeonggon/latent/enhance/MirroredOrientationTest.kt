package com.byeonggon.latent.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every Exif orientation, the mirrored ones (2, 4, 5 and 7) too, the way the thumbnails show it: the editor's
 * picture, a part of it at full size and a reframed copy all have the stored picture's quarters where the person
 * sees them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MirroredOrientationTest {
    private val folder = File(RuntimeEnvironment.getApplication().cacheDir, "orientation")
    private val photo = File(folder, "DSC00002.JPG")
    private val backup = File(folder, ".originals/DSC00002.JPG")

    /** The grey of each quarter of the stored picture: top left, top right, bottom left, bottom right. */
    private val quarters = intArrayOf(30, 90, 150, 220)

    @Before
    fun writePhoto() {
        folder.deleteRecursively()
        folder.mkdirs()
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val level = quarters[(if (y < HEIGHT / 2) 0 else 2) + (if (x < WIDTH / 2) 0 else 1)]
            bitmap.setPixel(x, y, (0xFF shl 24) or (level shl 16) or (level shl 8) or level)
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
    }

    private fun orient(value: Int) = ExifInterface(photo).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, value.toString())
        saveAttributes()
    }

    /**
     * Which stored quarter Exif puts at each quarter of the upright picture (top left, top right, bottom left, bottom
     * right), from where the orientation says the stored picture's first row and column go.
     */
    private fun expectedQuarters(orientation: Int): List<Int> {
        // The stored position (column, row) of a 2 by 2 picture that each upright position shows.
        val storedAt: (Int, Int) -> Pair<Int, Int> = when (orientation) {
            2 -> { x, y -> 1 - x to y }
            3 -> { x, y -> 1 - x to 1 - y }
            4 -> { x, y -> x to 1 - y }
            5 -> { x, y -> y to x }
            6 -> { x, y -> y to 1 - x }
            7 -> { x, y -> 1 - y to 1 - x }
            8 -> { x, y -> 1 - y to x }
            else -> { x, y -> x to y }
        }
        return listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (x, y) -> storedAt(x, y).let { (sx, sy) -> sy * 2 + sx } }
    }

    /** Which stored quarter each quarter of [image] shows, by its brightness. */
    private fun quartersOf(width: Int, height: Int, level: (x: Int, y: Int) -> Int): List<Int> {
        val reference = LinearImage.fromArgb(IntArray(4) { 0xFF shl 24 or (quarters[it] * 0x010101) }, 4, 1).red
        return listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (qx, qy) ->
            val seen = level(qx * width / 4, qy * height / 4)
            reference.indices.minBy { kotlin.math.abs(reference[it] - seen) }
        }
    }

    private fun quartersOf(image: LinearImage) = quartersOf(image.width, image.height) { x, y -> image.red[y * image.width + x].toInt() }

    @Test
    fun opensEachOrientationAsTheThumbnailsShowIt() {
        for (value in 1..8) {
            orient(value)
            val image = PhotoEnhancer.load(photo, maxEdge = 1000)
            val sideways = value >= 5
            assertEquals("size, orientation $value", if (sideways) HEIGHT to WIDTH else WIDTH to HEIGHT, image.width to image.height)
            assertEquals("quarters, orientation $value", expectedQuarters(value), quartersOf(image))
        }
    }

    @Test
    fun showsAPartAtFullSizeWhereItIsSeen() {
        for (value in 1..8) {
            orient(value)
            val sideways = value >= 5
            val frameWidth = if (sideways) HEIGHT else WIDTH
            val frameHeight = if (sideways) WIDTH else HEIGHT
            // A part around the middle of the upright picture's top left quarter.
            val region = PhotoEnhancer.region(photo, 0.25f, 0.25f, size = 16)
            assertEquals("frame, orientation $value", frameWidth to frameHeight, region.frameWidth to region.frameHeight)
            val middle = region.image.red[region.image.height / 2 * region.image.width + region.image.width / 2].toInt()
            val reference = LinearImage.fromArgb(IntArray(4) { 0xFF shl 24 or (quarters[it] * 0x010101) }, 4, 1).red
            val seen = reference.indices.minBy { kotlin.math.abs(reference[it] - middle) }
            assertEquals("top left, orientation $value", expectedQuarters(value)[0], seen)
        }
    }

    @Test
    fun reframesTheUprightPictureAndSaysItIsUpright() {
        for (value in 1..8) {
            writePhoto()
            orient(value)
            // The top half of the upright picture: its top left and top right quarters.
            PhotoEnhancer.saveEdited(photo, backup, PhotoEdit(framing = Framing(bottom = 0.5f)))

            val after = BitmapFactory.decodeFile(photo.path)
            val sideways = value >= 5
            assertEquals("size, orientation $value", if (sideways) HEIGHT to WIDTH / 2 else WIDTH to HEIGHT / 2, after.width to after.height)
            assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(photo).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            val left = after.getPixel(after.width / 4, after.height / 2) and 0xFF
            val right = after.getPixel(after.width * 3 / 4, after.height / 2) and 0xFF
            val nearest = { grey: Int -> quarters.indices.minBy { kotlin.math.abs(quarters[it] - grey) } }
            assertEquals("top quarters, orientation $value", expectedQuarters(value).take(2), listOf(nearest(left), nearest(right)))
            backup.delete()
        }
    }

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 64
    }
}
