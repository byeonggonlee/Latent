package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CameraCurveTest {
    @Test
    fun runsFromBlackToWhiteGettingBrighterAllTheWay() {
        assertEquals(0f, CameraCurve.display(0f), 0f)
        assertEquals(1f, CameraCurve.display(1f), 1e-6f)
        val curve = CameraCurve.displayOfLevel
        assertTrue((1 until curve.size).all { curve[it] >= curve[it - 1] })
    }

    @Test
    fun liftsTheMidtones() {
        // A tenth of the light the sensor can take is shown brighter than middle gray.
        assertTrue(CameraCurve.display(0.1f) > 0.5f)
    }

    @Test
    fun findsTheLightBehindEveryDisplayValue() {
        for (value in 0..255) {
            val level = CameraCurve.levelOfDisplay[value].toInt()
            assertEquals(value, (CameraCurve.displayOfLevel[level] * 255f).roundToInt())
        }
    }
}
