package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** The tools beyond the basic sliders: curves, single colours, tints, and what is laid over the picture. */
class ToneRendererLookTest {
    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    private fun gray(level: Int) = rgb(level, level, level)
    private fun red(pixel: Int) = (pixel ushr 16) and 0xFF
    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF
    private fun luma(pixel: Int) = 0.2126 * red(pixel) + 0.7152 * green(pixel) + 0.0722 * blue(pixel)
    private fun chroma(pixel: Int) = maxOf(red(pixel), green(pixel), blue(pixel)) - minOf(red(pixel), green(pixel), blue(pixel))

    private fun picture(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) =
        LinearImage.fromArgb(IntArray(width * height) { pixel(it % width, it / width) }, width, height)

    /** One flat colour, rendered with [edit]. */
    private fun rendered(pixel: Int, edit: PhotoEdit) = picture(8, 8) { _, _ -> pixel }.render(edit).first()

    private fun adjusted(pixel: Int, adjustments: Adjustments) = rendered(pixel, PhotoEdit(adjustments))

    private fun mixer(band: MixerBand, hue: Float = 0f, saturation: Float = 0f, luminance: Float = 0f): ColorMixer {
        fun only(value: Float) = List(MixerBand.entries.size) { if (it == band.ordinal) value else 0f }
        return ColorMixer(only(hue), only(saturation), only(luminance))
    }

    private fun spread(pixels: IntArray): Double {
        val mean = pixels.sumOf { luma(it) } / pixels.size
        return sqrt(pixels.sumOf { (luma(it) - mean) * (luma(it) - mean) } / pixels.size)
    }

    @Test
    fun whitesMoveTheBrightEndAndBlacksTheDarkEnd() {
        assertTrue(green(adjusted(gray(215), Adjustments(whites = 1f))) > 235)
        assertTrue(green(adjusted(gray(255), Adjustments(whites = -1f))) in 195..215)
        assertEquals(gray(0), adjusted(gray(0), Adjustments(whites = 1f)))
        assertEquals(60.0, green(adjusted(gray(60), Adjustments(whites = 1f))).toDouble(), 2.0)

        assertTrue(green(adjusted(gray(0), Adjustments(blacks = 1f))) in 20..40)
        assertEquals(0, green(adjusted(gray(20), Adjustments(blacks = -1f))))
        assertEquals(gray(255), adjusted(gray(255), Adjustments(blacks = 1f)))
        assertEquals(200.0, green(adjusted(gray(200), Adjustments(blacks = -1f))).toDouble(), 2.0)
    }

