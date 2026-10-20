package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.SyntheticDng.Layout
import com.byeonggon.latent.raw.SyntheticDng.Storage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.roundToInt

class DngTest {
    /** A level above black as the reader gives it: scaled so that the file's white is 16383. */
    private fun scaled(level: Int, black: Int = SyntheticDng.BLACK) = (level * 16383f / (SyntheticDng.WHITE - black)).roundToInt()

    /** The red, green, green and blue at the corner of the crop. */
    private fun corner(image: ArwImage): List<Int> {
        val at = image.cropTop * image.width + image.cropLeft
        return listOf(image.sensor[at], image.sensor[at + 1], image.sensor[at + image.width], image.sensor[at + image.width + 1]).map { it.toInt() }
    }

    @Test
    fun readsTheMosaicAndTheSettingsNeededToDevelopIt() {
        val image = Dng.read(SyntheticDng.file(red = 200, green = 400, blue = 300, orientation = 6))

        assertEquals(SyntheticDng.WIDTH to SyntheticDng.HEIGHT, image.width to image.height)
        // The default crop (1, 1 within the active area at 2, 2), moved by a photosite onto a red one.
        assertEquals(listOf(4, 4, 26, 18), listOf(image.cropLeft, image.cropTop, image.cropWidth, image.cropHeight))
        assertEquals(0, image.blackLevel)
        assertEquals(16383, image.whiteLevel)
        assertEquals(6, image.orientation)
        assertEquals(null, image.lens)
        assertEquals(listOf(scaled(200), scaled(400), scaled(400), scaled(300)), corner(image))
        // White balance from the neutral the phone measured: green over each colour.
        assertArrayEquals(floatArrayOf(2f, 1f, 1f / 0.7f), image.whiteBalance, 1e-3f)
        // Photosites outside the active area are black.
        assertEquals(0, image.sensor[0].toInt())
    }

    @Test
    fun findsTheRawImageUnderAPreviewAsAdobeLaysItOut() {
        val android = Dng.read(SyntheticDng.file(200, 400, 300))
        val adobe = Dng.read(SyntheticDng.file(200, 400, 300, layout = Layout.ADOBE))

        assertArrayEquals(android.sensor, adobe.sensor)
        assertArrayEquals(android.colorMatrix, adobe.colorMatrix, 1e-6f)
    }

    @Test
    fun startsTheCropOnARedPhotositeWhateverTheLayout() {
        for (pattern in listOf(intArrayOf(1, 0, 2, 1), intArrayOf(1, 2, 0, 1), intArrayOf(2, 1, 1, 0), intArrayOf(0, 1, 1, 2))) {
            val image = Dng.read(SyntheticDng.file(red = 200, green = 400, blue = 300, pattern = pattern))

            assertEquals(pattern.toList().toString(), listOf(scaled(200), scaled(400), scaled(400), scaled(300)), corner(image))
            assertTrue(image.cropLeft + image.cropWidth <= SyntheticDng.ACTIVE[3])
            assertTrue(image.cropTop + image.cropHeight <= SyntheticDng.ACTIVE[2])
        }
    }

    @Test
    fun readsPackedAndLosslessJpegSamplesAsTheSameMosaic() {
        val words = Dng.read(SyntheticDng.file(200, 400, 300)).sensor
        val packed = Dng.read(SyntheticDng.file(200, 400, 300, storage = Storage.PACKED_12)).sensor
        val jpeg = Dng.read(SyntheticDng.file(200, 400, 300, storage = Storage.LOSSLESS_JPEG)).sensor

        assertArrayEquals(words, packed)
        assertArrayEquals(words, jpeg)
    }

    @Test
    fun takesOffTheBlackLevelOfEachPhotositeOfThePattern() {
        val image = Dng.read(SyntheticDng.file(red = 200, green = 400, blue = 300, blacks = intArrayOf(60, 64, 64, 68)))

        // Scaled by the white above the highest black, the same for every photosite.
        assertEquals(listOf(scaled(200, 68), scaled(400, 68), scaled(400, 68), scaled(300, 68)), corner(image))
    }

