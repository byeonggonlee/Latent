package com.byeonggon.latent.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class Cr2SlicesTest {
    @Test fun joinsVerticalSlicesIntoSensorRows() {
        val sliced = shortArrayOf(0, 1, 6, 7, 2, 3, 8, 9, 4, 5, 10, 11)
        assertArrayEquals(ShortArray(12) { it.toShort() }, Cr2Slices.join(sliced, 6, 2, intArrayOf(2, 2, 2)))
    }

    @Test fun handlesAnUnequalFinalSlice() {
        val sliced = shortArrayOf(0, 1, 5, 6, 2, 3, 7, 8, 4, 9)
        assertArrayEquals(ShortArray(10) { it.toShort() }, Cr2Slices.join(sliced, 5, 2, intArrayOf(2, 2, 1)))
    }

    @Test fun acceptsAnUnsplitSensor() {
        val sensor = ShortArray(12) { it.toShort() }
        assertArrayEquals(sensor, Cr2Slices.join(sensor, 6, 2, intArrayOf(0, 0, 6)))
    }

    @Test fun checksCancellationWhileRearrangingRows() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            Cr2Slices.join(ShortArray(12), 6, 2, intArrayOf(2, 2, 2)) {
                if (++calls == 2) throw CancellationException("cancelled")
            }
        }
    }

    @Test fun rejectsInconsistentOrOversizedSliceDescriptions() {
        for (description in listOf(intArrayOf(2, 2, 3), intArrayOf(0, 0, 5), intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, 1), intArrayOf(1))) {
            assertThrows(IOException::class.java) { Cr2Slices.join(ShortArray(12), 6, 2, description) }
        }
        assertThrows(IOException::class.java) { Cr2Slices.join(ShortArray(11), 6, 2, intArrayOf(2, 2, 2)) }
    }
}