    @Test
    fun theToneCurveReshapesAllColoursOrOneOfThem() {
        val faded = ToneCurve(listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 1f)))

        assertEquals(gray(26), rendered(gray(0), PhotoEdit(curves = ToneCurves(all = faded))))
        assertEquals(gray(255), rendered(gray(255), PhotoEdit(curves = ToneCurves(all = faded))))

        val warmerShadows = rendered(gray(0), PhotoEdit(curves = ToneCurves(red = faded)))
        assertEquals(listOf(26, 0, 0), listOf(red(warmerShadows), green(warmerShadows), blue(warmerShadows)))
    }

    @Test
    fun blackAndWhiteKeepsTheBrightnessAndLetsColoursBeWeighed() {
        val reddish = rgb(180, 90, 80)

        val plain = rendered(reddish, PhotoEdit(monochrome = true))
        val redsLighter = rendered(reddish, PhotoEdit(monochrome = true, mixer = mixer(MixerBand.RED, luminance = 1f)))
        val bluesLighter = rendered(reddish, PhotoEdit(monochrome = true, mixer = mixer(MixerBand.BLUE, luminance = 1f)))

        assertEquals(0, chroma(plain))
        assertEquals(luma(reddish), green(plain).toDouble(), 1.5)
        assertTrue(green(redsLighter) > green(plain) + 15)
        assertEquals(plain, bluesLighter)
        // Vivid settings do nothing to a picture without colour.
        assertEquals(plain, rendered(reddish, PhotoEdit(Adjustments(saturation = 1f, vibrance = 1f), monochrome = true)))
    }

    @Test
    fun theMixerChangesOneColourAndLeavesTheOthersAlone() {
        val red = rgb(200, 60, 50)
        val blue = rgb(50, 50, 200)

        val orange = rendered(red, PhotoEdit(mixer = mixer(MixerBand.RED, hue = 1f)))
        assertTrue("$orange", green(orange) > green(red) + 25 && blue(orange) <= blue(red))
        assertEquals(luma(red), luma(orange), 20.0)

        assertEquals(0.0, chroma(rendered(blue, PhotoEdit(mixer = mixer(MixerBand.BLUE, saturation = -1f)))).toDouble(), 1.0)
        assertTrue(chroma(rendered(blue, PhotoEdit(mixer = mixer(MixerBand.BLUE, saturation = 0.5f)))) > chroma(blue) + 10)
        assertTrue(luma(rendered(blue, PhotoEdit(mixer = mixer(MixerBand.BLUE, luminance = -1f)))) < luma(blue) - 15)

        val untouched = PhotoEdit(mixer = mixer(MixerBand.BLUE, hue = 1f, saturation = -1f, luminance = -1f))
        assertEquals(red, rendered(red, untouched))
        assertEquals(gray(128), rendered(gray(128), untouched))
    }

    @Test
    fun aColourBetweenTwoMixerColoursFollowsBoth() {
        // Half way from yellow (60 degrees) to green (120).
        val lime = rgb(128, 200, 40)

        val byYellow = chroma(rendered(lime, PhotoEdit(mixer = mixer(MixerBand.YELLOW, saturation = -1f))))
        val byGreen = chroma(rendered(lime, PhotoEdit(mixer = mixer(MixerBand.GREEN, saturation = -1f))))
        val byBlue = chroma(rendered(lime, PhotoEdit(mixer = mixer(MixerBand.BLUE, saturation = -1f))))

        assertTrue("$byYellow, $byGreen", byYellow in 40..120 && byGreen in 40..120)
        assertEquals(chroma(lime), byBlue)
    }

    @Test
    fun gradingTintsTonesByHowBrightTheyAre() {
        val blueShadows = PhotoEdit(grading = ColorGrading(shadows = Tint(hue = 240f, amount = 1f)))

        val dark = rendered(gray(40), blueShadows)
        val bright = rendered(gray(240), blueShadows)

        assertTrue("$dark", blue(dark) > red(dark) + 20)
        assertEquals(luma(gray(40)), luma(dark), 2.0)
        assertTrue(chroma(bright) <= 2)

        // With the balance all the way up, hardly anything counts as a shadow any more.
        val balanced = rendered(gray(100), blueShadows.copy(grading = blueShadows.grading.copy(balance = 1f)))
        assertTrue(chroma(balanced) < chroma(rendered(gray(100), blueShadows)))

        val warmHighlights = rendered(gray(230), PhotoEdit(grading = ColorGrading(highlights = Tint(hue = 40f, amount = 1f))))
        assertTrue(red(warmHighlights) > blue(warmHighlights) + 20)
    }

    @Test
    fun theVignetteDarkensOrLightensTheCornersOnly() {
        val flat = picture(120, 80) { _, _ -> gray(128) }

        val darkened = flat.render(PhotoEdit(Adjustments(vignette = -1f)))
        val lightened = flat.render(PhotoEdit(Adjustments(vignette = 1f)))

        val centre = 40 * 120 + 60
        assertEquals(gray(128), darkened[centre])
        assertTrue(green(darkened[0]) < 60)
        assertTrue(green(darkened[40 * 120]) in 45..120) // the middle of a side: less than a corner
        assertTrue(green(lightened[0]) > 170)
        assertEquals(green(darkened[0]), green(darkened[80 * 120 - 1]))
    }

    @Test
    fun grainFlecksTheMiddleTonesWithoutMakingThemLighterOrDarker() {
        val flat = picture(1200, 800) { _, _ -> gray(128) }

        val grainy = flat.render(PhotoEdit(Adjustments(grain = 1f)))

        assertTrue(spread(grainy) > 4.0)
        assertEquals(128.0, grainy.sumOf { luma(it) } / grainy.size, 1.5)
        assertTrue(grainy.all { chroma(it) == 0 })
        assertArrayEquals(grainy, flat.render(PhotoEdit(Adjustments(grain = 1f))))
        // Black takes much less of it.
        assertTrue(spread(picture(1200, 800) { _, _ -> gray(2) }.render(PhotoEdit(Adjustments(grain = 1f)))) < spread(grainy) / 2)
    }

    @Test
    fun clarityStrengthensOrSoftensFineContrast() {
        val stripes = picture(200, 200) { x, _ -> gray(if (x % 4 < 2) 110 else 140) }
        fun contrast(pixels: IntArray) = green(pixels[100 * 200 + 102]) - green(pixels[100 * 200 + 100])

        val clearer = contrast(stripes.render(PhotoEdit(Adjustments(clarity = 1f))))
        val softer = contrast(stripes.render(PhotoEdit(Adjustments(clarity = -1f))))

        assertTrue("clearer $clearer, softer $softer", clearer > 42 && softer in 0..18)
        // An even area has nothing to set apart.
        assertEquals(gray(128), adjusted(gray(128), Adjustments(clarity = 1f)))
    }

    @Test
    fun dehazeLiftsAVeilAndCanLayOneOn() {
        // A scene of dark and coloured patches, half hidden behind a white veil.
        fun veiled(value: Int) = (value + 255) / 2
        val hazy = picture(160, 120) { x, y ->
            when ((x / 20 + y / 20) % 3) {
                0 -> rgb(veiled(20), veiled(30), veiled(25))
                1 -> rgb(veiled(150), veiled(60), veiled(40))
                else -> rgb(veiled(60), veiled(120), veiled(170))
            }
        }
        val before = hazy.render(PhotoEdit())

        val cleared = hazy.render(PhotoEdit(Adjustments(dehaze = 1f)))
        val thicker = hazy.render(PhotoEdit(Adjustments(dehaze = -1f)))

        assertTrue("${spread(before)} -> ${spread(cleared)}", spread(cleared) > spread(before) * 1.5)
        assertTrue(cleared.sumOf { chroma(it) } > before.sumOf { chroma(it) } * 1.5)
        assertTrue(spread(thicker) < spread(before) * 0.7)
    }

    @Test
    fun rendersEveryToolInStripsJustLikeInOnePiece() {
        val width = 180
        val height = 120
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            rgb((x * 255 / width), (y * 255 / height), abs(128 - x - y) % 256)
        }
        val whole = LinearImage.fromArgb(pixels, width, height)
        val edit = PhotoEdit(
            Adjustments(whites = 0.3f, blacks = -0.2f, shadows = 0.5f, clarity = 0.6f, dehaze = 0.4f, vignette = -0.7f, grain = 0.8f),
            curves = ToneCurves(all = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 0.95f)))),
            mixer = mixer(MixerBand.GREEN, hue = -0.5f, saturation = 0.4f, luminance = 0.3f),
            grading = ColorGrading(shadows = Tint(200f, 0.6f), highlights = Tint(40f, 0.5f), balance = 0.2f),
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
