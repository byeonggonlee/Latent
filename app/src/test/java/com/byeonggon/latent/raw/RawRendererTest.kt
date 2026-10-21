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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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
