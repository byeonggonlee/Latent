package com.byeonggon.cameramanager.raw

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.byeonggon.cameramanager.enhance.Framing
import com.byeonggon.cameramanager.enhance.PhotoEdit
import org.junit.Assert.assertEquals
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
}
