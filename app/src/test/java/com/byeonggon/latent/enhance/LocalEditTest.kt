package com.byeonggon.latent.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEditTest {
    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    private fun gray(level: Int) = rgb(level, level, level)
    private fun green(pixel: Int) = (pixel ushr 8) and 0xFF

    private val topDown = Mask(MaskShape.LINEAR, startX = 0.5f, startY = 0.25f, endX = 0.5f, endY = 0.75f)
    private val circle = Mask(MaskShape.RADIAL, startX = 0.5f, startY = 0.5f, endX = 0.75f, endY = 1f, feather = 0.5f)

    @Test
    fun aLinearMaskIsFullBehindItsStartAndGoneBeyondItsEnd() {
        assertEquals(1f, topDown.weightAt(0.1f, 0.1f, 300f, 200f), 0f)
        assertEquals(1f, topDown.weightAt(0.9f, 0.25f, 300f, 200f), 0f)
        assertEquals(0.5f, topDown.weightAt(0.3f, 0.5f, 300f, 200f), 1e-5f)
        assertEquals(0f, topDown.weightAt(0.5f, 0.8f, 300f, 200f), 0f)
        assertTrue(topDown.weightAt(0.5f, 0.3f, 300f, 200f) > 0.95f)
    }

    @Test
    fun aSlantedLinearMaskFadesAtRightAnglesToItsLineWhateverThePicturesShape() {
        // From the top left corner to the bottom right one of a picture twice as wide as high.
        val slanted = Mask(MaskShape.LINEAR, 0f, 0f, 1f, 1f)

        // (0.2, 0.8) and (0.5, 0.5) lie on one line at right angles to the diagonal only in a square picture.
        assertEquals(0.5f, slanted.weightAt(0.5f, 0.5f, 200f, 100f), 1e-5f)
        assertTrue(slanted.weightAt(0.2f, 0.8f, 200f, 100f) > 0.5f)
        assertEquals(0.5f, slanted.weightAt(0.2f, 0.8f, 100f, 100f), 1e-5f)
    }

    @Test
    fun aRadialMaskIsFullInTheMiddleAndFadesTowardsItsEdge() {
        // A quarter of the width and half the height: an ellipse that touches the top and the bottom.
        assertEquals(1f, circle.weightAt(0.5f, 0.5f, 400f, 200f), 0f)
        assertEquals(1f, circle.weightAt(0.6f, 0.5f, 400f, 200f), 0f)
        assertEquals(0.5f, circle.weightAt(0.6875f, 0.5f, 400f, 200f), 1e-4f)
        assertEquals(0f, circle.weightAt(0.76f, 0.5f, 400f, 200f), 0f)
        assertEquals(0.5f, circle.weightAt(0.5f, 0.875f, 400f, 200f), 1e-4f)
        assertEquals(1f, circle.copy(inverted = true).weightAt(0.9f, 0.5f, 400f, 200f), 0f)
        assertEquals(0f, circle.copy(feather = 0f).weightAt(0.76f, 0.5f, 400f, 200f), 0f)
        assertEquals(1f, circle.copy(feather = 0f).weightAt(0.74f, 0.5f, 400f, 200f), 0f)
    }

    @Test
    fun findsTheWholePicturesPlaceForEveryPixelOfAFramedOne() {
        val whole = Placement(Framing(), 300, 200)
        assertEquals(0.5f, whole.xOf(149, 0), 0.002f)
        assertEquals(0.25f, whole.yOf(0, 49), 0.003f)
        assertEquals(300f to 200f, whole.wholeWidth to whole.wholeHeight)

        // The right half of the picture.
        val cut = Placement(Framing(left = 0.5f), 150, 200)
        assertEquals(0.5f, cut.xOf(0, 0), 0.003f)
        assertEquals(1f, cut.xOf(149, 199), 0.003f)
        assertEquals(300f, cut.wholeWidth, 1e-3f)

        // A quarter turn clockwise: the framed picture's top right corner was the whole picture's top left.
        val turned = Placement(Framing(quarterTurns = 1), 200, 300)
        assertEquals(0f, turned.xOf(199, 0), 0.004f)
        assertEquals(0f, turned.yOf(199, 0), 0.004f)
        assertEquals(1f, turned.xOf(0, 299), 0.004f)
        assertEquals(300f to 200f, turned.wholeWidth to turned.wholeHeight)
    }

    @Test
    fun placesPixelsWhereFramingFetchesThemFrom() {
        // A bright dot in a dark picture: wherever it ends up when framed, its place in the whole must be found again.
        val picture = LinearImage(300, 200)
        for (y in 58..62) for (x in 208..212) picture.green[y * 300 + x] = 12000
        val framings = listOf(
            Framing(left = 0.5f, bottom = 0.6f),
            Framing(quarterTurns = 3),
            Framing(angle = 12f).shrunkToFit(300f, 200f),
            Framing(quarterTurns = 1).mirrored(),
            Framing(angle = -5f, vertical = 0.5f, horizontal = -0.3f).largestOf(1.5f, 300f, 200f),
        )
        for (framing in framings) {
            val framed = picture.framed(framing)
            val brightest = framed.green.indices.maxBy { framed.green[it] }
            val placement = Placement(framing, framed.width, framed.height)

            val x = placement.xOf(brightest % framed.width, brightest / framed.width)
            val y = placement.yOf(brightest % framed.width, brightest / framed.width)

            assertEquals("$framing", 210.5f / 300, x, 0.012f)
            assertEquals("$framing", 60.5f / 200, y, 0.015f)
        }
    }

    @Test
    fun changesOnlyThePartOfThePictureUnderItsMask() {
        val flat = LinearImage.fromArgb(IntArray(120 * 80) { gray(100) }, 120, 80)
        val brighterTop = PhotoEdit(locals = listOf(LocalEdit(topDown, Adjustments(exposure = 1f))))

        val rendered = flat.render(brighterTop)

        assertTrue(green(rendered[5 * 120 + 60]) > 130)
        assertEquals(gray(100), rendered[70 * 120 + 60])
        val middle = green(rendered[40 * 120 + 60])
        assertTrue("half way is $middle", middle in 110..135)
        // What applies everywhere still applies under the mask, and the part's own settings come on top.
        val both = flat.render(brighterTop.copy(adjustments = Adjustments(exposure = -1f)))
        assertEquals(100.0, green(both[5 * 120 + 60]).toDouble(), 3.0)
        assertTrue(green(both[70 * 120 + 60]) < 70)
    }

    @Test
    fun aMaskStaysOnWhatItWasPutOnWhenThePictureIsCut() {
        val flat = LinearImage(120, 80).apply { red.fill(3000); green.fill(3000); blue.fill(3000) }
        val leftSide = Mask(MaskShape.LINEAR, startX = 0.2f, startY = 0.5f, endX = 0.4f, endY = 0.5f)
        val edit = PhotoEdit(locals = listOf(LocalEdit(leftSide, Adjustments(exposure = 1f))), framing = Framing(left = 0.25f))

        val framed = flat.framed(edit.framing)
        val rendered = framed.render(edit)

        // The cut starts in the middle of the fade: its left edge is partly brightened, and from 0.4 of the whole on nothing is.
        val untouched = rendered[40 * framed.width + framed.width - 1]
        assertTrue(green(rendered[40 * framed.width]) > green(untouched) + 10)
        assertEquals(untouched, rendered[40 * framed.width + 30])
    }

    @Test
    fun isStoredWithTheEditButNotHandedOnWithItsLook() {
        val edit = PhotoEdit(
            Adjustments(contrast = 0.2f),
            locals = listOf(
                LocalEdit(topDown.copy(inverted = true), Adjustments(exposure = -0.5f, saturation = 0.3f)),
                LocalEdit(circle, Adjustments(clarity = 0.4f)),
            ),
        )

        assertEquals(edit, PhotoEdit.fromJson(edit.toJson()))
        assertTrue(PhotoEdit(locals = edit.locals).changesColors)
        assertEquals(emptyList<LocalEdit>(), PhotoEdit().withLookOf(edit).locals)
        assertEquals(edit.locals, edit.withLookOf(PhotoEdit(Adjustments(exposure = 1f))).locals)
    }

    @Test
    fun keepsALocalEditToTheTonesItIsHeldTo() {
        // Dark on the left, bright on the right, all under a mask that covers everything.
        val picture = LinearImage.fromArgb(IntArray(100 * 20) { gray(if (it % 100 < 50) 50 else 200) }, 100, 20)
        val everywhere = Mask(MaskShape.LINEAR, startX = 0.5f, startY = 2f, endX = 0.5f, endY = 3f)
        val brightOnly = LocalEdit(everywhere, Adjustments(exposure = -1f), darkest = 0.5f)

        val rendered = picture.render(PhotoEdit(locals = listOf(brightOnly)))

        assertEquals(gray(50), rendered[10 * 100 + 10])
        assertTrue(green(rendered[10 * 100 + 90]) < 170)
        assertEquals(1f, LocalEdit(everywhere).toneWeight(0.01f), 0f)
        assertEquals(0f, brightOnly.toneWeight(0.3f), 0f)
        assertEquals(0.5f, brightOnly.toneWeight(0.45f), 1e-5f)
        val stored = PhotoEdit(locals = listOf(brightOnly.copy(brightest = 0.9f)))
        assertEquals(stored, PhotoEdit.fromJson(stored.toJson()))
    }

    private val everywhere = Mask(MaskShape.EVERYWHERE, 0.5f, 0.5f, 0.5f, 0.5f)

    @Test
    fun theWholePictureIsFullEverywhereUnlessInverted() {
        assertEquals(1f, everywhere.weightAt(0.01f, 0.99f, 300f, 200f), 0f)
        assertEquals(0f, everywhere.copy(inverted = true).weightAt(0.5f, 0.5f, 300f, 200f), 0f)
    }

    @Test
    fun aColourRangeTakesColoursNearItsHueAndLeavesGraysAndOtherColoursAlone() {
        val blues = LocalEdit(everywhere, hue = 240f)

        assertEquals(1f, blues.colourWeight(0.1f, 0.1f, 0.9f), 0f)
        // Anywhere within the spread (30 degrees) of blue, all of it.
        assertEquals(1f, blues.colourWeight(0.1f, 0.5f, 0.9f), 0f)
        assertEquals(0f, blues.colourWeight(0.9f, 0.1f, 0.1f), 0f)
        assertEquals(0f, blues.colourWeight(0.4f, 0.4f, 0.4f), 0f)
        assertEquals(0f, blues.colourWeight(0f, 0f, 0f), 0f)
        // A hint of colour in a gray is not enough; a clear one is.
        assertEquals(0f, blues.colourWeight(0.5f, 0.5f, 0.52f), 0f)
        assertEquals(1f, blues.colourWeight(0.5f, 0.5f, 0.7f), 0f)
        // Without a hue every colour counts, grays too.
        assertEquals(1f, LocalEdit(everywhere).colourWeight(0.4f, 0.4f, 0.4f), 0f)
        // Light beyond white or below black does not confuse it.
        assertEquals(1f, blues.colourWeight(-0.2f, 0.1f, 1.4f), 0f)
    }

    @Test
    fun aColourRangeFadesOutBeyondItsSpreadAndFindsRedAroundTheWheel() {
        val red = LocalEdit(everywhere, hue = 350f, hueSpread = 20f)

        // Orange at 10 degrees is 20 away from 350: still all of it, red at 0 is 10 away.
        assertEquals(1f, red.colourWeight(1f, 0f, 0f), 0f)
        assertEquals(1f, red.colourWeight(1f, 10f / 60f, 0f), 1e-4f)
        // Half way down the fade, 10 degrees beyond the spread.
        assertEquals(0.5f, red.colourWeight(1f, 20f / 60f, 0f), 0.02f)
        assertEquals(0f, red.colourWeight(1f, 60f / 60f * 0.8f, 0f), 0f)
    }

    @Test
    fun keepsALocalEditToTheColourItIsHeldTo() {
        // Red on the left, blue on the right, under a mask that covers everything.
        val picture = LinearImage.fromArgb(IntArray(100 * 20) { if (it % 100 < 50) rgb(200, 40, 40) else rgb(40, 40, 200) }, 100, 20)
        val darkerBlues = LocalEdit(everywhere, Adjustments(exposure = -1f), hue = 240f)

        val untouched = picture.render(PhotoEdit())
        val rendered = picture.render(PhotoEdit(locals = listOf(darkerBlues)))

        assertEquals(untouched[10 * 100 + 10], rendered[10 * 100 + 10])
        val blueBefore = untouched[10 * 100 + 90] and 0xFF
        val blueAfter = rendered[10 * 100 + 90] and 0xFF
        assertTrue("$blueBefore -> $blueAfter", blueAfter < blueBefore - 20)
    }

    @Test
    fun storesTheColourOfAColourRange() {
        val edit = PhotoEdit(locals = listOf(LocalEdit(everywhere, Adjustments(saturation = 0.5f), hue = 120f, hueSpread = 45f), LocalEdit(topDown, Adjustments(exposure = 0.3f), hue = 215f)))

        assertEquals(edit, PhotoEdit.fromJson(edit.toJson()))
        // Edits stored before colour ranges existed have none.
        assertEquals(null, PhotoEdit.fromJson(PhotoEdit(locals = listOf(LocalEdit(topDown, Adjustments(exposure = 1f)))).toJson())!!.locals.single().hue)
    }
}
