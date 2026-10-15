package com.byeonggon.latent.enhance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class CubeLutTest {
    /** The lines of a .cube file of [size] points along each side, each turned into what [colour] says. */
    private fun points(size: Int, colour: (Float, Float, Float) -> FloatArray = { r, g, b -> floatArrayOf(r, g, b) }): String = buildString {
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val turned = colour(r / (size - 1f), g / (size - 1f), b / (size - 1f))
            append("${turned[0]} ${turned[1]} ${turned[2]}\n")
        }
    }

    private fun read(text: String) = CubeLut.parse(text.toByteArray())

    private fun refused(text: String): UnusableLutException = refused { read(text) }

    private fun refused(reading: () -> Unit): UnusableLutException {
        try {
            reading()
        } catch (e: UnusableLutException) {
            return e
        }
        fail("read without complaint")
        throw AssertionError()
    }

    @Test
    fun readsATableWithItsTitleRedChangingFastest() {
        val cube = read(
            """
            # Made by hand
            TITLE "Warm look"

            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
            """.trimIndent(),
        )

        assertEquals("Warm look", cube.title)
        assertEquals(2, cube.lut.size)
        // The second point is one step along red, the third one along green, the fifth one along blue.
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), cube.lut.table.copyOfRange(3, 6), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), cube.lut.table.copyOfRange(6, 9), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), cube.lut.table.copyOfRange(12, 15), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), cube.lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), cube.lut.domainMax, 0f)
    }

    @Test
    fun takesTheWaysOtherProgramsWriteTheirFiles() {
        // A byte order mark, Windows line ends, tabs, a comment after a value, Resolve's keywords, no title.
        val text = "﻿LUT_3D_SIZE 3\r\nLUT_IN_VIDEO_RANGE\r\nLUT_3D_INPUT_RANGE -0.5 1.5\r\n" +
            points(3).replace(" ", "\t").replace("\n", " # point\r\n")
        val cube = read(text)

        assertNull(cube.title)
        assertEquals(3, cube.lut.size)
        assertArrayEquals(floatArrayOf(-0.5f, -0.5f, -0.5f), cube.lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1.5f, 1.5f, 1.5f), cube.lut.domainMax, 0f)
        assertEquals(1f, cube.lut.table.last(), 0f)
    }

    @Test
    fun readsTheDomainOfEachColour() {
        val cube = read("DOMAIN_MIN 0 -0.1 0.2\nDOMAIN_MAX 1 1.1 0.8\nLUT_3D_SIZE 2\n" + points(2))

        assertArrayEquals(floatArrayOf(0f, -0.1f, 0.2f), cube.lut.domainMin, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1.1f, 0.8f), cube.lut.domainMax, 1e-6f)
    }

    @Test
    fun readsTheLargestCubeQuickly() {
        val text = "TITLE \"Big\"\nLUT_3D_SIZE 65\n" + points(65)
        val startedAt = System.nanoTime()
        val cube = read(text)

        assertEquals(65 * 65 * 65 * 3, cube.lut.table.size)
        // On a computer; a phone takes some times longer. Kotlin's own parsing of numbers took seconds.
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 3_000)
    }

    @Test
    fun saysWhenTheFileCannotBeReadOrIsTooLarge() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
        }
        assertEquals(LutProblem.UNREADABLE, refused { CubeLut.read(broken) }.problem)

        val huge = object : InputStream() {
            var left = CubeLut.LARGEST_FILE + 10L
            override fun read(): Int = if (left-- > 0) ' '.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val count = minOf(len.toLong(), left).toInt()
                b.fill(' '.code.toByte(), off, off + count)
                left -= count
                return count
            }
        }
        assertEquals(LutProblem.TOO_LARGE, refused { CubeLut.read(huge) }.problem)
    }

    @Test
    fun findsNoLutInOtherFiles() {
        assertEquals(LutProblem.NOT_A_LUT, refused("").problem)
        assertEquals(LutProblem.NOT_A_LUT, refused("# only a comment\n\n").problem)
        assertEquals(LutProblem.NOT_A_LUT, refused("Dear diary,\nnothing happened today.\n").problem)
        assertEquals(LutProblem.NOT_A_LUT, refused("<?xml version=\"1.0\"?>\n<x:xmpmeta/>\n").problem)
        // A picture picked by mistake.
        assertEquals(LutProblem.NOT_A_LUT, refused { CubeLut.parse(byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70)) }.problem)
    }

    @Test
    fun refusesOneDimensionalTables() {
        val alone = refused("TITLE \"Curve\"\nLUT_1D_SIZE 2\n0 0 0\n1 1 1\n")
        assertEquals(LutProblem.ONE_DIMENSIONAL, alone.problem)
        assertEquals(2, alone.line)
        // A shaper in front of a 3D table, as Resolve writes for Log footage.
        assertEquals(LutProblem.ONE_DIMENSIONAL, refused("LUT_1D_SIZE 2\nLUT_3D_SIZE 2\n" + points(2)).problem)
        assertEquals(LutProblem.ONE_DIMENSIONAL, refused("LUT_1D_INPUT_RANGE 0 1\nLUT_3D_SIZE 2\n" + points(2)).problem)
    }

    @Test
    fun needsASizeFromTwoToSixtyFive() {
        val withoutSize = refused("TITLE \"No size\"\n0 0 0\n")
        assertEquals(LutProblem.NO_SIZE, withoutSize.problem)
        assertEquals(2, withoutSize.line)
        assertEquals(LutProblem.NO_SIZE, refused("TITLE \"Nothing else\"\n").problem)
        assertEquals(LutProblem.NO_SIZE, refused("0 0 0\n1 1 1\n").problem)

        for (size in listOf("1", "66", "0", "-2", "33.5", "abc", "")) {
            val wrong = refused("LUT_3D_SIZE $size\n" + points(2))
            assertEquals("size '$size'", LutProblem.BAD_SIZE, wrong.problem)
            assertEquals(1, wrong.line)
        }
        assertEquals(LutProblem.BAD_SIZE, refused("LUT_3D_SIZE 2 2\n" + points(2)).problem)
        assertEquals(2, read("LUT_3D_SIZE 2\n" + points(2)).lut.size)
        assertEquals(65, read("LUT_3D_SIZE 65\n" + points(65)).lut.size)
    }

    @Test
    fun needsAUsableInputRange() {
        for (domain in listOf("DOMAIN_MIN 0 0\n", "DOMAIN_MAX 1 1 x\n", "DOMAIN_MIN 0 NaN 0\n", "LUT_3D_INPUT_RANGE 0\n", "DOMAIN_MAX 1 inf 1\n")) {
            val wrong = refused(domain + "LUT_3D_SIZE 2\n" + points(2))
            assertEquals(domain, LutProblem.BAD_DOMAIN, wrong.problem)
            assertEquals(1, wrong.line)
        }
        // Nothing between its ends.
        assertEquals(LutProblem.BAD_DOMAIN, refused("DOMAIN_MIN 0 0.5 0\nDOMAIN_MAX 1 0.5 1\nLUT_3D_SIZE 2\n" + points(2)).problem)
        assertEquals(LutProblem.BAD_DOMAIN, refused("LUT_3D_INPUT_RANGE 1 0\nLUT_3D_SIZE 2\n" + points(2)).problem)
    }

    @Test
    fun saysWhichLineOfTheTableIsNotThreeNumbers() {
        val lines = points(2).lines().toMutableList()
        fun withLine(index: Int, text: String) = "LUT_3D_SIZE 2\n" + lines.toMutableList().also { it[index] = text }.joinToString("\n")

        for (wrong in listOf("0.5 0.5", "0.5 0.5 0.5 0.5", "0.5 half 0.5", "0,5 0,5 0,5")) {
            val problem = refused(withLine(3, wrong))
            assertEquals(wrong, LutProblem.NOT_A_NUMBER, problem.problem)
            assertEquals(wrong, 5, problem.line)
        }
        // Keywords belong before the table.
        val late = refused("LUT_3D_SIZE 2\n" + points(2).lines().take(4).joinToString("\n") + "\nTITLE \"Late\"\n")
        assertEquals(LutProblem.NOT_A_NUMBER, late.problem)
        assertEquals(6, late.line)
    }

    @Test
    fun refusesValuesThatAreNotFiniteNumbers() {
        val lines = points(2).lines()
        for (wrong in listOf("NaN 0 0", "0 nan 0", "0 0 inf", "-Infinity 0 0", "1e999 0 0", "0 1.#INF 0", "0 0 -1.#IND")) {
            val text = "LUT_3D_SIZE 2\n" + (listOf(wrong) + lines.drop(1)).joinToString("\n")
            val problem = refused(text)
            assertEquals(wrong, LutProblem.NOT_FINITE, problem.problem)
            assertEquals(wrong, 2, problem.line)
        }
    }

    @Test
    fun tellsLutsForLogVideoByTheirNames() {
        for (name in listOf("SLog3SGamut3.CineToLC-709", "S-Log3 to Rec.709", "VLog_to_V709", "F-Log", "LogC to Rec709", "ZV-E10 LOG", "slog2", "Log3G10"))
            assertTrue(name, CubeLut.isForLogFootage(name))
        for (name in listOf("Teal and orange", "Analog film", "Catalog look", "Blog warm", "Kodak 2383"))
            assertFalse(name, CubeLut.isForLogFootage(name))
    }

    @Test
    fun needsAsManyLinesAsTheSizeAsksFor() {
        val tooFew = refused("LUT_3D_SIZE 3\n" + points(2))
        assertEquals(LutProblem.WRONG_COUNT, tooFew.problem)
        assertEquals(LutProblem.WRONG_COUNT, refused("LUT_3D_SIZE 2\n").problem)

        val tooMany = refused("LUT_3D_SIZE 2\n" + points(2) + "0.5 0.5 0.5\n")
        assertEquals(LutProblem.WRONG_COUNT, tooMany.problem)
        assertEquals(10, tooMany.line)
    }
}
