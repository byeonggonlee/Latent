package com.byeonggon.latent.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSettingsTest {
    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    /** A picture of soft vertical bands from [from] to [to] in brightness, tinted by [tint]. */
    private fun bands(from: Int, to: Int, tint: Triple<Int, Int, Int> = Triple(0, 0, 0)): LinearImage {
        val width = 200
        val height = 100
        return LinearImage.fromArgb(
            IntArray(width * height) { index ->
                val level = from + (to - from) * (index % width) / (width - 1)
                rgb((level + tint.first).coerceIn(0, 255), (level + tint.second).coerceIn(0, 255), (level + tint.third).coerceIn(0, 255))
            },
            width,
            height,
        )
    }

    @Test
    fun brightensADarkPictureAndDarkensABrightOneLessReadily() {
        val dark = AutoSettings.of(bands(5, 90))
        val bright = AutoSettings.of(bands(150, 250))

        assertTrue("dark ${dark.exposure}", dark.exposure > 0.5f)
        assertTrue("bright ${bright.exposure}", bright.exposure < -0.1f && bright.exposure > -dark.exposure)
    }

    @Test
    fun leavesAWellExposedNeutralPictureNearlyAlone() {
        val settings = AutoSettings.of(bands(10, 245))

        assertEquals(0f, settings.exposure, 0.15f)
        assertEquals(0f, settings.temperature, 0f)
        assertEquals(0f, settings.tint, 0f)
        assertTrue(settings.contrast <= 0.05f)
    }

    @Test
    fun warmsABluePictureAndTakesAGreenCastAway() {
        val blue = AutoSettings.of(bands(40, 200, Triple(-15, 0, 15)))
        val green = AutoSettings.of(bands(40, 200, Triple(-10, 15, -10)))

        assertTrue("${blue.temperature}", blue.temperature > 0.1f)
        assertTrue("${green.tint}", green.tint > 0.1f)
        // Rendered with the settings, the cast is smaller. Only part of the way is taken: a cast may be the scene's own light.
        val before = bands(40, 200, Triple(-15, 0, 15)).render(PhotoEdit())
        val after = bands(40, 200, Triple(-15, 0, 15)).render(PhotoEdit(blue))
        fun cast(pixels: IntArray) = pixels.sumOf { (it and 0xFF) - ((it ushr 16) and 0xFF) }
        assertTrue("${cast(before)} -> ${cast(after)}", cast(after) in 0 until (cast(before) * 0.8).toInt())
    }

    @Test
    fun addsContrastToAFlatPicture() {
        assertTrue(AutoSettings.of(bands(100, 150)).contrast > 0.1f)
    }
}
