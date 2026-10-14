package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class DetailFilterTest {
    private val width = 240
    private val height = 300

    private fun rgb(red: Int, green: Int, blue: Int) =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
    private fun gray(level: Int) = rgb(level, level, level)
    private fun red(pixel: Int) = (pixel ushr 16) and 0xFF
    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF

    private fun picture(pixel: (x: Int, y: Int) -> Int) = IntArray(width * height) { pixel(it % width, it / width) }

    /** The same for the same pixel every time, and unrelated between neighbours: -1, 0 or 1 times [strength]. */
    private fun speckle(x: Int, y: Int, strength: Int, seed: Int = 0): Int {
        var hash = (x + seed * 7919) * 374761393 + y * 668265263
        hash = (hash xor (hash ushr 13)) * 1274126177
        return ((hash xor (hash ushr 16)).mod(3) - 1) * strength
    }

    private fun filtered(pixels: IntArray, adjustments: Adjustments, scale: Float = 1f) =
        DetailFilter(adjustments, scale).apply(pixels, width, height)

    /** How much the green values of the pixels in a block around the middle of the left half vary. */
    private fun unrest(pixels: IntArray): Double {
        val values = (100 until 200).flatMap { y -> (20 until 100).map { x -> green(pixels[y * width + x]).toDouble() } }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun hasNothingToDoUnlessAskedTo() {
        assertFalse(DetailFilter(Adjustments(exposure = 1f, clarity = 1f), scale = 1f).isNeeded)
        assertTrue(DetailFilter(Adjustments(sharpening = 0.5f), scale = 1f).isNeeded)
        assertTrue(DetailFilter(Adjustments(texture = -0.5f), scale = 0.25f).isNeeded)
    }

    @Test
    fun leavesOutWhatASmallPreviewCannotShow() {
        val pixelDetail = Adjustments(sharpening = 1f, noiseReduction = 1f, colorNoiseReduction = 1f)

        assertFalse(DetailFilter(pixelDetail, scale = 0.25f).isNeeded)
        assertTrue(DetailFilter(pixelDetail, scale = 0.5f).isNeeded)
    }

    @Test
    fun sharpeningSetsAnEdgeOffAndLeavesEvenAreasAlone() {
        val edge = picture { x, _ -> gray(if (x < 120) 100 else 150) }

        val sharp = filtered(edge, Adjustments(sharpening = 1f))

        val row = 150 * width
        assertTrue(green(sharp[row + 119]) < 95)
        assertTrue(green(sharp[row + 120]) > 155)
        assertEquals(gray(100), sharp[row + 60])
        assertEquals(gray(150), sharp[row + 200])
    }

    @Test
    fun sharpeningAddsLittleToWhatMayJustBeNoise() {
        val faint = picture { x, _ -> gray(if (x < 120) 120 else 122) }
        val clear = picture { x, _ -> gray(if (x < 120) 100 else 140) }

        val faintGain = green(filtered(faint, Adjustments(sharpening = 1f))[150 * width + 120]) - 122
        val clearGain = green(filtered(clear, Adjustments(sharpening = 1f))[150 * width + 120]) - 140

        // The clear edge is twenty times as high but gains far more than twenty times as much.
        assertTrue("faint +$faintGain, clear +$clearGain", clearGain > 15 && faintGain <= 1)
    }

    @Test
    fun noiseReductionCalmsAnAreaButKeepsAnEdge() {
        val noisy = picture { x, y -> gray((if (x < 120) 90 else 170) + speckle(x, y, 6)) }

        val calm = filtered(noisy, Adjustments(noiseReduction = 1f))

        assertTrue("${unrest(noisy)} -> ${unrest(calm)}", unrest(calm) < unrest(noisy) / 2.5)
        val row = 150 * width
        val step = (125 until 135).map { green(calm[row + it]) }.average() - (105 until 115).map { green(calm[row + it]) }.average()
        assertEquals(80.0, step, 4.0)
        // Right at the edge the two sides are not smeared into each other.
        assertTrue(green(calm[row + 121]) - green(calm[row + 118]) > 60)
    }

    @Test
    fun colourNoiseReductionRemovesSpecklesOfColourButNotColours() {
        // Flecks of red and blue on gray to the left; a plain red area to the right.
        val flecked = picture { x, y ->
            if (x < 120) rgb(120 + speckle(x, y, 14, seed = 1), 120, 120 + speckle(x, y, 14, seed = 2)) else rgb(200, 60, 50)
        }
        fun colour(pixels: IntArray) =
            (100 until 200).sumOf { y -> (20 until 100).sumOf { x -> pixels[y * width + x].let { abs(red(it) - green(it)) + abs(blue(it) - green(it)) } } }

        val clean = filtered(flecked, Adjustments(colorNoiseReduction = 1f))

        assertTrue("${colour(flecked)} -> ${colour(clean)}", colour(clean) < colour(flecked) / 3)
        val plain = clean[150 * width + 200]
        assertEquals(200.0, red(plain).toDouble(), 2.0)
        assertEquals(60.0, green(plain).toDouble(), 2.0)
        assertEquals(50.0, blue(plain).toDouble(), 2.0)
    }

    @Test
    fun textureStrengthensOrSmoothsMediumDetail() {
        // Stripes six pixels wide: too wide to be noise, too narrow to be an area of their own.
        val stripes = picture { x, _ -> gray(if ((x / 6) % 2 == 0) 115 else 140) }
        fun contrast(pixels: IntArray) = green(pixels[150 * width + 129]) - green(pixels[150 * width + 123])

        assertEquals(25, contrast(stripes))
        assertTrue(contrast(filtered(stripes, Adjustments(texture = 1f))) > 35)
        assertTrue(contrast(filtered(stripes, Adjustments(texture = -1f))) < 15)
        // At a quarter of the size the same stripes are finer than what texture is about.
        assertArrayEquals(stripes, filtered(stripes, Adjustments(texture = 1f), scale = 0.05f))
    }

    @Test
    fun worksInBandsWithoutLeavingSeams() {
        // The same in every row, so the result has to be the same in every row too, wherever a band ends.
        val columns = picture { x, _ -> rgb(100 + (x * 7) % 60, 110 + (x * 13) % 50, 90 + (x * 5) % 70) }
        val everything = Adjustments(texture = 0.6f, sharpening = 0.8f, noiseReduction = 0.5f, colorNoiseReduction = 0.5f, glow = 0.7f)

        val result = filtered(columns, everything)

        // Sums of many values come out a hair differently depending on where in a band they start, hence the one level.
        for (y in 1 until height) for (x in 0 until width) {
            val first = result[x]
            val here = result[y * width + x]
            val apart = maxOf(abs(red(here) - red(first)), abs(green(here) - green(first)), abs(blue(here) - blue(first)))
            assertTrue("row $y, column $x is $apart off", apart <= 1)
        }
        assertFalse(result.contentEquals(columns))
    }

    @Test
    fun handsOverEveryRowExactlyOnce() {
        val pixels = picture { x, y -> gray((x + y) % 256) }
        val written = IntArray(height)

        DetailFilter(Adjustments(sharpening = 1f), scale = 1f).apply(pixels, width, height) { top, rows, band ->
            assertTrue(band.size >= rows * width)
            for (row in top until top + rows) written[row]++
        }

        assertTrue(written.all { it == 1 })
    }

    @Test
    fun glowSpreadsLightFromTheBrightPartsAndNeverDarkens() {
        // A dark picture with a bright square in the middle.
        val lamp = picture { x, y -> if (x in 100..140 && y in 130..170) gray(250) else gray(40) }

        val glowing = filtered(lamp, Adjustments(glow = 1f), scale = 0.25f)

        assertTrue(DetailFilter(Adjustments(glow = 0.3f), scale = 0.25f).isNeeded)
        assertTrue("next to it ${green(glowing[150 * width + 150])}", green(glowing[150 * width + 150]) > 60)
        assertTrue(green(glowing[150 * width + 150]) > green(glowing[150 * width + 200]))
        assertEquals(gray(40), glowing[5 * width + 5])
        assertTrue(glowing.indices.all { green(glowing[it]) >= green(lamp[it]) })
    }
}
