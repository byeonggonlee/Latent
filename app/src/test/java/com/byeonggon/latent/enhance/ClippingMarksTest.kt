package com.byeonggon.latent.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClippingMarksTest {
    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    @Test
    fun marksWhiteAndAnyColourAtItsBrightestInRed() {
        val pixels = intArrayOf(rgb(255, 255, 255), rgb(255, 40, 30), rgb(120, 254, 90))
        assertTrue(ClippingMarks.mark(pixels))
        assertArrayEquals(IntArray(3) { ClippingMarks.HIGHLIGHT }, pixels)
    }

    @Test
    fun marksOnlyPureBlackInBlue() {
        val pixels = intArrayOf(rgb(0, 0, 0), rgb(1, 1, 1), rgb(0, 0, 40), rgb(2, 2, 2))
        assertTrue(ClippingMarks.mark(pixels))
        assertArrayEquals(intArrayOf(ClippingMarks.SHADOW, ClippingMarks.SHADOW, rgb(0, 0, 40), rgb(2, 2, 2)), pixels)
    }

    @Test
    fun leavesAPictureWithoutClippingAlone() {
        val pixels = intArrayOf(rgb(10, 20, 30), rgb(128, 128, 128), rgb(240, 200, 100))
        val before = pixels.copyOf()
        assertFalse(ClippingMarks.mark(pixels))
        assertArrayEquals(before, pixels)
    }
}
