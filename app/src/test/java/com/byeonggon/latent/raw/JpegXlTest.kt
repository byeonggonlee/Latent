package com.byeonggon.latent.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JpegXlTest {
    private val file = JpegXlTest::class.java.getResourceAsStream("/raw/groups.jxl")!!.use { it.readBytes() }

    private fun decode(bytes: ByteArray): ShortArray {
        val into = ShortArray(WIDTH * HEIGHT * 3)
        JpegXl.decode(bytes, 0, bytes.size, into, WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT, channels = 3, bits = 16)
        return into
    }

    @Test
    fun readsASeveralGroupPictureSampleBySample() {
        val samples = decode(file)

        // The picture groups.py writes: smooth ramps and steps, in groups of their own.
        for ((x, y) in listOf(0 to 0, 599 to 519, 300 to 260, 17 to 411)) {
            val at = (y * WIDTH + x) * 3
            assertEquals("red at $x, $y", x * 5 + y * 3, samples[at].toInt())
            assertEquals("green at $x, $y", x * 2 + y * 7 + (x / 8) * (y / 8) % 50, samples[at + 1].toInt())
            assertEquals("blue at $x, $y", (x / 16 + y / 16) * 40, samples[at + 2].toInt())
        }
    }

    /** Cut short, the stream used to leave the decoder waiting for a group that never came; a limit makes that a failure. */
    @Test(timeout = 30_000)
    fun refusesAPictureCutShortInsteadOfWaitingForIt() {
        for (kept in listOf(file.size / 4, file.size * 2 / 3, file.size - 1)) {
            val error = assertThrows("kept $kept", IOException::class.java) { decode(file.copyOf(kept)) }
            assertEquals("Damaged JPEG XL data", error.message)
        }
    }

    @Test
    fun checksTheSameOrientedDimensionsAsTheCodecOutput() {
        // Header only: encoded 600x520, orientation 6, RGB16; no frame data is needed for preflight.
        val header = listOf(255, 10, 58, 16, 232, 74, 88, 248, 137, 9).map(Int::toByte).toByteArray()
        JpegXl.checkSize(header, 0, header.size, 520..520, 600..600, channels = 3)
    }

    @Test
    fun acceptsAnEdgeTileWithPaddedSamplesOutsideThePicture() {
        val into = ShortArray(590 * 510 * 3)
        JpegXl.decode(file, 0, file.size, into, 590, 510, 0, 0, 600, 520, channels = 3, bits = 16)
        val last = (590 * 510 - 1) * 3
        assertEquals(listOf(4472, 4790, 2680), into.slice(last until last + 3).map { it.toInt() })
    }

    @Test
    fun refusesAPictureLargerThanItsDngTile() {
        val into = ShortArray(32 * 24 * 3)
        assertThrows(IOException::class.java) {
            JpegXl.decode(file, 0, file.size, into, 32, 24, 0, 0, 32, 24, channels = 3, bits = 16)
        }
        assertTrue(into.all { it == 0.toShort() })
    }

    @Test
    fun refusesWhatIsNotJpegXl() {
        assertThrows(IOException::class.java) { decode(ByteArray(300) { it.toByte() }) }
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 520
    }
}
