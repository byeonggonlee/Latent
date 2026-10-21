package com.byeonggon.latent.raw

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.latent.enhance.Adjustments
import com.byeonggon.latent.enhance.DetectedKind
import com.byeonggon.latent.enhance.Detection
import com.byeonggon.latent.enhance.Framing
import com.byeonggon.latent.enhance.LocalEdit
import com.byeonggon.latent.enhance.Mask
import com.byeonggon.latent.enhance.MaskRaster
import com.byeonggon.latent.enhance.MaskShape
import com.byeonggon.latent.enhance.MaskStore
import com.byeonggon.latent.enhance.PhotoEdit
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RawRendererTest {
    private val folder = File(RuntimeEnvironment.getApplication().cacheDir, "raw")

    // The picture is 56 by 8 pixels on the sensor, taken with the camera turned on its side.
    private val raw = File(folder, "DSC00002.ARW")
    private val jpeg = File(folder, "DSC00002.jpg")

    @Before
    fun writeRawFile() {
        folder.deleteRecursively()
        folder.mkdirs()
        raw.writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700, orientation = 6))
    }

    private fun sizeOf(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun showsThePictureUprightAtHalfSize() {
        val preview = RawRenderer.preview(RawRenderer.read(raw), PhotoEdit())

        assertEquals(4 to 28, preview.width to preview.height)
    }

    @Test
    fun asksBetweenRowsWhetherToStopAndStopsWhenTold() {
        var asked = 0
        RawRenderer.read(raw) { asked++ }
        assertTrue("asked $asked times", asked >= SyntheticArw.HEIGHT)

        // Told halfway down the rows.
        asked = 0
        assertThrows(CancellationException::class.java) {
            RawRenderer.read(raw) { if (++asked > SyntheticArw.HEIGHT / 2) throw CancellationException("moved on") }
        }
    }

    @Test
    fun turnsEveryOrientationUprightTheMirroredOnesToo() {
        for (orientation in 1..8) {
            raw.writeBytes(SyntheticArw.file(red = 600, green = 900, blue = 700, orientation = orientation))
            val image = RawRenderer.read(raw)
            val sideways = orientation >= 5

            val preview = RawRenderer.preview(image, PhotoEdit())
            assertEquals("preview, orientation $orientation", if (sideways) 4 to 28 else 28 to 4, preview.width to preview.height)
            val region = RawRenderer.region(image, 0.5f, 0.5f, size = 4)
            assertEquals("frame, orientation $orientation", if (sideways) 8 to 56 else 56 to 8, region.frameWidth to region.frameHeight)
        }
    }

    /**
     * The synthetic raw file with each quarter of its crop at a brightness of its own (top left darkest, bottom right
     * brightest), stored with Exif [orientation].
     */
    private fun quarteredRaw(orientation: Int): ArwImage {
        val plain = SonyArw.read(SyntheticArw.file(red = 600, green = 900, blue = 700))
        val levels = intArrayOf(300, 1200, 3000, 6000)
        val sensor = plain.sensor.copyOf()
        for (y in 0 until plain.cropHeight) for (x in 0 until plain.cropWidth) {
            val quarter = (if (y < plain.cropHeight / 2) 0 else 2) + (if (x < plain.cropWidth / 2) 0 else 1)
            sensor[(plain.cropTop + y) * plain.width + plain.cropLeft + x] = (plain.blackLevel + levels[quarter]).toShort()
        }
        return ArwImage(
            plain.width, plain.height, plain.cropLeft, plain.cropTop, plain.cropWidth, plain.cropHeight, plain.blackLevel,
            plain.whiteLevel, plain.whiteBalance, plain.colorMatrix, plain.saturation, plain.lens, orientation, sensor,
        )
    }

    /** Which stored quarter Exif shows at each quarter of the upright picture (top left, top right, bottom left, bottom right). */
    private fun expectedQuarters(orientation: Int): List<Int> {
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

    /** The quarters of [image] by brightness: 0 for the darkest, 3 for the brightest. */
    private fun quartersOf(image: com.byeonggon.latent.enhance.LinearImage): List<Int> {
        val seen = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (qx, qy) ->
            image.green[qy * image.height / 4 * image.width + qx * image.width / 4].toInt()
        }
        return seen.map { level -> seen.count { it < level } }
    }

    @Test
    fun putsTheQuartersOfAMirroredRawFileWhereTheyAreSeen() {
        for (orientation in 1..8) {
            val image = quarteredRaw(orientation)

            val proxy = RawRenderer.proxy(image, cells = 1, lensCorrection = false)
            assertEquals("preview, orientation $orientation", expectedQuarters(orientation), quartersOf(proxy))

            // A part at full size around the middle of the upright picture's top left and bottom right quarters.
            val topLeft = RawRenderer.region(image, 0.25f, 0.25f, size = 2)
            val bottomRight = RawRenderer.region(image, 0.75f, 0.75f, size = 2)
            val expected = expectedQuarters(orientation)
            val darker = topLeft.image.green[0] < bottomRight.image.green[0]
            assertEquals("detail, orientation $orientation", expected[0] < expected[3], darker)
        }
    }

    @Test
    fun showsOnlyThePartThatIsKept() {
        val preview = RawRenderer.preview(RawRenderer.read(raw), PhotoEdit(framing = Framing(bottom = 0.5f)))

        assertEquals(4 to 14, preview.width to preview.height)
    }

    @Test
    fun exportsThePictureAsTheSensorRecordedItAndSaysHowToTurnIt() {
        RawRenderer.export(raw, jpeg, PhotoEdit())

        assertEquals(56 to 8, sizeOf(jpeg))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(jpeg).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }

    @Test
    fun exportsAReframedPictureUpright() {
        RawRenderer.export(raw, jpeg, PhotoEdit(framing = Framing(bottom = 0.5f)))

        assertEquals(8 to 28, sizeOf(jpeg))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(jpeg).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }

    @Test
    fun exportsADetectedMaskWhereItWasFoundOnTheUprightPicture() {
        // An outline of the top half of the picture as it is shown, upright: 8 across, 56 down.
        val outline = MaskStore.newId()
        MaskStore.save(outline, MaskRaster.of(8, 56, FloatArray(8 * 56) { if (it / 8 < 28) 1f else 0f }))
        val subject = Mask(MaskShape.DETECTED, 0f, 0f, 0f, 0f, feather = 0f, detection = Detection(DetectedKind.SUBJECT, outline, "test", 1))

        RawRenderer.export(raw, jpeg, PhotoEdit(locals = listOf(LocalEdit(subject, Adjustments(exposure = -1f)))))

        assertEquals(8 to 56, sizeOf(jpeg))
        val exported = BitmapFactory.decodeFile(jpeg.path)
        assertTrue((exported.getPixel(4, 6) and 0xFF) < (exported.getPixel(4, 50) and 0xFF) - 20)
    }
}
