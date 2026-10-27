package com.byeonggon.latent.enhance

import org.junit.Assert.*
import org.junit.Test

class ColourSamplingTest {
    private fun plane(width: Int = 1, height: Int = 1, colour: (Int, Int) -> Triple<Float, Float, Float>): ColourPickPlane {
        val pixels = List(width * height) { colour(it % width, it / width) }
        return ColourPickPlane(ShownPicture(width, height, pixels.map { it.first }.toFloatArray(), pixels.map { it.second }.toFloatArray(), pixels.map { it.third }.toFloatArray()))
    }

    @Test fun picksWorkingRgbHuesAndWrapsRed() {
        for ((rgb, hue) in listOf(Triple(1f, 0f, 0f) to 0f, Triple(0f, 1f, 0f) to 120f, Triple(0f, 0f, 1f) to 240f)) {
            assertEquals(hue, plane { _, _ -> rgb }.sample(0.5f, 0.5f)!!.hue, 0.001f)
        }
        assertEquals(0.06f, plane { _, _ -> Triple(1f, 0.001f, 0f) }.sample(0f, 0f)!!.hue, 0.001f)
        assertEquals(359.94f, plane { _, _ -> Triple(1f, 0f, 0.001f) }.sample(1f, 1f)!!.hue, 0.001f)
    }

    @Test fun leavesGrayBlackAndInvalidSamplesWithoutAHue() {
        for (rgb in listOf(Triple(0f, 0f, 0f), Triple(0.4f, 0.4f, 0.4f), Triple(1f, 0.96f, 0.96f), Triple(Float.NaN, 0f, 1f), Triple(Float.POSITIVE_INFINITY, 0f, 1f))) {
            assertNull(plane { _, _ -> rgb }.sample(0.5f, 0.5f))
        }
        val red = plane { _, _ -> Triple(1f, 0f, 0f) }
        for ((x, y) in listOf(-0.01f to 0.5f, 1.01f to 0.5f, 0.5f to Float.NaN)) assertNull(red.sample(x, y))
        assertEquals(0f, plane { _, _ -> Triple(1f, -1f, -2f) }.sample(0.5f, 0.5f)!!.hue, 0f)
    }

    @Test fun averagesFiveByFiveAndClipsTheNeighbourhoodAtEdges() {
        val image = plane(5, 5) { x, y -> if (x == 2 && y == 2) Triple(0f, 0f, 1f) else Triple(1f, 0f, 0f) }
        assertEquals(357.5f, image.sample(0.5f, 0.5f)!!.hue, 0.001f) // 24 red : 1 blue
        assertEquals(352.5f, image.sample(0f, 0f)!!.hue, 0.001f) // 8 red : 1 blue
    }

    @Test fun usesTheSelectedMasksInputRatherThanItsOwnOrLaterOutput() {
        val image = LinearImage.fromArgb(IntArray(64) { 0xff4488cc.toInt() }, 8, 8)
        val all = Mask(MaskShape.EVERYWHERE, 0f, 0f, 1f, 1f)
        val prior = LocalEdit(all, Adjustments(temperature = 0.5f, exposure = 0.3f))
        val selected = LocalEdit(all, Adjustments(saturation = -1f))
        val later = LocalEdit(all, Adjustments(exposure = -2f))
        val base = PhotoEdit(Adjustments(contrast = 0.3f), locals = listOf(prior, selected, later))
        val input = ToneRenderer(base, image).colourInput(image, 1)
        val expected = ToneRenderer(base.copy(locals = listOf(prior)), image).render(image)
        assertArrayEquals(expected, input.pixels())
        assertFalse(expected.contentEquals(ToneRenderer(base, image).render(image)))
        val hue = input.sample(0.5f, 0.5f)!!.hue
        assertTrue(selected.copy(hue = hue).colourWeight(input.picture.red[0], input.picture.green[0], input.picture.blue[0]) > 0f)
    }

    @Test fun excludesFinalEffectsAndFramingFromTheWholePhotoPickingView() {
        val image = LinearImage.fromArgb(IntArray(64) { 0xff4488cc.toInt() }, 8, 8)
        val local = LocalEdit(Mask(MaskShape.EVERYWHERE, 0f, 0f, 1f, 1f), Adjustments(exposure = 1f))
        val base = PhotoEdit(locals = listOf(local))
        val finalEffects = base.copy(monochrome = true, grading = ColorGrading(shadows = Tint(40f, 0.8f)), adjustments = Adjustments(vignette = -0.8f, grain = 0.6f), framing = Framing(quarterTurns = 1, left = 0.2f))
        assertArrayEquals(ToneRenderer(base, image).colourInput(image, 0).pixels(), ToneRenderer(finalEffects, image).colourInput(image, 0).pixels())
    }
    @Test fun samplesBeforeAnActualLutChangesTheDisplayedColour() {
        val previous = LutStore.directory
        val folder = java.nio.file.Files.createTempDirectory("colour-picker-lut").toFile()
        try {
            LutStore.directory = folder
            val id = "00000000000000000000000000000001"
            LutStore.save(id, ColorLut.of(2) { _, _, _ -> floatArrayOf(0f, 0f, 0f) })
            val image = LinearImage.fromArgb(IntArray(64) { 0xff4488cc.toInt() }, 8, 8)
            val edit = PhotoEdit(locals = listOf(LocalEdit(Mask(MaskShape.EVERYWHERE, 0f, 0f, 1f, 1f))), lut = LutLook(id))
            val renderer = ToneRenderer(edit, image)
            assertEquals(0xff4488cc.toInt(), renderer.colourInput(image, 0).pixels()[0])
            assertEquals(0xff000000.toInt(), renderer.render(image)[0])
        } finally {
            LutStore.directory = previous
            folder.deleteRecursively()
        }
    }
}
