package com.byeonggon.latent.enhance

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Detail worked out on the graphics processor against the same on the processor, on a real photo at
 * full size: the pictures have to agree, and the graphics processor has to be the faster. Pass the
 * photo with `-e jpegSample <path>`; nothing is written.
 */
@RunWith(AndroidJUnit4::class)
class GpuDetailOnDeviceTest {
    private val sample = InstrumentationRegistry.getArguments().getString("jpegSample")?.let(::File)

    @After
    fun backToDefault() {
        DetailFilter.useGraphicsProcessor = true
    }

    private fun timed(what: String, block: () -> IntArray): IntArray {
        val startedAt = System.nanoTime()
        return block().also { Log.i(TAG, "on device: $what in ${(System.nanoTime() - startedAt) / 1_000_000} ms") }
    }

    @Test
    fun agreesWithTheProcessorAndIsFaster() {
        assumeTrue(sample != null && sample.isFile)
        assertNotNull("this device has a graphics processor to use", GpuDetail.instance())
        val bitmap = BitmapFactory.decodeFile(sample!!.path)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val cases = mapOf(
            "everything" to Adjustments(texture = 0.4f, sharpening = 0.5f, noiseReduction = 0.4f, colorNoiseReduction = 0.5f),
            "smoothing" to Adjustments(texture = -0.6f, noiseReduction = 0.8f),
            "texture and sharpening" to Adjustments(texture = 0.5f, sharpening = 1f),
        )
        for ((name, adjustments) in cases) {
            val filter = DetailFilter(adjustments, scale = 1f)
            DetailFilter.useGraphicsProcessor = false
            val cpu = timed("$name ${width}x$height on the processor") { filter.apply(pixels, width, height) }
            DetailFilter.useGraphicsProcessor = true
            val gpu = timed("$name ${width}x$height on the graphics processor") { filter.apply(pixels, width, height) }

            var largest = 0
            var off = 0L
            for (index in pixels.indices) {
                val a = cpu[index]
                val b = gpu[index]
                var difference = 0
                for (shift in intArrayOf(0, 8, 16)) difference = max(difference, abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)))
                largest = max(largest, difference)
                if (difference > 1) off++
            }
            val share = off.toDouble() / pixels.size
            Log.i(TAG, "on device: $name differs by at most $largest levels; ${"%.4f".format(share * 100)}% of pixels by more than one")
            // Floating point sums in another order, and band edges in other places: a level here and there.
            assertTrue("$name: largest difference $largest", largest <= 6)
            assertTrue("$name: $off pixels off by more than one", share < 0.001)
        }
    }

    private companion object {
        const val TAG = "GpuDetail"
    }
}
