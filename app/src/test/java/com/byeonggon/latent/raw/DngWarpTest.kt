package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.LinearImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngWarpTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun list(vararg bodies: ByteArray, id: Int = 1, flags: Int = 1): ByteArray =
        ByteBuffer.allocate(4 + bodies.sumOf { 16 + it.size }).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(bodies.size)
            bodies.forEach { putInt(id); putInt(0x01030000); putInt(flags); putInt(it.size); put(it) }
        }.array()

    private fun body(vararg planes: DoubleArray, cx: Double = 0.5, cy: Double = 0.5): ByteArray =
        ByteBuffer.allocate(20 + planes.size * 48).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(planes.size)
            planes.forEach { p -> p.forEach { putDouble(it) } }
            putDouble(cx); putDouble(cy)
        }.array()

    private fun DngWarp.at(x: Double, y: Double, plane: Int): Pair<Double, Double> {
        val point = doubleArrayOf(x, y)
        sourcePosition(point, plane)
        return point[0] to point[1]
    }

    private fun read(bytes: ByteArray) = Dng.read(SyntheticDng.file(100, 100, 100, opcodes3 = bytes))

    @Test fun readsTheStandardOpcodeList3TagInMosaicAndLinearFiles() {
        val opcodes = list(body(doubleArrayOf(0.9, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val files = SyntheticDng.Layout.entries.map { layout ->
            SyntheticDng.file(100, 100, 100, layout = layout, opcodes3 = opcodes)
        } + SyntheticDng.linear(100, 100, 100, opcodes3 = opcodes)
        for (file in files) {
            val (data, first) = TiffDirectory.open(file)
            val main = TiffDirectory.read(data, first)
            val raw = main.ints(0x014A)?.firstOrNull()?.let { TiffDirectory.read(data, it) } ?: main
            // DNG 1.7.1, p.58: OpcodeList3 is decimal tag 51022, not the tag following OpcodeList2.
            assertArrayEquals(opcodes, raw.bytes(51022))
            assertFalse(0xC742 in raw)
            assertNotNull(Dng.read(file).dngWarp)
        }
    }

    @Test fun identityDoesNotResampleOrChangeTheSensor() {
        val plain = Dng.read(SyntheticDng.file(100, 100, 100))
        val decoded = read(list(body(identity)))
        assertArrayEquals(plain.sensor, decoded.sensor)
        assertNull(decoded.dngWarp)
    }

    @Test fun readsBigEndianWarpInsideLittleEndianTiffAndPreservesActiveCoordinates() {
        val warp = read(list(body(doubleArrayOf(1.0, 0.1, 0.0, 0.0, 0.0, 0.0), cx = 0.25))).dngWarp!!
        // Active rectangle (2,2)-(30,22): centre (9,12); farthest corner squared radius 21²+10²=541.
        // Destination (19,12), normalized r²=100/541. Source x=19+100/541.
        val at = warp.at(19.0, 12.0, 0)
        assertEquals(19.1848428835, at.first, 1e-9)
        assertEquals(12.0, at.second, 1e-9)
    }

    @Test fun cropViewsRetainTheSameWarpMetadata() {
        val decoded = read(list(body(doubleArrayOf(0.95, 0.0, 0.0, 0.0, 0.0, 0.0))))
        assertSame(decoded.dngWarp, decoded.part(2, 2, 4, 4).dngWarp)
    }

    @Test fun tangentialTermsAndThreeColourPlanesUseTheirOwnCoefficients() {
        val warp = read(list(body(identity, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.01, 0.02), identity))).dngWarp!!
        // Centre (16,12), M²=14²+10²=296. At (26,17): x offset 10, y offset 5.
        val green = warp.at(26.0, 17.0, 1)
        assertEquals(26.4359286453, green.first, 1e-9)
        assertEquals(17.2179643226, green.second, 1e-9)
        assertEquals(26.0, warp.at(26.0, 17.0, 0).first, 0.0)
        assertEquals(26.0, warp.at(26.0, 17.0, 2).first, 0.0)
    }

    @Test fun malformedOrUnsupportedRequiredListsDoNotExposePartialCorrection() {
        val good = body(doubleArrayOf(0.9, 0.0, 0.0, 0.0, 0.0, 0.0))
        val bad = listOf(
            list(good).copyOf(40), list(good, good).copyOf(100),
            list(body(identity, identity)), list(body(identity.copyOf().apply { this[1] = Double.NaN })),
            list(body(identity, cx = 1.1)), list(good, id = 2, flags = 0),
            ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array(),
        )
        bad.forEach { assertNull(read(it).dngWarp) }
        assertNull(read(list(good, id = 42)).dngWarp)
    }

    @Test fun orderedWarpsAreNotSilentlyCollapsedToTheLastOne() {
        val warp = read(list(body(doubleArrayOf(0.8, 0.0, 0.0, 0.0, 0.0, 0.0)), body(doubleArrayOf(0.5, 0.0, 0.0, 0.0, 0.0, 0.0)))).dngWarp!!
        assertEquals(20.0, warp.at(26.0, 12.0, 0).first, 1e-9)
    }

    @Test fun correctionKeepsTheEdgesInsideTheAvailableCrop() {
        val warp = read(list(body(doubleArrayOf(1.0, 0.2, 0.0, 0.0, 0.0, 0.0)))).dngWarp!!
        val raw = LinearImage(26, 18)
        // Paint a smooth source x-ramp: resampling can be checked at any mapped position.
        for (y in 0 until 18) for (x in 0 until 26) raw.red[y * 26 + x] = (x * 1000).toShort()
        val decoded = read(list(body(doubleArrayOf(1.0, 0.2, 0.0, 0.0, 0.0, 0.0))))
        warp.straighten(raw, decoded, 1)
        assertTrue(raw.red[9 * 26 + 2] > 2000)
        assertTrue(raw.red[9 * 26 + 23] < 23000)
        assertTrue(raw.red.all { it in 0..25000 })
    }
    private fun rampImage(warp: DngWarp?, matrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)): ArwImage {
        val samples = ShortArray(32 * 24 * 3) { at ->
            val x = at / 3 % 32
            val y = at / 3 / 32
            (when (at % 3) { 0 -> x * 100; 1 -> x * 200; else -> y * 100 }).toShort()
        }
        return ArwImage(32, 24, 4, 4, 26, 18, 0, 16383, floatArrayOf(1f, 1f, 1f), matrix,
            1f, null, 1, samples, channels = 3, dngWarp = warp)
    }

    @Test fun appliesPlaneWarpBeforeTheColourMatrixMixesCameraChannels() {
        val warp = read(list(body(doubleArrayOf(0.5, 0.0, 0.0, 0.0, 0.0, 0.0), identity, identity))).dngWarp!!
        val raw = rampImage(warp, floatArrayOf(0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        val corrected = RawDeveloper.develop(raw, lensCorrection = true)
        // At sensor x=6, red samples x=11 (1100), green stays at x=6 (1200).
        // Their matrix average is 1150 sensor units, converted to the 16383 output scale.
        assertEquals(1150, corrected.red[4 * 26 + 2].toInt())
        assertEquals(1200, corrected.green[4 * 26 + 2].toInt())
        val plain = RawDeveloper.develop(raw)
        assertEquals(900, plain.red[4 * 26 + 2].toInt())
    }

    @Test fun previewBlockCentresMatchFullGeometryEvenWhenTheLastBlockIsTruncated() {
        val warp = read(list(body(doubleArrayOf(0.8, 0.0, 0.0, 0.0, 0.0, 0.0)))).dngWarp!!
        val raw = rampImage(warp)
        val full = RawDeveloper.develop(raw, lensCorrection = true)
        for (cells in 1..2) {
            val preview = RawDeveloper.developPreview(raw, cells, lensCorrection = true)
            val side = cells * 2
            for (y in 1 until preview.height - 1) for (x in 1 until preview.width - 1) {
                val mean = (0 until side).sumOf { dy -> (0 until side).sumOf { dx ->
                    full.red[(y * side + dy) * full.width + x * side + dx].toInt()
                } }.toDouble() / (side * side)
                assertEquals(mean, preview.red[y * preview.width + x].toDouble(), 2.0)
            }
        }
    }

    @Test fun compositionUsesLastInverseFirstWhenCentresDiffer() {
        val a = body(doubleArrayOf(0.8, 0.0, 0.0, 0.0, 0.0, 0.0), cx = 0.25)
        val b = body(doubleArrayOf(0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
        val warp = read(list(a, b)).dngWarp!!
        // Last warp: 26 -> 21 around x=16. First warp: 21 ->18.6 around x=9.
        assertEquals(18.6, warp.at(26.0, 12.0, 0).first, 1e-9)
    }

    @Test fun optionalUnknownOpcodeCanBeSkippedWithoutLosingTheKnownWarp() {
        val known = list(body(doubleArrayOf(0.9, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val unknown = list(byteArrayOf(42), id = 42)
        val combined = ByteBuffer.allocate(known.size + unknown.size - 4).apply {
            putInt(2); put(unknown, 4, unknown.size - 4); put(known, 4, known.size - 4)
        }.array()
        assertNotNull(read(combined).dngWarp)
    }

    @Test fun damagedHeadersAndExtremeTransformsDoNotMakeOpeningThePhotoFail() {
        val good = list(body(doubleArrayOf(0.9, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val cases = listOf(
            good.copyOf().apply { ByteBuffer.wrap(this).putInt(16, Int.MAX_VALUE) },
            good.copyOf().apply { ByteBuffer.wrap(this).putInt(8, 0x01040000) },
            list(body(doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0, 0.0))),
            list(body(doubleArrayOf(1e300, 0.0, 0.0, 0.0, 0.0, 0.0))),
        )
        cases.forEach { assertNull(read(it).dngWarp) }
    }

    @Test fun radialWarpStraightensABowedGridLine() {
        val warp = DngWarp.read(list(body(doubleArrayOf(1.0, 0.4, 0.0, 0.0, 0.0, 0.0))),
            intArrayOf(0, 0, 192, 256), intArrayOf(0, 0, 256, 192))!!
        val image = LinearImage(256, 192)
        // Build the distorted line by solving rSource = rCorrected + 0.4*rCorrected³.
        // This scalar inverse fixture is independent of the production inverse sampler.
        for (y in 0 until 192) for (x in 0 until 256) {
            val dx = (x - 128) / 160.0
            val dy = (y - 96) / 160.0
            val sourceRadius = kotlin.math.hypot(dx, dy)
            var low = 0.0
            var high = sourceRadius
            repeat(40) {
                val mid = (low + high) / 2
                if (mid + 0.4 * mid * mid * mid < sourceRadius) low = mid else high = mid
            }
            val correctedX = 128 + if (sourceRadius == 0.0) 0.0 else dx * ((low + high) / 2) / sourceRadius * 160
            val distance = correctedX - 180
            image.red[y * 256 + x] = (30000 * kotlin.math.exp(-distance * distance / 2)).toInt().toShort()
        }
        fun peak(y: Int) = (150..220).maxBy { image.red[y * 256 + it].toInt() }
        val bowed = listOf(20, 50, 96, 140, 170).map(::peak)
        assertTrue(bowed.max() - bowed.min() >= 3)
        val raw = ArwImage(256, 192, 0, 0, 256, 192, 0, 16383, floatArrayOf(1f, 1f, 1f),
            FloatArray(9), 1f, null, 1, ShortArray(256 * 192), dngWarp = warp)
        warp.straighten(image, raw, 1)
        val straight = listOf(20, 50, 96, 140, 170).map(::peak)
        assertTrue("line peaks $straight", straight.max() - straight.min() <= 1)
    }

    @Test fun nonsquareDefaultScaleDoesNotExposeSquarePixelWarp() {
        val opcodes = list(body(doubleArrayOf(0.9, 0.0, 0.0, 0.0, 0.0, 0.0)))
        val square = Dng.read(SyntheticDng.file(100, 100, 100, opcodes3 = opcodes, defaultScale = floatArrayOf(2f, 2f)))
        assertNotNull(square.dngWarp)
        for (scale in listOf(floatArrayOf(1f, 2f), floatArrayOf(0f, 0f), floatArrayOf(1f))) {
            val image = Dng.read(SyntheticDng.file(100, 100, 100, opcodes3 = opcodes, defaultScale = scale))
            assertNull(image.dngWarp)
        }
    }

    @Test fun integerPixelCentresMatchTheDngSdkSampler() {
        val warp = read(list(body(doubleArrayOf(0.8, 0.0, 0.0, 0.0, 0.0, 0.0)))).dngWarp!!
        val corrected = RawDeveloper.develop(rampImage(warp), lensCorrection = true)
        // SDK centre x=16, destination sensor pixel x=6: source=16+(6-16)*0.8=8.
        assertEquals(800, corrected.red[4 * 26 + 2].toInt())
    }

    @Test fun presentUnreadableDefaultScaleIsNotTreatedAsAbsent() {
        val file = SyntheticDng.file(100, 100, 100,
            opcodes3 = list(body(doubleArrayOf(0.9, 0.0, 0.0, 0.0, 0.0, 0.0))), defaultScale = floatArrayOf(1f, 1f))
        val directory = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val entries = directory.getShort(8).toInt() and 0xffff
        val at = (0 until entries).map { 10 + it * 12 }.single { (directory.getShort(it).toInt() and 0xffff) == 0xC61E }
        // A valid TIFF ASCII field, but not a numeric DNG DefaultScale.
        directory.putShort(at + 2, 2)
        directory.putInt(at + 4, 2)
        directory.putInt(at + 8, 0x31)
        assertNull(Dng.read(file).dngWarp)
    }

}
