package com.byeonggon.latent.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot

/** The eight Exif orientations, and the ways of turning a stored picture upright, which must all agree on them. */
class OrientationTest {
    /** A picture whose every pixel is different: its index in the stored picture. */
    private fun counting(width: Int, height: Int) = LinearImage(width, height).apply {
        for (index in 0 until width * height) {
            red[index] = index.toShort()
            green[index] = (index * 2).toShort()
            blue[index] = (index * 3).toShort()
        }
    }

    /**
     * Where Exif puts the stored pixel ([x], [y]) of a [width] by [height] picture when it is seen: from where each
     * orientation says the stored picture's first row and first column go.
     */
    private fun seenAt(orientation: Int, x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> = when (orientation) {
        2 -> width - 1 - x to y
        3 -> width - 1 - x to height - 1 - y
        4 -> x to height - 1 - y
        5 -> y to x
        6 -> height - 1 - y to x
        7 -> height - 1 - y to width - 1 - x
        8 -> y to width - 1 - x
        else -> x to y
    }

    @Test
    fun bringsEachOrientationUprightAsExifDefinesIt() {
        val stored = counting(5, 3)
        for (value in 1..8) {
            val seen = stored.oriented(Orientation.ofExif(value))
            val sideways = value >= 5
            assertEquals("size, orientation $value", if (sideways) 3 to 5 else 5 to 3, seen.width to seen.height)
            for (y in 0 until 3) for (x in 0 until 5) {
                val (seenX, seenY) = seenAt(value, x, y, 5, 3)
                assertEquals("orientation $value, ($x, $y)", stored.red[y * 5 + x], seen.red[seenY * seen.width + seenX])
            }
        }
    }

    @Test
    fun findsAnUprightRectangleWhereItIsStored() {
        val stored = counting(7, 4)
        for (value in 1..8) {
            val orientation = Orientation.ofExif(value)
            val seen = stored.oriented(orientation)
            val upright = intArrayOf(1, 1, 2, 2)
            val (left, top, width, height) = PictureRegion.asStored(upright, orientation, stored.width, stored.height)
            val part = LinearImage(width, height)
            for (y in 0 until height) for (x in 0 until width) part.red[y * width + x] = stored.red[(top + y) * stored.width + left + x]
            val partSeen = part.oriented(orientation)
            for (y in 0 until 2) for (x in 0 until 2) {
                assertEquals("orientation $value, ($x, $y)", seen.red[(1 + y) * seen.width + 1 + x], partSeen.red[y * 2 + x])
            }
        }
    }

    @Test
    fun framesTheUprightPictureWhateverTheOrientation() {
        val stored = counting(6, 4)
        val cut = Framing(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 1f)
        for (value in 1..8) {
            val orientation = Orientation.ofExif(value)
            val seen = stored.oriented(orientation)
            assertArrayEquals("whole, orientation $value", seen.red, stored.framed(Framing(), orientation).red)
            assertArrayEquals("cut, orientation $value", seen.framed(cut).red, stored.framed(cut, orientation).red)
            assertArrayEquals("turned, orientation $value", seen.turned(1).red, stored.framed(Framing(quarterTurns = 1), orientation).red)
        }
    }

    @Test
    fun healsWhereTheSpotIsOnTheUprightPicture() {
        for (value in 1..8) {
            val orientation = Orientation.ofExif(value)
            // A bright picture with one dark blemish, at (0.3, 0.6) of the upright picture.
            val upright = if (orientation.isSideways) LinearImage(150, 200) else LinearImage(200, 150)
            for (y in 0 until upright.height) for (x in 0 until upright.width) {
                val blemish = hypot(x - 0.3 * upright.width, y - 0.6 * upright.height) < 5
                val at = y * upright.width + x
                upright.red[at] = (if (blemish) 1000 else 5000).toShort()
                upright.green[at] = upright.red[at]
                upright.blue[at] = upright.red[at]
            }
            // The same picture as the file stores it: what turning it upright gives back.
            val stored = storedOf(upright, value)
            assertArrayEquals(upright.red, stored.oriented(orientation).red)

            Healing.heal(stored, listOf(Spot(0.3f, 0.6f, 0.03f, 0.7f, 0.6f)), orientation)

            val healed = stored.oriented(orientation)
            val centre = (0.6 * upright.height).toInt() * upright.width + (0.3 * upright.width).toInt()
            assertEquals("orientation $value", 5000, healed.green[centre].toInt())
        }
    }

    /** The stored picture that [value] brings upright as [upright]. */
    private fun storedOf(upright: LinearImage, value: Int): LinearImage {
        val sideways = value >= 5
        val stored = if (sideways) LinearImage(upright.height, upright.width) else LinearImage(upright.width, upright.height)
        for (y in 0 until stored.height) for (x in 0 until stored.width) {
            val (seenX, seenY) = seenAt(value, x, y, stored.width, stored.height)
            val from = seenY * upright.width + seenX
            val to = y * stored.width + x
            stored.red[to] = upright.red[from]
            stored.green[to] = upright.green[from]
            stored.blue[to] = upright.blue[from]
        }
        return stored
    }
}
