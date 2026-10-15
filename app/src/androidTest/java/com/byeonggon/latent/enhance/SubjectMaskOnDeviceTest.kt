package com.byeonggon.latent.enhance

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The bundled subject model run by LiteRT on the phone itself: what it takes and gives, read from the
 * model, and whether it tells a plain subject from its background, on the graphics processor and on the
 * processor's cores, each timed cold (set up and first run) and warm. The times are logged under
 * `SubjectMask`; `adb logcat -s SubjectMask SubjectMaskOnDeviceTest` shows them. With
 * `-e jpegSample <path>`, a real photo is also outlined on both, and the outlines compared.
 */
@RunWith(AndroidJUnit4::class)
class SubjectMaskOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample = InstrumentationRegistry.getArguments().getString("jpegSample")?.let(::File)
    private val appMasks = MaskStore.directory

    @After
    fun backToTheAppsOwn() {
        MaskStore.directory = appMasks
    }

    /** A bright orange disc, a little left of the middle, on a dull blue-gray ground, as the camera shows it. */
    private fun disc(width: Int = 1024, height: Int = 683): ShownPicture {
        val red = FloatArray(width * height)
        val green = FloatArray(width * height)
        val blue = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val at = y * width + x
            val inside = hypot(x - width * 0.45f, y - height * 0.5f) < height * 0.3f
            red[at] = if (inside) 0.95f else 0.35f
            green[at] = if (inside) 0.55f else 0.4f
            blue[at] = if (inside) 0.2f else 0.45f + 0.1f * y / height
        }
        return ShownPicture(width, height, red, green, blue)
    }

    private fun millis(since: Long) = (System.nanoTime() - since) / 1_000_000

    @Test
    fun readsTheModelsShapesAndFindsAPlainSubjectOnEachAccelerator() {
        val picture = disc()
        val answers = HashMap<SegmenterAccelerator, Segmentation>()
        for (accelerator in SegmenterAccelerator.entries) {
            val started = System.nanoTime()
            val segmenter = try {
                LiteRtSegmenter(context.assets, listOf(accelerator))
            } catch (e: SegmenterException) {
                // The graphics processor may not be usable on every phone; the processor's cores always are.
                assertTrue("The model must run on the processor's cores: $e", accelerator != SegmenterAccelerator.CPU)
                Log.w(TAG, "$accelerator: cannot be set up, ${e.message}")
                continue
            }
            segmenter.use {
                val setUp = millis(started)
                assertEquals(accelerator, it.accelerator)
                // U2-Netp as converted: 1 x 320 x 320 x 3 floats in, 1 x 320 x 320 x 1 out.
                assertEquals(320, it.inputWidth)
                assertEquals(320, it.inputHeight)
                val first = System.nanoTime()
                val answer = it.segment(picture)
                val cold = millis(first)
                val warm = (1..5).map { _ ->
                    val again = System.nanoTime()
                    it.segment(picture)
                    millis(again)
                }
                Log.i(TAG, "$accelerator: set up $setUp ms, first run $cold ms, warm runs $warm ms (median ${warm.sorted()[2]} ms)")

                assertEquals(320, answer.width)
                assertEquals(320, answer.height)
                assertEquals(320 * 320, answer.values.size)
                // The model ends in a sigmoid: everything from 0 to 1.
                assertTrue(answer.values.all { value -> value in 0f..1f })
                val centre = answer.values[160 * 320 + 144]
                val corner = answer.values[10 * 320 + 10]
                Log.i(TAG, "$accelerator: centre of the disc $centre, corner $corner, range ${answer.values.min()} to ${answer.values.max()}")
                assertTrue("centre $centre", centre > 0.5f)
                assertTrue("corner $corner", corner < 0.5f)
                answers[accelerator] = answer
            }
        }
        val gpu = answers[SegmenterAccelerator.GPU]
        val cpu = answers[SegmenterAccelerator.CPU]
        if (gpu != null && cpu != null) {
            val apart = gpu.values.indices.maxOf { abs(gpu.values[it] - cpu.values[it]) }
            val mean = gpu.values.indices.sumOf { abs(gpu.values[it] - cpu.values[it]).toDouble() } / gpu.values.size
            Log.i(TAG, "GPU against CPU: largest difference $apart, mean ${"%.5f".format(mean)}")
            assertTrue("mean difference $mean", mean < 0.02)
        }
    }

    @Test
    fun outlinesARealPhotoTheSameOnEachAccelerator() {
        val file = sample
        if (file == null) {
            Log.i(TAG, "No photo given; pass -e jpegSample <path> to outline one")
            return
        }
        MaskStore.directory = File(context.cacheDir, "subject-test").apply { mkdirs() }
        val picture = PhotoEnhancer.load(file, 1600)
        val outlines = SegmenterAccelerator.entries.mapNotNull { accelerator ->
            val segmenter = try {
                LiteRtSegmenter(context.assets, listOf(accelerator))
            } catch (e: SegmenterException) {
                return@mapNotNull null
            }
            segmenter.use {
                val started = System.nanoTime()
                val outline = SubjectMasks.outline(picture, it)
                Log.i(TAG, "$accelerator: ${file.name} ${picture.width}x${picture.height} outlined in ${millis(started)} ms, " + (outline?.let { found -> "${found.width}x${found.height}, covering ${"%.1f".format(found.coverage().average() * 100)}%" } ?: "nothing found"))
                outline
            }
        }
        if (outlines.size == 2) {
            val (first, second) = outlines
            val apart = first.levels.indices.count { abs((first.levels[it].toInt() and 0xFF) - (second.levels[it].toInt() and 0xFF)) > 64 }
            Log.i(TAG, "GPU and CPU outlines differ by more than a quarter in ${"%.2f".format(apart * 100.0 / first.levels.size)}% of cells")
            assertTrue(apart < first.levels.size / 50)
        }
    }

    private companion object {
        const val TAG = "SubjectMaskOnDeviceTest"
    }
}
