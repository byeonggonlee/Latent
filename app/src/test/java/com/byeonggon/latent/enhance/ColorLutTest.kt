package com.byeonggon.latent.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ColorLutTest {
    private fun ColorLut.of(red: Float, green: Float, blue: Float) = FloatArray(3).also { lookup(red, green, blue, it) }

    /** Colours spread all over the cube, the corners and sides among them. */
    private val colours = listOf(
        floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 1f),
        floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(1f, 0.25f, 0f),
    ) + List(200) { Random(it).let { random -> floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat()) } }

    @Test
    fun anIdentityTableChangesNothing() {
        for (size in listOf(2, 3, 17, 33, 65)) {
            val lut = ColorLut.identity(size)
            for (colour in colours) assertArrayEquals("size $size", colour, lut.of(colour[0], colour[1], colour[2]), 1e-5f)
        }
    }

    @Test
    fun swapsAndInvertsColoursAlsoBetweenItsPoints() {
        // What is linear in each colour comes out exactly, however coarse the cube.
        for (size in listOf(2, 5, 33)) {
            val swapped = ColorLut.of(size) { r, g, b -> floatArrayOf(b, r, g) }
            val inverted = ColorLut.of(size) { r, g, b -> floatArrayOf(1f - r, 1f - g, 1f - b) }
            for (colour in colours) {
                val (r, g, b) = colour.toList()
                assertArrayEquals(floatArrayOf(b, r, g), swapped.of(r, g, b), 1e-5f)
                assertArrayEquals(floatArrayOf(1f - r, 1f - g, 1f - b), inverted.of(r, g, b), 1e-5f)
            }
        }
    }

    @Test
    fun mixesTheEightPointsAroundAColour() {
        // Only the far corner is white: the middle of the cube takes an eighth of it.
        val corner = ColorLut.of(2) { r, g, b -> if (r == 1f && g == 1f && b == 1f) floatArrayOf(1f, 1f, 1f) else floatArrayOf(0f, 0f, 0f) }
        assertArrayEquals(floatArrayOf(0.125f, 0.125f, 0.125f), corner.of(0.5f, 0.5f, 0.5f), 1e-6f)
        assertEquals(0.25f * 0.5f * 0.75f, corner.of(0.25f, 0.5f, 0.75f)[0], 1e-6f)
    }

    @Test
    fun holdsColoursBeyondItsEdgesToTheEdge() {
        val inverted = ColorLut.of(9) { r, g, b -> floatArrayOf(1f - r, 1f - g, 1f - b) }

        assertArrayEquals(floatArrayOf(0f, 1f, 0.5f), inverted.of(1.4f, -0.3f, 0.5f), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), inverted.of(1f, 1f, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), inverted.of(0f, 0f, 0f), 1e-6f)
        // Just short of the far edge, in the last cell.
        assertArrayEquals(floatArrayOf(0.001f, 0.001f, 0.001f), inverted.of(0.999f, 0.999f, 0.999f), 1e-5f)
    }

    @Test
    fun spreadsItsPointsOverItsDomain() {
        // Points from -0.5 to 1.5 along each side, each giving back where it is: the identity over that range.
        val size = 5
        val table = FloatArray(size * size * size * 3)
        var at = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            table[at++] = -0.5f + r * 0.5f
            table[at++] = -0.5f + g * 0.5f
            table[at++] = -0.5f + b * 0.5f
        }
        val wide = ColorLut(size, table, floatArrayOf(-0.5f, -0.5f, -0.5f), floatArrayOf(1.5f, 1.5f, 1.5f))

        assertArrayEquals(floatArrayOf(0.3f, 1.2f, -0.4f), wide.of(0.3f, 1.2f, -0.4f), 1e-5f)
        assertArrayEquals(floatArrayOf(1.5f, -0.5f, 0f), wide.of(2f, -1f, 0f), 1e-5f)

        // A domain of one colour only: blue from 0.25 to 0.75 takes up the whole side of the cube.
        val narrow = ColorLut(2, ColorLut.identity(2).table, floatArrayOf(0f, 0f, 0.25f), floatArrayOf(1f, 1f, 0.75f))
        assertArrayEquals(floatArrayOf(0.2f, 0.4f, 0.5f), narrow.of(0.2f, 0.4f, 0.5f), 1e-5f)
        assertEquals(0f, narrow.of(0.2f, 0.4f, 0.25f)[2], 1e-6f)
        assertEquals(1f, narrow.of(0.2f, 0.4f, 0.9f)[2], 1e-6f)
    }

    @Test
    fun agreesWithWhatItReadFromAFile() {
        val text = "LUT_3D_SIZE 3\n" + buildString {
            for (b in 0..2) for (g in 0..2) for (r in 0..2) append("${b / 2f} ${r / 2f} ${g / 2f}\n")
        }
        val lut = CubeLut.parse(text.toByteArray()).lut

        assertArrayEquals(floatArrayOf(0.9f, 0.1f, 0.6f), lut.of(0.1f, 0.6f, 0.9f), 1e-5f)
    }
}
