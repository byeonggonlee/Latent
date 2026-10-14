package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {
    private val wide = 300f
    private val high = 200f

    /** A picture whose red levels count up along the rows, so every pixel can be told apart. */
    private fun counting(width: Int, height: Int) =
        LinearImage(width, height, ShortArray(width * height) { it.toShort() }, ShortArray(width * height), ShortArray(width * height))

    @Test
    fun theWholePictureFitsUntilItIsLevelled() {
        assertTrue(Framing().fits(wide, high))
        assertTrue(Framing(quarterTurns = 1).fits(wide, high))
        assertFalse(Framing(angle = 5f).fits(wide, high))
        assertFalse(Framing(left = -0.1f).fits(wide, high))
    }

    @Test
    fun shrinksAroundItsCentreUntilItFits() {
        val levelled = Framing(angle = 10f, left = 0.2f, top = 0f, right = 1f, bottom = 0.8f)

        val fitted = levelled.shrunkToFit(wide, high)

        assertTrue(fitted.fits(wide, high))
        assertTrue(fitted.width < levelled.width)
        assertEquals(0.6f, (fitted.left + fitted.right) / 2, 1e-4f)
        assertEquals(0.4f, (fitted.top + fitted.bottom) / 2, 1e-4f)
        assertEquals(levelled.width / levelled.height, fitted.width / fitted.height, 1e-3f)
        // No smaller than it has to be: a little larger would not fit any more.
        val larger = fitted.copy(left = fitted.left - 0.01f, right = fitted.right + 0.01f)
        assertFalse(larger.fits(wide, high))
        assertSame(fitted, fitted.shrunkToFit(wide, high))
    }

    @Test
    fun goesOnlyAsFarTowardsAnotherRectangleAsThePictureReaches() {
        val start = Framing(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
        val within = start.copy(right = 0.9f)
        val beyond = start.copy(right = 1.5f)

        assertEquals(within, start.towards(within, wide, high))
        val stopped = start.towards(beyond, wide, high)
        assertEquals(1f, stopped.right, 1e-3f)
        assertEquals(0.25f, stopped.left, 1e-6f)
    }

    @Test
    fun findsTheLargestRectangleOfAShape() {
        assertEquals(Framing(), Framing.largest(0, 0f, ratio = 1.5f, wide, high))

        val square = Framing.largest(0, 0f, ratio = 1f, wide, high)
        assertEquals(1f / 6, square.left, 1e-4f)
        assertEquals(5f / 6, square.right, 1e-4f)

        // On its side the picture is 200 wide and 300 high.
        val sideways = Framing.largest(1, 0f, ratio = 1f, wide, high)
        assertEquals(0f, sideways.left, 1e-4f)
        assertEquals(1f / 6, sideways.top, 1e-4f)

        val levelled = Framing.largest(0, 8f, ratio = 1.5f, wide, high)
        assertTrue(levelled.fits(wide, high))
        assertTrue(levelled.width in 0.7f..0.95f)
        assertEquals(levelled.width, levelled.height, 1e-3f)
    }

    @Test
    fun keepsTheSamePartOfThePictureThroughQuarterTurns() {
        val framing = Framing(angle = 6f, left = 0.3f, top = 0.2f, right = 0.8f, bottom = 0.7f)

        val once = framing.turnedClockwise()
        val around = once.turnedClockwise().turnedClockwise().turnedClockwise()

        assertEquals(1, once.quarterTurns)
        assertEquals(6f, once.angle, 0f)
        assertEquals(0.3f, once.left, 1e-5f)
        assertEquals(0.3f, once.top, 1e-5f)
        assertEquals(0.8f, once.right, 1e-5f)
        assertEquals(0.8f, once.bottom, 1e-5f)
        assertEquals(framing.quarterTurns, around.quarterTurns)
        assertEquals(framing.left, around.left, 1e-5f)
        assertEquals(framing.bottom, around.bottom, 1e-5f)
        assertEquals(framing.fits(wide, high), once.fits(wide, high))
    }

    @Test
    fun leavesAPictureThatIsKeptWholeAsItIs() {
        val picture = counting(6, 4)

        assertSame(picture, picture.framed(Framing()))
    }

    @Test
    fun cutsOutTheKeptPartPixelForPixel() {
        val picture = counting(6, 4)

        val part = picture.framed(Framing(left = 0.5f, top = 0.25f, right = 1f, bottom = 0.75f))

        assertEquals(3 to 2, part.width to part.height)
        assertArrayEquals(shortArrayOf(9, 10, 11, 15, 16, 17), part.red)
    }

    @Test
    fun turnsByQuarterTurnsPixelForPixel() {
        val picture = counting(6, 4)

        for (turns in 1..3) {
            val framed = picture.framed(Framing(quarterTurns = turns))
            val turned = picture.turned(turns)
            assertEquals(turned.width to turned.height, framed.width to framed.height)
            assertArrayEquals("$turns quarter turns", turned.red, framed.red)
        }
        // A picture stored on its side is brought upright first; the framing's own turns come on top.
        assertArrayEquals(picture.turned(3).red, picture.framed(Framing(quarterTurns = 2), uprightTurns = 1).red)
        assertArrayEquals(picture.turned(1).red, picture.framed(Framing(), uprightTurns = 1).red)
    }

    @Test
    fun cutsFromAPictureOnItsSideInWhatIsThenItsWidthAndHeight() {
        val picture = counting(6, 4)

        // Upright it is 4 wide and 6 high; the kept part is its top half.
        val part = picture.framed(Framing(bottom = 0.5f), uprightTurns = 1)

        assertEquals(4 to 3, part.width to part.height)
        assertArrayEquals(picture.turned(1).red.copyOf(12), part.red)
    }

    @Test
    fun levelsByTurningThePictureClockwiseAboutItsCentre() {
        // Dark, with one bright spot to the right of the centre.
        val size = 101
        val picture = LinearImage(size, size)
        for (y in 48..52) for (x in 78..82) picture.green[y * size + x] = 10000

        val levelled = picture.framed(Framing(angle = 90f))

        // A quarter turn clockwise takes what was on the right to the bottom.
        assertEquals(size to size, levelled.width to levelled.height)
        assertEquals(10000, levelled.green[80 * size + 50].toInt())
        assertEquals(0, levelled.green[50 * size + 80].toInt())
        assertEquals(picture.green[50 * size + 50], levelled.green[50 * size + 50])
    }

    /** Dark, with a bright spot around ([x], [y]) for each of the given places. */
    private fun spots(vararg places: Pair<Int, Int>): LinearImage {
        val picture = LinearImage(301, 201)
        for ((x, y) in places) for (row in y - 2..y + 2) for (column in x - 2..x + 2) picture.green[row * 301 + column] = 10000
        return picture
    }

    /** The middle of the bright pixels in the given part of [image], as x and y. */
    private fun spotIn(image: LinearImage, columns: IntRange, rows: IntRange): Pair<Double, Double> {
        var sumX = 0.0
        var sumY = 0.0
        var weight = 0.0
        for (y in rows) for (x in columns) {
            val level = image.green[y * image.width + x].toDouble()
            sumX += x * level
            sumY += y * level
            weight += level
        }
        return sumX / weight to sumY / weight
    }

    @Test
    fun mirrorsThePicturePixelForPixel() {
        val picture = counting(6, 4)

        val mirrored = picture.framed(Framing().mirrored())

        assertArrayEquals(shortArrayOf(5, 4, 3, 2, 1, 0, 11, 10, 9, 8, 7, 6), mirrored.red.copyOf(12))
        assertEquals(Framing(), Framing().mirrored().mirrored())
    }

    @Test
    fun mirroringShowsTheSamePartTheOtherWayRound() {
        val picture = counting(6, 4)
        val leftThird = Framing(right = 1f / 3)

        val mirrored = picture.framed(leftThird.mirrored())

        // Columns 0 and 1 of the picture, now with column 1 on the left.
        assertEquals(2 to 4, mirrored.width to mirrored.height)
        assertArrayEquals(shortArrayOf(1, 0, 7, 6), mirrored.red.copyOf(4))
        assertEquals(-5f, Framing(angle = 5f, horizontal = 0.25f).mirrored().angle, 0f)
        assertEquals(-0.25f, Framing(angle = 5f, horizontal = 0.25f).mirrored().horizontal, 0f)
    }

    @Test
    fun turnsAMirroredPictureTheWayItIsSeen() {
        val picture = counting(6, 4)
        val mirrored = Framing(left = 0.5f).mirrored()

        // Turning what is seen a quarter clockwise must give the same as turning the finished picture.
        assertArrayEquals(picture.framed(mirrored).turned(1).red, picture.framed(mirrored.turnedClockwise()).red)
        assertArrayEquals(picture.framed(mirrored).turned(2).red, picture.framed(mirrored.turnedClockwise().turnedClockwise()).red)
    }

    @Test
    fun aTiltedPictureNoLongerFillsItsFrame() {
        val tilted = Framing(vertical = 0.6f)

        assertFalse(tilted.fits(wide, high))
        val largest = tilted.largestOf(1.5f, wide, high)
        assertTrue(largest.fits(wide, high))
        assertTrue(largest.width in 0.5f..0.95f)
        assertEquals(largest.width, largest.height, 1e-3f)
        assertFalse(Framing(horizontal = -0.6f).fits(wide, high))
    }

    @Test
    fun tiltingMakesOneSideOfThePictureLargerThanTheOther() {
        // Two spots near the top and two near the bottom, each pair equally far apart.
        val picture = spots(110 to 40, 190 to 40, 110 to 160, 190 to 160)
        val framing = Framing(vertical = 0.5f).largestOf(1.5f, 301f, 201f)

        val tilted = picture.framed(framing)

        val middle = tilted.width / 2
        fun spread(rows: IntRange) =
            spotIn(tilted, middle until tilted.width, rows).first - spotIn(tilted, 0 until middle, rows).first
        val top = spread(0 until tilted.height / 2)
        val bottom = spread(tilted.height / 2 until tilted.height)
        assertTrue("top $top, bottom $bottom", bottom > top * 1.15)
        // The centre of the picture stays where it was.
        val centred = spots(150 to 100).framed(framing)
        val (x, y) = spotIn(centred, 0 until centred.width, 0 until centred.height)
        assertEquals(centred.width / 2.0, x, 1.0)
        assertEquals(centred.height / 2.0, y, 1.0)
    }

    @Test
    fun aTiltGoesAlongWhenThePictureIsTurned() {
        val picture = spots(110 to 40, 190 to 40, 110 to 160, 190 to 160)
        val framing = Framing(vertical = 0.5f).largestOf(1.5f, 301f, 201f)

        val turnedAfter = picture.framed(framing).turned(1)
        val turnedBefore = picture.framed(framing.turnedClockwise())

        assertEquals(turnedAfter.width.toDouble(), turnedBefore.width.toDouble(), 1.0)
        // What was the larger bottom is now on the left: the spots there are further apart, in both pictures alike.
        for (image in listOf(turnedAfter, turnedBefore)) {
            val half = image.width / 2
            fun spread(columns: IntRange) =
                spotIn(image, columns, image.height / 2 until image.height).second - spotIn(image, columns, 0 until image.height / 2).second
            assertTrue(spread(0 until half) > spread(half until image.width) * 1.15)
        }
    }

    @Test
    fun findsItsWayFromTheViewToThePictureAndBack() {
        val view = FramedView(Framing(angle = 7f, flipped = true, vertical = 0.4f, horizontal = -0.3f), 300f, 200f)

        for ((x, y) in listOf(0f to 0f, 120f to -80f, -60f to 70f)) {
            val turnedX = view.turnedX(x, y)
            val turnedY = view.turnedY(x, y)
            assertEquals(x, view.viewX(turnedX, turnedY), 0.01f)
            assertEquals(y, view.viewY(turnedX, turnedY), 0.01f)
        }
        assertEquals(0f, view.turnedX(0f, 0f), 0f)
        assertTrue(view.reaches(100f, 50f))
    }
}
