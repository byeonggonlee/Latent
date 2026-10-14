package com.byeonggon.latent.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneRendererTest {
    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    private fun gray(level: Int) = rgb(level, level, level)
    private fun red(pixel: Int) = (pixel ushr 16) and 0xFF
    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF
    private fun chroma(pixel: Int) = maxOf(red(pixel), green(pixel), blue(pixel)) - minOf(red(pixel), green(pixel), blue(pixel))

    private fun picture(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) =
        LinearImage.fromArgb(IntArray(width * height) { pixel(it % width, it / width) }, width, height)

    /** Every gray from black to white, side by side. */
    private val grays = picture(256, 1) { x, _ -> gray(x) }

    private fun adjusted(image: LinearImage, adjustments: Adjustments) = image.render(PhotoEdit(adjustments))

    /** One flat colour, rendered with [adjustments]. */
    private fun adjusted(pixel: Int, adjustments: Adjustments) = adjusted(picture(8, 8) { _, _ -> pixel }, adjustments).first()

    @Test
    fun anUntouchedEditGivesThePictureBackExactly() {
        val colours = picture(64, 64) { x, y -> rgb(x * 4, y * 4, (x + y) * 2) }

        assertArrayEquals(IntArray(256, ::gray), grays.render(PhotoEdit()))
        assertArrayEquals(IntArray(64 * 64) { rgb(it % 64 * 4, it / 64 * 4, (it % 64 + it / 64) * 2) }, colours.render(PhotoEdit()))
    }

    @Test
    fun exposureBrightensAndDarkensEveryToneWithoutSwappingAny() {
        val brighter = adjusted(grays, Adjustments(exposure = 1f))
        val darker = adjusted(grays, Adjustments(exposure = -1f))

        // The very darkest tones are too close together in eight bits to tell a change.
        for (level in 10..254) {
            assertTrue("$level became ${green(brighter[level])}", green(brighter[level]) > level || green(brighter[level]) == 255)
            assertTrue("$level became ${green(darker[level])}", green(darker[level]) < level)
        }
        assertTrue((1..255).all { green(brighter[it]) >= green(brighter[it - 1]) && green(darker[it]) >= green(darker[it - 1]) })
        assertEquals(0, green(darker[0]))
        // A stop less light than the sensor could take is still a bright tone, not a middle gray.
        assertTrue("white became ${green(darker[255])}", green(darker[255]) in 215..245)
    }

    @Test
    fun contrastPushesTonesAwayFromMiddleGray() {
        val more = adjusted(grays, Adjustments(contrast = 1f))
        val less = adjusted(grays, Adjustments(contrast = -1f))

        assertTrue(green(more[50]) < 40 && green(more[200]) > 210)
        assertTrue(green(less[50]) > 60 && green(less[200]) < 190)
        assertEquals(115.0, green(more[115]).toDouble(), 2.0)
        assertEquals(listOf(0, 255, 0, 255), listOf(green(more[0]), green(more[255]), green(less[0]), green(less[255])))
    }

    @Test
    fun shadowsOnlyMoveDarkTones() {
        val lifted = Adjustments(shadows = 1f)
        val deepened = Adjustments(shadows = -1f)

        assertTrue(green(adjusted(gray(30), lifted)) > 40)
        assertTrue(green(adjusted(gray(30), deepened)) < 25)
        assertEquals(0, green(adjusted(gray(0), lifted)))
        assertEquals(gray(160), adjusted(gray(160), lifted))
        assertEquals(gray(160), adjusted(gray(160), deepened))
    }

    @Test
    fun highlightsOnlyMoveBrightTones() {
        val recovered = Adjustments(highlights = -1f)
        val brightened = Adjustments(highlights = 1f)

        assertTrue(green(adjusted(gray(225), recovered)) < 205)
        assertTrue(green(adjusted(gray(225), brightened)) > 235)
        assertEquals(gray(255), adjusted(gray(255), recovered))
        assertEquals(gray(90), adjusted(gray(90), recovered))
        assertEquals(gray(90), adjusted(gray(90), brightened))
    }

    @Test
    fun liftingShadowsKeepsTheDetailInThem() {
        // Fine dark stripes: one shadow as far as the surroundings go.
        val stripes = picture(200, 200) { x, _ -> gray(if (x % 2 == 0) 24 else 36) }

        val lifted = adjusted(stripes, Adjustments(shadows = 1f))

        val dark = green(lifted[100 * 200 + 100])
        val light = green(lifted[100 * 200 + 101])
        assertTrue(dark > 30)
        // The stripes were half as bright again as each other and still are; a tone curve would have pressed them together.
        assertEquals(1.5, light.toDouble() / dark, 0.08)
    }

    @Test
    fun liftingShadowsLeavesNoHaloAlongAnEdge() {
        val halves = picture(300, 200) { x, _ -> gray(if (x < 150) 30 else 215) }

        val lifted = adjusted(halves, Adjustments(shadows = 1f))

        val row = 100 * 300
        val farDark = green(lifted[row + 20])
        assertTrue(farDark > 40)
        for (x in 0 until 150) assertEquals("dark side at $x", farDark.toDouble(), green(lifted[row + x]).toDouble(), 4.0)
        for (x in 150 until 300) assertEquals("bright side at $x", 215.0, green(lifted[row + x]).toDouble(), 4.0)
    }

    @Test
    fun temperatureAndTintShiftTheColourButNotTheBrightness() {
        val warm = adjusted(gray(128), Adjustments(temperature = 1f))
        val cool = adjusted(gray(128), Adjustments(temperature = -1f))
        val magenta = adjusted(gray(128), Adjustments(tint = 1f))
        val greenish = adjusted(gray(128), Adjustments(tint = -1f))

        assertTrue(red(warm) > 140 && blue(warm) < 116)
        assertTrue(red(cool) < 116 && blue(cool) > 140)
        assertTrue(green(magenta) < 124 && red(magenta) > 130 && blue(magenta) > 130)
        assertTrue(green(greenish) > 130 && red(greenish) < 126)
        for (pixel in listOf(warm, cool, magenta, greenish)) {
            val luma = 0.2126 * red(pixel) + 0.7152 * green(pixel) + 0.0722 * blue(pixel)
            assertEquals(128.0, luma, 6.0)
        }
    }

    @Test
    fun saturationTakesColourAwayOrAddsToIt() {
        val colour = rgb(170, 120, 90)

        val none = adjusted(colour, Adjustments(saturation = -1f))
        val more = adjusted(colour, Adjustments(saturation = 0.5f))

        assertEquals(0, chroma(none))
        assertTrue(chroma(more) > chroma(colour) + 20)
        assertEquals(gray(128), adjusted(gray(128), Adjustments(saturation = 1f)))
    }

    @Test
    fun vibranceAddsMostToColoursThatAreNotVividYet() {
        val muted = rgb(140, 120, 110)
        val vivid = rgb(200, 60, 20)

        val mutedGain = chroma(adjusted(muted, Adjustments(vibrance = 1f))).toDouble() / chroma(muted)
        val vividGain = chroma(adjusted(vivid, Adjustments(vibrance = 1f))).toDouble() / chroma(vivid)

        assertTrue("muted x$mutedGain, vivid x$vividGain", mutedGain > 1.6 && vividGain < 1.2 && vividGain >= 1.0)
    }

    @Test
    fun autoEnhanceStretchesADullPicture() {
        val dull = picture(100, 100) { x, y -> gray(90 + (x + y) / 4) }

        val enhanced = dull.render(PhotoEdit(autoEnhance = true))

        assertTrue(green(enhanced.first()) < 80)
        assertTrue(green(enhanced.last()) > 150)
    }

    @Test
    fun rendersALargePictureInStripsJustLikeInOnePiece() {
        val width = 180
        val height = 120
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            rgb((x * 255 / width), (y * 255 / height), abs(128 - x - y) % 256)
        }
        val whole = LinearImage.fromArgb(pixels, width, height)
        val edit = PhotoEdit(
            Adjustments(exposure = 0.3f, contrast = 0.2f, highlights = -0.8f, shadows = 0.9f, temperature = 0.2f, vibrance = 0.4f),
            autoEnhance = true,
        )
        val renderer = ToneRenderer(edit, whole.downscaled(ToneRenderer.OVERVIEW_EDGE))

        val inStrips = IntArray(width * height)
        val rows = 50
        for (top in 0 until height step rows) {
            val count = minOf(rows, height - top)
            val strip = LinearImage.fromArgb(pixels.copyOfRange(top * width, (top + count) * width), width, count)
            val rendered = IntArray(width * count)
            renderer.render(strip, rendered, top = top, frameHeight = height)
            rendered.copyInto(inStrips, top * width)
        }

        assertArrayEquals(renderer.render(whole), inStrips)
    }
}
