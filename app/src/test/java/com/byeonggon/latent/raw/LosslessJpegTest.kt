package com.byeonggon.latent.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class LosslessJpegTest {
    private val random = Random(7)

    /** Rows of samples that vary like a picture: smooth, with noise and the odd jump. */
    private fun picture(lines: Int, width: Int, precision: Int) = Array(lines) { y ->
        IntArray(width) { x -> ((x * 37 + y * 11 + random.nextInt(64) + if (x % 9 == 0) 900 else 0) % (1 shl precision)) }
    }

    private fun decoded(stream: ByteArray, width: Int, height: Int, tileWidth: Int = width, left: Int = 0, top: Int = 0): ShortArray =
        ShortArray(width * height).also { LosslessJpeg.decode(stream, 0, stream.size, it, width, height, left, top, tileWidth, tileLength = 64) }

    private fun flat(rows: Array<IntArray>) = ShortArray(rows.size * rows[0].size) { rows[it / rows[0].size][it % rows[0].size].toShort() }

    @Test
    fun decodesWhatWasEncodedWithEveryPredictor() {
        for (predictor in 1..7) {
            val rows = picture(lines = 6, width = 20, precision = 12)
            val stream = LosslessJpegEncoder.encode(rows, components = 1, precision = 12, predictor = predictor)

            assertArrayEquals("predictor $predictor", flat(rows), decoded(stream, 20, 6))
        }
    }

    @Test
    fun decodesComponentsSideBySideAndSixteenBitSamples() {
        val rows = picture(lines = 5, width = 24, precision = 16).map { row -> IntArray(row.size) { row[it] * 3 % 65536 } }.toTypedArray()
        val stream = LosslessJpegEncoder.encode(rows, components = 2, precision = 16, predictor = 6)

        assertArrayEquals(flat(rows), decoded(stream, 24, 5))
    }

    @Test
    fun decodesAcrossRestartMarkers() {
        val rows = picture(lines = 8, width = 10, precision = 14)
        val stream = LosslessJpegEncoder.encode(rows, components = 1, precision = 14, predictor = 4, restartLines = 2)

        assertArrayEquals(flat(rows), decoded(stream, 10, 8))
    }

    @Test
    fun placesATileAndDropsWhatFallsOutsideTheImage() {
        val rows = picture(lines = 4, width = 8, precision = 10)
        val stream = LosslessJpegEncoder.encode(rows, components = 1, precision = 10, predictor = 1)

        // An image 10 by 5, the tile placed at 6, 2: only its first four columns and three rows are inside.
        val image = decoded(stream, width = 10, height = 5, tileWidth = 8, left = 6, top = 2)

        for (y in 0 until 5) for (x in 0 until 10) {
            val inside = x >= 6 && y >= 2
            val expected = if (inside) rows[y - 2][x - 6] else 0
            assertEquals("at $x, $y", expected, image[y * 10 + x].toInt())
        }
    }

    @Test
    fun refusesAFrameLargerThanItsTile() {
        val rows = picture(lines = 8, width = 8, precision = 10)
        val stream = LosslessJpegEncoder.encode(rows, components = 1, precision = 10, predictor = 1)

        assertThrows(IOException::class.java) { LosslessJpeg.decode(stream, 0, stream.size, ShortArray(64), 8, 8, 0, 0, tileWidth = 8, tileLength = 4) }
    }

    @Test
    fun refusesWhatIsNotLosslessJpeg() {
        assertThrows(IOException::class.java) { decoded(byteArrayOf(1, 2, 3, 4, 5, 6), 2, 2) }
        // A baseline JPEG's frame is not read as a lossless one.
        val baseline = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(), 0, 0)
        assertThrows(IOException::class.java) { decoded(baseline, 2, 2) }
    }
}
