package com.byeonggon.latent.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureMathTest {
    @Test
    fun aDragAcrossTheWholeWidthGoesAsFarAsTheRangeReachesFromZero() {
        assertEquals(1f, valueAfterDrag(start = 0f, dragged = 1000f, width = 1000f, range = -1f..1f), 1e-6f)
        assertEquals(-0.5f, valueAfterDrag(start = 0f, dragged = -500f, width = 1000f, range = -1f..1f), 1e-6f)
        // The exposure range is wider, so the same drag goes further.
        assertEquals(3f, valueAfterDrag(start = 0f, dragged = 1000f, width = 1000f, range = -3f..3f), 1e-6f)
        // A setting that only adds something reaches its top in one drag as well.
        assertEquals(0.5f, valueAfterDrag(start = 0f, dragged = 500f, width = 1000f, range = 0f..1f), 1e-6f)
    }

    @Test
    fun startsFromWhereTheSettingStood() {
        assertEquals(0.4f, valueAfterDrag(start = 0.2f, dragged = 200f, width = 1000f, range = -1f..1f), 1e-6f)
    }

    @Test
    fun staysWithinTheRange() {
        assertEquals(1f, valueAfterDrag(start = 0.9f, dragged = 5000f, width = 1000f, range = -1f..1f), 1e-6f)
        assertEquals(0f, valueAfterDrag(start = 0.1f, dragged = -5000f, width = 1000f, range = 0f..1f), 1e-6f)
        assertEquals(0.4f, valueAfterDrag(start = 0.4f, dragged = 100f, width = 0f, range = -1f..1f), 1e-6f)
    }

    @Test
    fun movesOneSettingForEachStepOfTravel() {
        assertEquals(0 to 30f, stepThrough(selected = 0, carry = 0f, travel = 30f, step = 100f, size = 6))
        // The leftover from one move adds to the next.
        assertEquals(1 to 10f, stepThrough(selected = 0, carry = 30f, travel = 80f, step = 100f, size = 6))
        assertEquals(3 to 0f, stepThrough(selected = 0, carry = 0f, travel = 300f, step = 100f, size = 6))
        assertEquals(1 to -20f, stepThrough(selected = 3, carry = 0f, travel = -220f, step = 100f, size = 6))
    }

    @Test
    fun stopsAtTheEndsOfTheList() {
        assertEquals(5 to 0f, stepThrough(selected = 4, carry = 0f, travel = 900f, step = 100f, size = 6))
        assertEquals(0 to 0f, stepThrough(selected = 1, carry = 0f, travel = -900f, step = 100f, size = 6))
    }
}