    @Test
    fun linearizesThroughTheTable() {
        // A table that stores the levels at half their value: stored 2n stands for n.
        val table = IntArray(2048) { it / 2 }.also { it[it.lastIndex] = SyntheticDng.WHITE }
        val image = Dng.read(SyntheticDng.file(red = 200, green = 400, blue = 300, linearization = table))

        assertEquals(listOf(scaled(200), scaled(400), scaled(400), scaled(300)), corner(image))
    }

    @Test
    fun brightensTheCornersByTheGainMap() {
        // Gains of 1 along the top, 2 along the bottom: the middle row is half as much brighter.
        val map = SyntheticDng.GainMap(pointsDown = 2, pointsAcross = 2, gains = floatArrayOf(1f, 1f, 2f, 2f))
        val plain = Dng.read(SyntheticDng.file(100, 100, 100))
        val corrected = Dng.read(SyntheticDng.file(100, 100, 100, gainMap = map))

        val activeHeight = SyntheticDng.ACTIVE[2] - SyntheticDng.ACTIVE[0]
        for (y in listOf(0, activeHeight / 2, activeHeight - 1)) {
            val at = (SyntheticDng.ACTIVE[0] + y) * plain.width + SyntheticDng.ACTIVE[1] + 4
            val expected = 1f + (y + 0.5f) / activeHeight
            assertEquals("row $y", plain.sensor[at] * expected, corrected.sensor[at].toFloat(), 1f)
        }
    }

    @Test
    fun developsWhatThePhoneSawAsNeutralAsGray() {
        // Levels in the proportions of the neutral the phone measured.
        val image = Dng.read(SyntheticDng.file(red = 150, green = 300, blue = 210))
        val developed = RawDeveloper.develop(image)

        val middle = developed.height / 2 * developed.width + developed.width / 2
        val green = developed.green[middle].toInt()
        assertTrue(green > 0)
        assertEquals(green.toFloat(), developed.red[middle].toFloat(), green * 0.01f)
        assertEquals(green.toFloat(), developed.blue[middle].toFloat(), green * 0.01f)
    }

    @Test
    fun readsALinearDngWhoseColoursThePhoneFilledIn() {
        for (lossless in listOf(false, true)) {
            // In the proportions of the neutral the phone measured, each over its own black level.
            val image = Dng.read(SyntheticDng.linear(red = 192, green = 380, blue = 264, lossless = lossless))

            assertEquals(3, image.channels)
            assertEquals(6, image.orientation)
            assertEquals(28 to 20, image.cropWidth to image.cropHeight)
            // Each sample loses its own black, and comes to the same scale as a mosaic's.
            val at = ((image.cropTop + 5) * image.width + image.cropLeft + 5) * 3
            assertEquals("lossless $lossless", listOf(scaled(192), scaled(380, SyntheticDng.BLACK + 8), scaled(264, SyntheticDng.BLACK + 16)), listOf(image.sensor[at], image.sensor[at + 1], image.sensor[at + 2]).map { it.toInt() })
            // What the phone measured as neutral comes out gray, at full size and in the preview.
            for (developed in listOf(RawDeveloper.develop(image), RawDeveloper.developPreview(image))) {
                val middle = developed.height / 2 * developed.width + developed.width / 2
                val green = developed.green[middle].toInt()
                assertTrue(green > 0)
                assertEquals(green.toFloat(), developed.red[middle].toFloat(), green * 0.01f)
                assertEquals(green.toFloat(), developed.blue[middle].toFloat(), green * 0.01f)
            }
        }
    }

    @Test
    fun saysWhatItCannotReadYet() {
        val jpegXl = assertThrows(IOException::class.java) { Dng.read(SyntheticDng.file(200, 400, 300, compression = 52546)) }
        assertTrue(jpegXl.message!!.contains("JPEG XL"))
        assertThrows(IOException::class.java) { Dng.read(SyntheticDng.file(200, 400, 300, pattern = intArrayOf(0, 1, 2, 1))) }
        assertThrows(IOException::class.java) { Dng.read(SyntheticDng.file(200, 400, 300).copyOf(600)) }
    }

    @Test
    fun tellsADngFromASonyRawFile() {
        assertTrue(Dng.isDng(SyntheticDng.file(200, 400, 300)))
        assertFalse(Dng.isDng(SyntheticArw.file(300, 600, 400)))
        assertFalse(Dng.isDng(ByteArray(3)))
    }
}
