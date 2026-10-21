package com.byeonggon.latent.raw

import com.byeonggon.latent.raw.SyntheticDng.Layout
import com.byeonggon.latent.raw.SyntheticDng.Storage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private val dualProfile = SyntheticDng.ColorProfile(
        matrix1 = floatArrayOf(1f, 0.1f, 0f, 0f, 1f, 0.1f, 0.1f, 0f, 1f),
        matrix2 = floatArrayOf(0.8f, -0.1f, 0.1f, 0.1f, 1.1f, 0f, 0f, 0.1f, 0.9f),
        calibration1 = floatArrayOf(1f, 0.02f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        calibration2 = floatArrayOf(0.9f, 0f, 0f, 0.03f, 1.1f, 0f, 0f, 0.02f, 1f),
        analogBalance = floatArrayOf(1.1f, 1f, 0.9f),
        neutral = floatArrayOf(0.826882372f, 1f, 0.539704874f),
    )
    // Independent Gauss–Jordan reference; dual profiles additionally use SDK D50/4-decimal CM normalization.
    private val tungstenMatrix = floatArrayOf(
        3.509343779f, -2.139854568f, -0.369489210f,
        -1.022992860f, 2.207182920f, -0.184190061f,
        -0.055849855f, -0.219312437f, 1.275162293f,
    )
    private val daylightMatrix = floatArrayOf(
        3.224001980f, -1.101553092f, -1.122448887f,
        -1.114576183f, 1.891102956f, 0.223473227f,
        0.086989180f, -0.370824027f, 1.283834848f,
    )
    private val middleMatrix = floatArrayOf(
        3.459153695f, -1.739223797f, -0.719929898f,
        -1.111991735f, 2.103828679f, 0.008163056f,
        0.013284284f, -0.300529088f, 1.287244804f,
    )

    private fun withProfile(profile: SyntheticDng.ColorProfile) = Dng.read(SyntheticDng.file(200, 400, 300, profile = profile))

    @Test
    fun clampsTheTungstenAndDaylightMatricesOutsideTheirCalibrationTemperatures() {
        // Neutrals of the composite endpoint matrices under xy=(.480,.414) and D75, respectively.
        val warm = withProfile(dualProfile.copy(neutral = floatArrayOf(1.372777202f, 1f, 0.326424870f)))
        val cool = withProfile(dualProfile.copy(neutral = floatArrayOf(0.578943548f, 1f, 0.825811807f)))
        assertArrayEquals(tungstenMatrix, warm.colorMatrix, 0.003f)
        assertArrayEquals(daylightMatrix, cool.colorMatrix, 0.003f)
        assertArrayEquals(warm.sensor, cool.sensor)
    }

    @Test
    fun infersAnIntermediateWhiteFromTheCameraNeutral() {
        // Robertson's 250-mired point (4000 K): first weight = (250-1e6/6500)/(1e6/2850-1e6/6500).
        val profile = dualProfile.copy(
            matrix2 = floatArrayOf(0.96f, 0.06f, 0.02f, 0.02f, 1.02f, 0.08f, 0.08f, 0.02f, 0.98f),
            calibration2 = floatArrayOf(0.98f, 0.016f, 0f, 0.006f, 1.02f, 0f, 0f, 0.004f, 1f),
            neutral = floatArrayOf(1.091795751f, 1f, 0.610436835f),
        )
        val image = withProfile(profile)
        val expected = floatArrayOf(
            3.508669936f, -2.066703499f, -0.441966437f,
            -1.046514303f, 2.191908350f, -0.145394047f,
            -0.041894230f, -0.236643171f, 1.278537401f,
        )
        assertArrayEquals(expected, image.colorMatrix, 0.003f)
        assertArrayEquals(floatArrayOf(1f / 1.0918f, 1f, 1f / 0.6104f), image.whiteBalance, 0.001f)
    }

    @Test
    fun usesAnExplicitWhiteWithTheSameInverseTemperatureWeight() {
        val image = withProfile(dualProfile.copy(neutral = null, whiteXY = floatArrayOf(0.380446172f, 0.376756239f)))
        assertArrayEquals(middleMatrix, image.colorMatrix, 0.003f)
        assertArrayEquals(floatArrayOf(1f / 0.841711095f, 1f, 1f / 0.543624844f), image.whiteBalance, 0.003f)
    }

    @Test
    fun theBlendMovesOneWayWithTheSceneTemperature() {
        // Explicit whites at Robertson's 200, 250 and 325 mired points; this matrix entry increases with warmth.
        val whites = listOf(floatArrayOf(0.345104142f, 0.351622522f), floatArrayOf(0.380446172f, 0.376756239f), floatArrayOf(0.430438419f, 0.402581260f))
        val values = whites.map { withProfile(dualProfile.copy(neutral = null, whiteXY = it)).colorMatrix[2] }
        assertEquals(-0.919378391f, values[0], 0.003f)
        assertEquals(-0.719929898f, values[1], 0.003f)
        assertEquals(-0.453615123f, values[2], 0.003f)
        assertTrue(values[0] < values[1] && values[1] < values[2])
    }

    @Test
    fun reversingTheCalibrationOrderDoesNotChangeTheBlend() {
        val reversed = dualProfile.copy(
            matrix1 = dualProfile.matrix2, matrix2 = dualProfile.matrix1,
            calibration1 = dualProfile.calibration2, calibration2 = dualProfile.calibration1,
            illuminant1 = 21, illuminant2 = 17,
            neutral = null, whiteXY = floatArrayOf(0.380446172f, 0.376756239f),
        )
        assertArrayEquals(middleMatrix, withProfile(reversed).colorMatrix, 0.003f)
    }

    @Test
    fun aColorMatrixExposureScaleDoesNotChangeTheBlend() {
        val profile = dualProfile.copy(neutral = null, whiteXY = floatArrayOf(0.380446172f, 0.376756239f))
        val scaled = profile.copy(matrix1 = profile.matrix1!!.map { it * 2 }.toFloatArray())
        assertArrayEquals(withProfile(profile).colorMatrix, withProfile(scaled).colorMatrix, 0.001f)
    }

    @Test
    fun anOscillatingNeutralUsesTheSdkThirtyPassAverage() {
        // This deliberately strong pair alternates between two whites; the independent 30-pass reference
        // ends at average xy=(.376109004,.376143311), or 242.989353 mired, after profile normalization.
        val expected = floatArrayOf(
            3.449271785f, -1.702535042f, -0.746736743f,
            -1.115383962f, 2.092698211f, 0.022685750f,
            0.018521747f, -0.306119940f, 1.287598193f,
        )
        assertArrayEquals(expected, withProfile(dualProfile).colorMatrix, 0.003f)
    }

    @Test
    fun aSingleOrUnspecifiedIlluminantKeepsTheLegacyMatrixSelection() {
        val firstOnly = withProfile(dualProfile.copy(matrix2 = null))
        val secondOnly = withProfile(dualProfile.copy(matrix1 = null))
        assertArrayEquals(tungstenMatrix, firstOnly.colorMatrix, 0.00001f)
        assertArrayEquals(daylightMatrix, secondOnly.colorMatrix, 0.00001f)
        assertArrayEquals(daylightMatrix, withProfile(dualProfile.copy(illuminant1 = 0)).colorMatrix, 0.00001f)
        assertArrayEquals(tungstenMatrix, withProfile(dualProfile.copy(illuminant2 = 17)).colorMatrix, 0.00001f)
        assertArrayEquals(daylightMatrix, withProfile(dualProfile.copy(neutral = null)).colorMatrix, 0.00001f)
    }

    @Test
    fun aSingleMatrixDoesNotReadTheUnusedIlluminantPayload() {
        val file = SyntheticDng.file(200, 400, 300, profile = dualProfile.copy(matrix2 = null))
        val data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val count = data.getShort(8).toInt() and 0xFFFF
        val entry = (0 until count).map { 10 + 12 * it }.first { (data.getShort(it).toInt() and 0xFFFF) == 0xC65B }
        data.putInt(entry + 4, 3).putInt(entry + 8, Int.MAX_VALUE)
        assertArrayEquals(tungstenMatrix, Dng.read(file).colorMatrix, 0.00001f)
    }

    @Test
    fun invalidWhiteCoordinatesKeepTheLegacyFallback() {
        for (white in listOf(floatArrayOf(0f, 0f), floatArrayOf(0.8f, 0.8f))) {
            assertArrayEquals(daylightMatrix, withProfile(dualProfile.copy(neutral = null, whiteXY = white)).colorMatrix, 0.00001f)
        }
        val single = dualProfile.copy(matrix2 = null, neutral = null)
        val before = withProfile(single)
        val after = withProfile(single.copy(whiteXY = floatArrayOf(0.480f, 0.414f)))
        assertArrayEquals(before.colorMatrix, after.colorMatrix, 0f)
        assertArrayEquals(before.whiteBalance, after.whiteBalance, 0f)
    }

    @Test
    fun aSingularUnusedCalibrationDoesNotBreakTheLegacyFallback() {
        val broken = dualProfile.copy(calibration1 = FloatArray(9))
        assertArrayEquals(daylightMatrix, withProfile(broken).colorMatrix, 0.00001f)
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
    fun readsTheSamplesOfExpertRawFromJpegXl() {
        val words = Dng.read(SyntheticDng.linear(red = 192, green = 380, blue = 264))
        val image = Dng.read(SyntheticDng.linear(red = 192, green = 380, blue = 264, jpegXl = true))

        assertEquals(3, image.channels)
        assertEquals(words.width to words.height, image.width to image.height)
        val active = SyntheticDng.ACTIVE
        for (y in 0 until image.height) for (x in 0 until image.width) for (channel in 0 until 3) {
            val at = (y * image.width + x) * 3 + channel
            val inGradient = y >= active[0] && y < active[0] + 6 && x >= active[1] && x < active[1] + 6
            if (!inGradient) assertEquals("$x, $y, $channel", words.sensor[at], image.sensor[at])
        }
        // In the gradient, red rises 10 a column, green 7 a row, blue by the product of both.
        val at = (3 * image.width + 5) * 3
        assertEquals(
            listOf(scaled(192 + 50), scaled(380 + 21, SyntheticDng.BLACK + 8), scaled(264 + 15, SyntheticDng.BLACK + 16)),
            listOf(image.sensor[at], image.sensor[at + 1], image.sensor[at + 2]).map { it.toInt() },
        )
    }

    @Test
    fun bringsUpTheBaselineExposureAsFarAsWhite() {
        val plain = Dng.read(SyntheticDng.linear(red = 192, green = 380, blue = 264))
        val oneStop = Dng.read(SyntheticDng.linear(red = 192, green = 380, blue = 264, baselineExposure = 1f))
        val darker = Dng.read(SyntheticDng.linear(red = 192, green = 380, blue = 264, baselineExposure = -1f))
        val tooFar = Dng.read(SyntheticDng.linear(red = 192, green = 380, blue = 264, baselineExposure = 6f))

        for (at in plain.sensor.indices) {
            assertEquals(at.toString(), plain.sensor[at] * 2f, oneStop.sensor[at].toFloat(), 1f)
            assertEquals(plain.sensor[at], darker.sensor[at])
        }
        // Six stops would take the brightest far past white; it is brought to white and no further, the rest in proportion.
        val brightest = plain.sensor.indices.maxBy { plain.sensor[it] }
        assertEquals(16383, tooFar.sensor[brightest].toInt())
        assertEquals(tooFar.sensor.max().toInt(), 16383)
    }

    @Test
    fun saysWhatItCannotRead() {
        // Not a JPEG XL stream where the file says one is.
        val jpegXl = assertThrows(IOException::class.java) { Dng.read(SyntheticDng.file(200, 400, 300, compression = 52546)) }
        assertTrue(jpegXl.message!!.contains("JPEG XL"))
        assertThrows(IOException::class.java) { Dng.read(SyntheticDng.file(200, 400, 300, pattern = intArrayOf(0, 1, 2, 1))) }
        assertThrows(IOException::class.java) { Dng.read(SyntheticDng.file(200, 400, 300).copyOf(600)) }
    }

    @Test(timeout = 30_000)
    fun aCutShortMultiGroupDngFailsAndTheNextReadSucceeds() {
        val stream = javaClass.getResourceAsStream("/raw/groups.jxl")!!.use { it.readBytes() }
        for (kept in listOf(stream.size / 4, stream.size * 2 / 3, stream.size - 1)) {
            val file = SyntheticDng.jpegXl(stream)
            // The file still contains every byte: only its declared strip length cuts the stream short.
            setFirstInt(file, 0x0117, kept)
            assertThrows(IOException::class.java) { Dng.read(file) }
            val image = Dng.read(SyntheticDng.jpegXl(stream))
            assertEquals(600 * 520 * 3, image.sensor.size)
            // At 300,260: red = 2280, green = 2420 + (37*32 % 50) = 2454, blue = 1360.
            val at = (260 * 600 + 300) * 3
            assertEquals(listOf(570, 613, 340), image.sensor.slice(at until at + 3).map { it.toInt() })
        }
    }

    @Test
    fun ignoresAnUnderstatedUncompressedStripByteCount() = assertUncompressedStripLengthIgnored(1)

    @Test
    fun ignoresAnOverstatedUncompressedStripByteCount() = assertUncompressedStripLengthIgnored(Int.MAX_VALUE)

    private fun assertUncompressedStripLengthIgnored(length: Int) {
        for (storage in listOf(Storage.WORDS, Storage.PACKED_12)) {
            val file = SyntheticDng.file(200, 400, 300, storage = storage)
            val expected = Dng.read(file).sensor
            setFirstInt(file, 0x0117, length)
            assertArrayEquals(expected, Dng.read(file).sensor)
        }
    }

    @Test
    fun anUncompressedStripWithAMissingRowByteStillFails() {
        for (storage in listOf(Storage.WORDS, Storage.PACKED_12)) {
            val file = SyntheticDng.file(200, 400, 300, storage = storage)
            val (data, directory) = TiffDirectory.open(file)
            val raw = TiffDirectory.read(data, directory)
            val offset = raw.int(0x0111)!!
            val length = raw.int(0x0117)!!
            // Append the strip after all metadata, then remove its last byte alone.
            val cutShort = file + file.copyOfRange(offset, offset + length - 1)
            setFirstInt(cutShort, 0x0111, file.size)
            setFirstInt(cutShort, 0x0117, Int.MAX_VALUE)
            val failure = assertThrows(IOException::class.java) { Dng.read(cutShort) }
            assertEquals("Raw image data is cut short", failure.message)
        }
    }

    @Test
    fun jpegXlStillRequiresItsWholeDeclaredStripRange() {
        val stream = javaClass.getResourceAsStream("/raw/groups.jxl")!!.use { it.readBytes() }
        val file = SyntheticDng.jpegXl(stream)
        setFirstInt(file, 0x0117, Int.MAX_VALUE)
        assertThrows(IOException::class.java) { Dng.read(file) }
    }

    @Test
    fun refusesAnOverflowingTileLayout() {
        val file = SyntheticDng.file(200, 400, 300)
        val data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val count = data.getShort(8).toInt() and 0xFFFF
        for (i in 0 until count) {
            val at = 10 + 12 * i
            when (data.getShort(at).toInt() and 0xFFFF) {
                0x0111 -> data.putShort(at, 0x0144.toShort())
                0x0117 -> data.putShort(at, 0x0145.toShort())
                0xC61F -> { data.putShort(at, 0x0142.toShort()); data.putInt(at + 4, 1); data.putInt(at + 8, Int.MAX_VALUE) }
                0xC620 -> { data.putShort(at, 0x0143.toShort()); data.putInt(at + 4, 1); data.putInt(at + 8, Int.MAX_VALUE) }
            }
        }
        assertThrows(IOException::class.java) { Dng.read(file) }
    }

    @Test
    fun aDamagedOptionalIccPointerDoesNotHideTheImage() {
        val stream = javaClass.getResourceAsStream("/raw/groups.jxl")!!.use { it.readBytes() }
        val file = SyntheticDng.jpegXl(stream, iccProfile = byteArrayOf(1, 2, 3, 4, 5))
        val expected = Dng.read(file).sensor
        setFirstInt(file, 0x8773, Int.MAX_VALUE)
        val frame = Dng.decode(file)
        assertNull(frame.iccProfile())
        assertArrayEquals(expected, frame.legacyImage.sensor)
    }

    @Test
    fun losslessJpegKeepsClippingAnOverstatedStripLengthToEof() {
        val file = SyntheticDng.file(200, 400, 300, storage = Storage.LOSSLESS_JPEG)
        val expected = Dng.read(file).sensor
        setFirstInt(file, 0x0117, Int.MAX_VALUE)
        assertArrayEquals(expected, Dng.read(file).sensor)
    }

    private fun setFirstInt(file: ByteArray, tag: Int, value: Int) {
        val data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val count = data.getShort(8).toInt() and 0xFFFF
        val at = (0 until count).map { 10 + 12 * it }.first { (data.getShort(it).toInt() and 0xFFFF) == tag }
        data.putInt(at + 8, value)
    }

    @Test
    fun tellsADngFromASonyRawFile() {
        assertTrue(Dng.isDng(SyntheticDng.file(200, 400, 300)))
        assertFalse(Dng.isDng(SyntheticArw.file(300, 600, 400)))
        assertFalse(Dng.isDng(ByteArray(3)))
    }
}
