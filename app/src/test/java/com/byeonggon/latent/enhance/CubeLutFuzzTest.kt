package com.byeonggon.latent.enhance

import com.byeonggon.latent.fuzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Damaged and odd .cube files: each is read into a table that can be looked up, or refused with an
 * [UnusableLutException] that says why; nothing else is thrown, and no table larger than a LUT can be is made.
 */
class CubeLutFuzzTest {
    private val seed = buildString {
        append("# Made by hand\r\nTITLE \"Warm\"\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1.0 1.0 1.0\nLUT_3D_SIZE 3\n\n")
        for (b in 0 until 3) for (g in 0 until 3) for (r in 0 until 3) append("${r / 2f} ${g / 2f + 0.01f} ${b / 2f}\n")
    }.toByteArray()

    /** Resolve's way: its input range, its own keywords, numbers with signs and exponents. */
    private val resolveSeed = buildString {
        append("﻿LUT_3D_SIZE 2\nLUT_3D_INPUT_RANGE -0.5 1.5\nLUT_IN_VIDEO_RANGE\n")
        for (i in 0 until 8) append("+${i}e-1 -0.0 0.5\n")
    }.toByteArray()

    private fun check(bytes: ByteArray) {
        val cube = try {
            CubeLut.parse(bytes)
        } catch (e: UnusableLutException) {
            return
        }
        val lut = cube.lut
        assertTrue(lut.size in ColorLut.SMALLEST..ColorLut.LARGEST)
        assertTrue(lut.table.all { it.isFinite() })
        // What turns a colour into its place in the table is a number: the table can be looked up.
        assertTrue("scale ${lut.scale.toList()}, offset ${lut.offset.toList()}", (lut.scale + lut.offset).all { it.isFinite() })
        val out = FloatArray(3)
        lut.lookup(0.5f, 0.25f, 1f, out)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun theSeedsAreTables() {
        assertEquals(3, CubeLut.parse(seed).lut.size)
        assertEquals(2, CubeLut.parse(resolveSeed).lut.size)
    }

    @Test
    fun damagedFilesAreReadOrRefusedWithAReason() = fuzz(seed = 11, cases = 5_000) { mutator ->
        check(mutator.bytes(if (mutator.random.nextBoolean()) seed else resolveSeed))
    }

    @Test
    fun aDomainTooNarrowToSpreadThePointsOverIsRefused() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1e-44 1 1\n" + "0 0 0\n".repeat(8)

        assertEquals(LutProblem.BAD_DOMAIN, refusal(text).problem)
    }

    private fun refusal(text: String) = refusal(text.toByteArray())

    private fun refusal(bytes: ByteArray): UnusableLutException {
        try {
            CubeLut.parse(bytes)
        } catch (e: UnusableLutException) {
            return e
        }
        throw AssertionError("read without complaint")
    }
}
