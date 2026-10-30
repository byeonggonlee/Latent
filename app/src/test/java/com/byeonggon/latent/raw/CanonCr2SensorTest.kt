package com.byeonggon.latent.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CanonCr2SensorTest {
    private fun metadata(width: Int = 8, height: Int = 2) = CanonCr2Metadata.Metadata(
        "Canon EOS 7D", width, height, 1, intArrayOf(0,0,width,height), floatArrayOf(2f,1f,1.5f), 12279,
        intArrayOf(1,4,4), 0, 0,
    )

    @Test fun decodesFourComponentLosslessJpegAndReordersVerticalSlices() {
        val rows = arrayOf(intArrayOf(0,1,2,3,8,9,10,11), intArrayOf(4,5,6,7,12,13,14,15))
        val stream = LosslessJpegEncoder.encode(rows, components = 4, precision = 14, predictor = 1)
        assertArrayEquals(ShortArray(16) { it.toShort() }, CanonCr2Sensor.decode(stream, metadata().copy(length = stream.size)))
    }

    @Test fun rejectsReducedRawAndUnexpectedFrameSizes() {
        val rows = arrayOf(IntArray(8) { it }, IntArray(8) { it + 8 })
        val rgb = LosslessJpegEncoder.encode(arrayOf(IntArray(6) { it }, IntArray(6) { it + 6 }), components = 3, precision = 14, predictor = 1)
        assertThrows(IOException::class.java) { CanonCr2Sensor.decode(rgb, metadata().copy(length = rgb.size)) }
        val bad = LosslessJpegEncoder.encode(rows, components = 4, precision = 14, predictor = 1)
        assertThrows(IOException::class.java) { CanonCr2Sensor.decode(bad, metadata(width = 6).copy(slices=intArrayOf(1,3,3),length=bad.size)) }
    }
}
