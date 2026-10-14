package com.byeonggon.latent.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class AutoEnhanceTest {
    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private fun red(pixel: Int) = (pixel ushr 16) and 0xFF
    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF

    /** Applies what auto enhance decided for [pixels] to those same pixels. */
    private fun enhance(pixels: IntArray) {
        val levels = AutoEnhance.analyze(pixels)
        fun corrected(value: Int, curve: (Float) -> Float) = (curve(value / 255f) * 255f).roundToInt()
        for (index in pixels.indices) {
            val pixel = pixels[index]
            pixels[index] = (pixel and (0xFF shl 24)) or
                (corrected(red(pixel), levels::red) shl 16) or
                (corrected(green(pixel), levels::green) shl 8) or
                corrected(blue(pixel), levels::blue)
        }
    }

    @Test
    fun leavesAFullRangeNeutralImageAlone() {
        val pixels = IntArray(256, ::gray)
        val original = pixels.copyOf()

        enhance(pixels)

        // Clipping 0.5% at each end moves values by a level or two at most.
        pixels.indices.forEach { assertEquals(red(original[it]).toDouble(), red(pixels[it]).toDouble(), 3.0) }
    }

    @Test
    fun stretchesAFlatImageInBothDirections() {
        val pixels = IntArray(100) { gray(100 + it / 2) } // values 100..149
        val original = pixels.copyOf()

        enhance(pixels)

        assertTrue(red(pixels.first()) < red(original.first()))
        assertTrue(red(pixels.last()) > red(original.last()))
        assertTrue(red(pixels.last()) - red(pixels.first()) > red(original.last()) - red(original.first()))
    }

    @Test
    fun brightensAnUnderexposedImage() {
        val pixels = IntArray(200) { gray(it / 2) } // values 0..99, nothing above mid gray
        val before = pixels.sumOf { red(it) }

        enhance(pixels)

        assertTrue(pixels.sumOf { red(it) } > before)
    }

    @Test
    fun pullsAColorCastTowardNeutral() {
        // Warm cast: red is higher than blue across the midtones.
        val pixels = IntArray(100) { (0xFF shl 24) or ((110 + it) shl 16) or ((100 + it) shl 8) or (90 + it) }
        val castBefore = red(pixels[50]) - blue(pixels[50])

        enhance(pixels)

        val castAfter = red(pixels[50]) - blue(pixels[50])
        assertTrue("cast $castBefore -> $castAfter", castAfter < castBefore)
        assertEquals(0xFF, pixels[50] ushr 24)
        assertTrue(green(pixels[50]) in 0..255)
    }
}
