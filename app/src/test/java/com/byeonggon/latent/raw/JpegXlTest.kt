package com.byeonggon.latent.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun refusesWhatIsNotJpegXl() {
        assertThrows(IOException::class.java) { decode(ByteArray(300) { it.toByte() }) }
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 520
    }
}
