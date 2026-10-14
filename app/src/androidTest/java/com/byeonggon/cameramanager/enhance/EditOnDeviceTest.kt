package com.byeonggon.cameramanager.enhance

import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.byeonggon.cameramanager.raw.RawRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Edits real photos on the device, where speed and memory limits differ from a desktop JVM: how fast
 * the preview follows a slider, and whether a full-size picture can be reframed within the app's memory.
 * Pass the files with `-e jpegSample <path>` and `-e arwSample <path>`. They are only read; all results
 * go to the app's cache and are removed again.
 *
 * Run it with `adb shell am instrument`, not with Gradle's connectedAndroidTest: that task uninstalls
 * the app afterwards and takes the library database with it.
 */
@RunWith(AndroidJUnit4::class)
class EditOnDeviceTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val jpegSample = arguments.getString("jpegSample")?.let(::File)
    private val arwSample = arguments.getString("arwSample")?.let(::File)
    private val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "edit-on-device-test")

    private val tones = Adjustments(
        exposure = 0.3f, contrast = 0.2f, highlights = -0.6f, shadows = 0.6f, whites = 0.1f, blacks = -0.1f, temperature = 0.1f,
        vibrance = 0.4f, texture = 0.3f, clarity = 0.3f, dehaze = 0.2f, vignette = -0.3f, grain = 0.3f,
        sharpening = 0.5f, noiseReduction = 0.3f, colorNoiseReduction = 0.3f,
    )

    /** Every kind of tool at once: the most an edit can ask of the device. */
    private val everything = PhotoEdit(
        tones,
        autoEnhance = true,
        curves = ToneCurves(all = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 0.97f)))),
        mixer = ColorMixer(saturation = List(MixerBand.entries.size) { if (it == MixerBand.BLUE.ordinal) 0.3f else 0f }),
        grading = ColorGrading(shadows = Tint(210f, 0.3f), highlights = Tint(40f, 0.3f)),
        locals = listOf(
            LocalEdit(Mask(MaskShape.LINEAR, 0.5f, 0.2f, 0.5f, 0.6f), Adjustments(exposure = -0.7f, dehaze = 0.3f)),
            LocalEdit(Mask(MaskShape.RADIAL, 0.5f, 0.5f, 0.8f, 0.9f), Adjustments(exposure = 0.4f, clarity = 0.3f)),
        ),
    )

    private inline fun <T> timed(what: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        return block().also { Log.i(TAG, "on device: $what in ${(System.nanoTime() - startedAt) / 1_000_000} ms") }
    }

    private fun sizeOf(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** Draws the picture as the editor does while a slider moves, and logs how long one drawing takes. */
    private fun followASlider(name: String, picture: LinearImage) {
        val overview = picture.downscaled(ToneRenderer.OVERVIEW_EDGE)
        for ((what, base) in listOf("a few sliders" to PhotoEdit(Adjustments(contrast = 0.2f, shadows = 0.4f, vibrance = 0.3f)), "every tool" to everything)) {
            val times = (1..12).map { step ->
                val edit = base.copy(adjustments = base.adjustments.copy(exposure = step / 20f))
                val startedAt = System.nanoTime()
                val pixels = ToneRenderer(edit, overview).render(picture)
                DetailFilter(edit.adjustments, scale = 0.25f).let { if (it.isNeeded) it.apply(pixels, picture.width, picture.height) }
                (System.nanoTime() - startedAt) / 1_000_000
            }
            Log.i(TAG, "on device: $name preview ${picture.width}x${picture.height}, $what, per slider step: ${times.sorted()[times.size / 2]} ms (worst ${times.max()})")
        }
    }

    @Test
    fun editsAJpegWithinTheAppsMemory() {
        assumeTrue(jpegSample != null && jpegSample.isFile)
        folder.deleteRecursively()
        folder.mkdirs()
        val photo = File(folder, "photo.jpg")
        val backup = File(folder, "originals/photo.jpg")
        try {
            jpegSample!!.copyTo(photo)
            val original = sizeOf(photo)
            followASlider("jpeg", timed("jpeg load for editing") { PhotoEnhancer.load(photo, 1600) })

            timed("jpeg save with every tool") { PhotoEnhancer.saveEdited(photo, backup, everything) }
            assertEquals(original, sizeOf(photo))
            timed("jpeg region at full size") { PhotoEnhancer.region(backup, 0.5f, 0.5f, 1024) }

            val levelled = Framing(angle = 3f, vertical = 0.3f).largestOf(1.5f, original.first.toFloat(), original.second.toFloat())
            timed("jpeg save reframed with every tool") { PhotoEnhancer.saveEdited(photo, backup, everything.copy(framing = levelled)) }
            val reframed = sizeOf(photo)
            assertTrue("reframed to $reframed", reframed.first < original.first && reframed.first > original.first * 0.8)
            assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(photo).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertEquals("ZV-E10", ExifInterface(photo).getAttribute(ExifInterface.TAG_MODEL))
        } finally {
            folder.deleteRecursively()
        }
        Log.i(TAG, "on device: heap limit ${Runtime.getRuntime().maxMemory() shr 20} MB")
    }

    @Test
    fun editsARawFileWithinTheAppsMemory() {
        assumeTrue(arwSample != null && arwSample.isFile)
        folder.deleteRecursively()
        folder.mkdirs()
        val target = File(folder, "developed.jpg")
        try {
            val raw = RawRenderer.read(arwSample!!)
            followASlider("raw", timed("raw proxy") { RawRenderer.proxy(raw, cells = 2, lensCorrection = true) })
            timed("raw region at full size") { RawRenderer.region(raw, 0.5f, 0.5f, 1024) }

            val levelled = Framing(angle = 3f, vertical = 0.3f).largestOf(1.5f, 6000f, 4000f)
            timed("raw export reframed with every tool") { RawRenderer.export(arwSample, target, everything.copy(framing = levelled)) }
            val size = sizeOf(target)
            assertTrue("exported $size", maxOf(size.first, size.second) in 5000..5999)
            assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(target).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        } finally {
            folder.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "RawDevelop"
    }
}
