package com.byeonggon.latent.enhance

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Tones rendered on the graphics processor against the same on the processor, on a real photo, for
 * edits that use every kind of step: the pictures have to agree. Pass the photo with
 * `-e jpegSample <path>`; nothing is written.
 */
@RunWith(AndroidJUnit4::class)
class GpuToneOnDeviceTest {
    private val sample = InstrumentationRegistry.getArguments().getString("jpegSample")?.let(::File)

    @After
    fun backToDefault() {
        ToneRenderer.useGraphicsProcessor = true
    }

    private val tones = Adjustments(
        exposure = 0.3f, contrast = 0.2f, highlights = -0.6f, shadows = 0.6f, whites = 0.1f, blacks = -0.1f, temperature = 0.1f,
        vibrance = 0.4f, clarity = 0.3f, dehaze = 0.2f, vignette = -0.3f, grain = 0.3f,
    )

    private val everything = PhotoEdit(
        tones,
        autoEnhance = true,
        curves = ToneCurves(all = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 0.97f)))),
        mixer = ColorMixer(saturation = List(MixerBand.entries.size) { if (it == MixerBand.BLUE.ordinal) 0.3f else 0f }, hue = List(MixerBand.entries.size) { if (it == MixerBand.GREEN.ordinal) -0.4f else 0f }),
        grading = ColorGrading(shadows = Tint(210f, 0.3f), highlights = Tint(40f, 0.3f)),
        locals = listOf(
            LocalEdit(Mask(MaskShape.LINEAR, 0.5f, 0.2f, 0.5f, 0.6f), Adjustments(exposure = -0.7f, dehaze = 0.3f)),
            LocalEdit(Mask(MaskShape.RADIAL, 0.5f, 0.5f, 0.8f, 0.9f), Adjustments(exposure = 0.4f, clarity = 0.3f)),
        ),
    )

    private val brushed = PhotoEdit(
        Adjustments(contrast = 0.3f, dehaze = -0.4f),
        monochrome = true,
        mixer = ColorMixer(luminance = List(MixerBand.entries.size) { if (it == MixerBand.RED.ordinal) 0.5f else -0.2f }),
        locals = listOf(
            LocalEdit(
                Mask(
                    MaskShape.BRUSH, 0f, 0f, 0f, 0f, feather = 0.6f, inverted = true,
                    strokes = listOf(BrushStroke(listOf(0.2f, 0.3f, 0.5f, 0.4f, 0.7f, 0.35f), radius = 0.08f)),
                ),
                Adjustments(exposure = 0.8f, highlights = 0.5f),
                darkest = 0.2f,
                brightest = 0.8f,
            ),
        ),
    )

    private fun compare(name: String, picture: LinearImage, edit: PhotoEdit) {
        val framed = picture.framed(edit.framing)
        val overview = framed.downscaled(ToneRenderer.OVERVIEW_EDGE)
        val renderer = ToneRenderer(edit, overview)
        ToneRenderer.useGraphicsProcessor = false
        var startedAt = System.nanoTime()
        val cpu = renderer.render(framed)
        val cpuMs = (System.nanoTime() - startedAt) / 1_000_000
        ToneRenderer.useGraphicsProcessor = true
        startedAt = System.nanoTime()
        val gpu = renderer.render(framed)
        val gpuMs = (System.nanoTime() - startedAt) / 1_000_000

        var largest = 0
        var off = 0L
        for (index in cpu.indices) {
            var difference = 0
            for (shift in intArrayOf(0, 8, 16)) difference = max(difference, abs(((cpu[index] ushr shift) and 0xFF) - ((gpu[index] ushr shift) and 0xFF)))
            largest = max(largest, difference)
            if (difference > 1) off++
        }
        val share = off.toDouble() / cpu.size
        Log.i(TAG, "on device: $name ${framed.width}x${framed.height}: processor $cpuMs ms, graphics processor $gpuMs ms; at most $largest levels apart, ${"%.4f".format(share * 100)}% of pixels by more than one")
        assertTrue("$name: largest difference $largest", largest <= 6)
        assertTrue("$name: $off pixels off by more than one", share < 0.001)
    }

    @Test
    fun agreesWithTheProcessor() {
        assumeTrue(sample != null && sample.isFile)
        assumeTrue(Graphics.available)
        val proxy = PhotoEnhancer.load(sample!!, 1600)
        compare("light", proxy, PhotoEdit(Adjustments(exposure = 0.5f, contrast = 0.3f, shadows = 0.4f)))
        compare("everything", proxy, everything)
        compare("brushed black and white", proxy, brushed)
        val levelled = Framing(angle = 3f, vertical = 0.3f, horizontal = -0.2f, flipped = true).largestOf(proxy.width.toFloat() / proxy.height, proxy.width.toFloat(), proxy.height.toFloat())
        compare("levelled and tilted", proxy, everything.copy(framing = levelled))
        compare("turned", proxy, everything.copy(framing = Framing().turnedClockwise()))

        val full = PhotoEnhancer.load(sample, 8000)
        compare("everything at full size", full, everything)
    }

    private companion object {
        const val TAG = "GpuTone"
    }
}
