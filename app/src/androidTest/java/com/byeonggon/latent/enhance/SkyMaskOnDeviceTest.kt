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

/**
 * The bundled sky model run by LiteRT on the phone itself: what it takes and gives, read from the model, and whether it
 * finds a plain sky above the ground, on the graphics processor and on the processor's cores, each timed cold and warm.
 * Its ops include GATHER_ND and BROADCAST_TO, which the graphics processor may not take: where it cannot be set up there,
 * the cores run it, and the log says so. `adb logcat -s SubjectMask SkyMaskOnDeviceTest` shows the times. With
 * `-e jpegSample <path>`, a real photo's sky is also outlined on both, and the outlines compared.
 */
@RunWith(AndroidJUnit4::class)
class SkyMaskOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample = InstrumentationRegistry.getArguments().getString("jpegSample")?.let(::File)
    private val appMasks = MaskStore.directory

    @After
    fun backToTheAppsOwn() {
        MaskStore.directory = appMasks
    }

    /** A blue sky, lighter towards the horizon, over green and gray ground below 45 % of the height, as the camera shows it. */
    private fun landscape(width: Int = 1024, height: Int = 683): ShownPicture {
        val red = FloatArray(width * height)
        val green = FloatArray(width * height)
        val blue = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val at = y * width + x
            val down = y.toFloat() / height
            if (down < 0.45f) {
                red[at] = 0.35f + 0.3f * down
                green[at] = 0.55f + 0.3f * down
                blue[at] = 0.95f
            } else {
                red[at] = if ((x / 40 + y / 40) % 2 == 0) 0.30f else 0.42f
                green[at] = if ((x / 40 + y / 40) % 2 == 0) 0.45f else 0.40f
                blue[at] = 0.30f
            }
        }
        return ShownPicture(width, height, red, green, blue)
    }

    private fun millis(since: Long) = (System.nanoTime() - since) / 1_000_000

    @Test
    fun readsTheModelsShapesAndFindsAPlainSkyOnEachAccelerator() {
        val picture = landscape()
        val answers = HashMap<SegmenterAccelerator, Segmentation>()
        for (accelerator in SegmenterAccelerator.entries) {
            val started = System.nanoTime()
            val segmenter = try {
                LiteRtSegmenter(context.assets, listOf(accelerator), SegmenterModel.SKY)
            } catch (e: SegmenterException) {
                assertTrue("The model must run on the processor's cores: $e", accelerator != SegmenterAccelerator.CPU)
                Log.w(TAG, "$accelerator: cannot be set up, ${e.message}")
                continue
            }
            segmenter.use {
                val setUp = millis(started)
                assertEquals(accelerator, it.accelerator)
                // EfficientViT-Seg B1 as converted: 1 x 512 x 512 x 3 floats in, the sky's probability 1 x 64 x 64 x 1 out.
                assertEquals(512, it.inputWidth)
                assertEquals(512, it.inputHeight)
                val first = System.nanoTime()
                val answer = it.segment(picture)
                val cold = millis(first)
                val warm = (1..5).map { _ ->
                    val again = System.nanoTime()
                    it.segment(picture)
                    millis(again)
                }
                Log.i(TAG, "$accelerator: set up $setUp ms, first run $cold ms, warm runs $warm ms (median ${warm.sorted()[2]} ms)")

                assertEquals(64, answer.width)
                assertEquals(64, answer.height)
                // A softmax's share: everything from 0 to 1.
                assertTrue(answer.values.all { value -> value in 0f..1f })
                val sky = answer.values[8 * 64 + 32]
                val ground = answer.values[56 * 64 + 32]
                Log.i(TAG, "$accelerator: sky $sky, ground $ground, range ${answer.values.min()} to ${answer.values.max()}")
                assertTrue("sky $sky", sky > 0.5f)
                assertTrue("ground $ground", ground < 0.5f)
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
    fun outlinesARealPhotosSkyTheSameOnEachAccelerator() {
        val file = sample
        if (file == null) {
            Log.i(TAG, "No photo given; pass -e jpegSample <path> to outline one")
            return
        }
        MaskStore.directory = File(context.cacheDir, "sky-test").apply { mkdirs() }
        val picture = PhotoEnhancer.load(file, 1600)
        val outlines = SegmenterAccelerator.entries.mapNotNull { accelerator ->
            val segmenter = try {
                LiteRtSegmenter(context.assets, listOf(accelerator), SegmenterModel.SKY)
            } catch (e: SegmenterException) {
                return@mapNotNull null
            }
            segmenter.use {
                val started = System.nanoTime()
                val outline = SubjectMasks.outline(picture, it, DetectedKind.SKY)
                Log.i(TAG, "$accelerator: ${file.name} ${picture.width}x${picture.height} sky in ${millis(started)} ms, " + (outline?.let { found -> "${found.width}x${found.height}, covering ${"%.1f".format(found.coverage().average() * 100)}%" } ?: "no sky"))
                outline
            }
        }
        if (outlines.size == 2) {
            val (first, second) = outlines
            val apart = first.levels.indices.count { abs((first.levels[it].toInt() and 0xFF) - (second.levels[it].toInt() and 0xFF)) > 64 }
            Log.i(TAG, "GPU and CPU skies differ by more than a quarter in ${"%.2f".format(apart * 100.0 / first.levels.size)}% of cells")
            assertTrue(apart < first.levels.size / 50)
        }
    }

    private companion object {
        const val TAG = "SkyMaskOnDeviceTest"
    }
}
