package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.Orientation
import com.byeonggon.latent.raw.api.RawCorrection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class PhoneDngTest {
    @Test
    fun appliesAllFourBayerGainMapsBeforeCroppingAndNormalisesTheShotNeutral() {
        // Android DngCreator writes a separate, interleaved map for each Bayer position.
        // Distinct gains expose a dropped map, a wrong parity or overlapping applications.
        val factors = listOf(2f, 3f, 5f, 7f)
        val maps = factors.mapIndexed { site, factor ->
            SyntheticDng.GainMap(2, 2, FloatArray(4) { factor }).bytes().also {
                ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN)
                    .putInt(20, site / 2).putInt(24, site % 2)
                    .putInt(44, 2).putInt(48, 2)
            }.drop(4).toByteArray()
        }
        val opcodes = ByteBuffer.allocate(4 + maps.sumOf { it.size }).order(ByteOrder.BIG_ENDIAN)
            .putInt(4).apply { maps.forEach { put(it) } }.array()
        val profile = SyntheticDng.ColorProfile(neutral = floatArrayOf(0.5f, 1.25f, 0.75f))
        val frame = Dng.decode(SyntheticDng.file(
            100, 100, 100, pattern = intArrayOf(2, 1, 1, 0), opcodes2 = opcodes,
            orientation = 6, profile = profile,
        ))
        val image = frame.legacyImage
        assertTrue(RawCorrection.GAIN_MAP in frame.corrections)
        assertArrayEquals(floatArrayOf(2.5f, 1f, 5f / 3f), image.whiteBalance, 0.0001f)
        val unshaded = (100 * 16383f / (SyntheticDng.WHITE - SyntheticDng.BLACK)).roundToInt()
        for (y in 0 until SyntheticDng.HEIGHT) for (x in 0 until SyntheticDng.WIDTH) {
            val ax = x - SyntheticDng.ACTIVE[1]
            val ay = y - SyntheticDng.ACTIVE[0]
            val inside = x in SyntheticDng.ACTIVE[1] until SyntheticDng.ACTIVE[3] &&
                y in SyntheticDng.ACTIVE[0] until SyntheticDng.ACTIVE[2]
            val expected = if (inside) (unshaded * factors[(ay and 1) * 2 + (ax and 1)]).roundToInt() else 0
            assertEquals("$x,$y", expected, image.sensor[y * image.width + x].toInt())
        }
        assertEquals(listOf(3, 3, 26, 18), listOf(image.cropLeft, image.cropTop, image.cropWidth, image.cropHeight))
        val upright = RawDeveloper.developPreview(image).oriented(Orientation.ofExif(image.orientation))
        assertEquals(9 to 13, upright.width to upright.height)
    }

    @Test
    fun keepsPhoneOrientationUntilBothFullAndPreviewPixelsAreTurnedUpright() {
        // For each EXIF value: source corners in output TL, TR, BL, BR order.
        val cornerOrders = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        for (orientation in 1..8) {
            val image = Dng.read(SyntheticDng.file(
                samples = { x, y -> SyntheticDng.BLACK + 80 + (if (x >= 16) 60 else 0) + (if (y >= 12) 120 else 0) },
                orientation = orientation,
            ))
            assertEquals(orientation, image.orientation)
            for (plain in listOf(RawDeveloper.develop(image), RawDeveloper.developPreview(image))) {
                fun corners(pixels: ShortArray, width: Int, height: Int) = listOf(
                    pixels[0], pixels[width - 1], pixels[(height - 1) * width], pixels.last(),
                )
                val before = corners(plain.green, plain.width, plain.height)
                assertEquals(4, before.distinct().size)
                val upright = plain.oriented(Orientation.ofExif(image.orientation))
                val dimensions = if (orientation >= 5) plain.height to plain.width else plain.width to plain.height
                assertEquals(dimensions, upright.width to upright.height)
                assertEquals(cornerOrders[orientation - 1].map { before[it] }, corners(upright.green, upright.width, upright.height))
            }
        }
    }
}
