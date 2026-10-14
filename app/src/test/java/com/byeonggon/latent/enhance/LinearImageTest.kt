package com.byeonggon.latent.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LinearImageTest {
    /** A picture whose red levels count up along the rows; green and blue are left black. */
    private fun counting(width: Int, height: Int) =
        LinearImage(width, height, ShortArray(width * height) { it.toShort() }, ShortArray(width * height), ShortArray(width * height))

    @Test
    fun shrinksByAveragingBlocksOfPixels() {
        val small = counting(4, 4).downscaled(maxEdge = 2)

        assertEquals(2 to 2, small.width to small.height)
        // The top left block holds 0, 1, 4 and 5.
        assertArrayEquals(shortArrayOf(2, 4, 10, 12), small.red)
    }

    @Test
    fun keepsItsSizeWhenItIsSmallEnoughAlready() {
        val copy = counting(3, 2).downscaled(maxEdge = 8)

        assertEquals(3 to 2, copy.width to copy.height)
        assertArrayEquals(shortArrayOf(0, 1, 2, 3, 4, 5), copy.red)
    }

    @Test
    fun turnsClockwiseByQuarterTurns() {
        // 0 1 2
        // 3 4 5
        val picture = counting(3, 2)

        val once = picture.turned(1)
        assertEquals(2 to 3, once.width to once.height)
        assertArrayEquals(shortArrayOf(3, 0, 4, 1, 5, 2), once.red)
        assertArrayEquals(shortArrayOf(5, 4, 3, 2, 1, 0), picture.turned(2).red)
        assertArrayEquals(shortArrayOf(2, 5, 1, 4, 0, 3), picture.turned(3).red)
        assertArrayEquals(picture.red, picture.turned(4).red)
        assertArrayEquals(picture.red, once.turned(-1).red)
    }

    @Test
    fun readsEachColourOfFinishedPixels() {
        val picture = LinearImage.fromArgb(intArrayOf(0xFF000000.toInt(), 0xFFFF8000.toInt()), 2, 1)

        assertEquals(0, picture.red[0].toInt())
        assertEquals(LinearImage.MAX_LEVEL, picture.red[1].toInt())
        assertEquals(CameraCurve.levelOfDisplay[0x80], picture.green[1])
        assertEquals(0, picture.blue[1].toInt())
    }
}
