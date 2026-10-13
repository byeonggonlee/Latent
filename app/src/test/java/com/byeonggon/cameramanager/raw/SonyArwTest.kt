package com.byeonggon.cameramanager.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SonyArwTest {
    @Test
    fun readsTheSensorDataAndTheSettingsNeededToDevelopIt() {
        val image = SonyArw.read(SyntheticArw.file(red = 300, green = 600, blue = 400, orientation = 6))

        assertEquals(SyntheticArw.WIDTH, image.width)
        assertEquals(SyntheticArw.HEIGHT, image.height)
        assertEquals(listOf(4, 4, 56, 8), listOf(image.cropLeft, image.cropTop, image.cropWidth, image.cropHeight))
        assertEquals(512, image.blackLevel)
        assertEquals(16383, image.whiteLevel)
        assertEquals(listOf(2f, 1f, 1.5f), image.whiteBalance.toList())
        assertEquals(6, image.orientation)
        // Stored values this low sit on the straight part of the curve, where they simply double.
        val w = image.width
        assertEquals(listOf(600, 1200, 1200, 800), listOf(image.sensor[0], image.sensor[1], image.sensor[w], image.sensor[w + 1]).map { it.toInt() })
        assertTrue(image.sensor.indices.all { index ->
            val expected = if (index / w % 2 == 0) (if (index % 2 == 0) 600 else 1200) else (if (index % 2 == 0) 1200 else 800)
            image.sensor[index].toInt() == expected
        })
    }

    @Test
    fun readsTheLensProfile() {
        val lens = SonyArw.read(SyntheticArw.file(300, 600, 400)).lens!!

        assertArrayEquals(SyntheticArw.DISTORTION, lens.distortion)
        assertArrayEquals(SyntheticArw.RED_ABERRATION, lens.redAberration)
        assertArrayEquals(SyntheticArw.BLUE_ABERRATION, lens.blueAberration)
    }

    @Test
    fun unpacksABlockFromItsExtremesAndOffsets() {
        // A spread of 800 needs offsets in steps of 8 to fit into 7 bits.
        val offsets = IntArray(14) { 5 * it }
        val block = SyntheticArw.block(max = 1000, min = 200, maxIndex = 3, minIndex = 7, offsets = offsets)
        val file = ByteArray(32)
        block.copyInto(file)
        val identity = IntArray(4096) { it }

        val sensor = SonyArw.decode(file, stripOffset = 0, width = 32, height = 1, curve = identity)

        val expected = ArrayList<Int>()
        var next = 0
        for (index in 0 until 16) {
            expected += when (index) {
                3 -> 1000
                7 -> 200
                else -> minOf(200 + (offsets[next++] shl 3), 2047)
            }
        }
        // The block fills every other column; values are doubled on their way through the curve.
        assertEquals(expected.map { it * 2 }, (0 until 16).map { sensor[2 * it].toInt() })
    }

    @Test
    fun expandsStoredValuesInEverLargerSteps() {
        val curve = SonyArw.toneCurve(intArrayOf(8000, 10400, 12900, 14100))

        assertEquals(1000, curve[1000])
        assertEquals(2000, curve[2000])
        assertEquals(2002, curve[2001])
        assertEquals(3200, curve[2600])
        assertEquals(3204, curve[2601])
        assertTrue((1 until curve.size).all { curve[it] > curve[it - 1] })
    }

    @Test
    fun unscramblingIsItsOwnInverse() {
        val original = IntArray(300) { it * 2654435761L.toInt() }
        val words = original.copyOf()

        SonyArw.unscramble(words, key = 0x44332211)
        assertTrue(!words.contentEquals(original))
        SonyArw.unscramble(words, key = 0x44332211)

        assertArrayEquals(original, words)
    }

    @Test
    fun rejectsFilesItCannotDevelop() {
        assertThrows(IOException::class.java) { SonyArw.read(ByteArray(64) { it.toByte() }) }
        assertThrows(IOException::class.java) { SonyArw.read(SyntheticArw.file(300, 600, 400, make = "Canon")) }
        assertThrows(IOException::class.java) { SonyArw.read(SyntheticArw.file(300, 600, 400).copyOf(5000)) }
    }
}
