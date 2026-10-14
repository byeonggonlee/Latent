package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {
    private fun curve(vararg points: Pair<Float, Float>) = ToneCurve(points.map { CurvePoint(it.first, it.second) })

    @Test
    fun aStraightCurveLeavesEveryToneWhereItIs() {
        val table = ToneCurve().table(257)

        assertTrue(ToneCurve().isStraight)
        for (step in 0..256) assertEquals(step / 256f, table[step], 1e-5f)
    }

    @Test
    fun passesThroughItsPointsAndStaysBetweenThem() {
        val lifted = curve(0f to 0f, 0.25f to 0.4f, 0.75f to 0.8f, 1f to 1f)

        val table = lifted.table(101)

        assertEquals(0.4f, table[25], 1e-4f)
        assertEquals(0.8f, table[75], 1e-4f)
        assertTrue((1..100).all { table[it] >= table[it - 1] })
    }

    @Test
    fun doesNotSwingPastAPointThatIsFollowedByAFlatStretch() {
        // Steep up to the second point and level after it: a plain smooth curve would overshoot here.
        val table = curve(0f to 0f, 0.1f to 0.9f, 0.5f to 0.9f, 1f to 1f).table(201)

        assertTrue(table.all { it in 0f..1f })
        assertTrue((1..200).all { table[it] >= table[it - 1] - 1e-6f })
        assertEquals(0.9f, table[60], 1e-3f)
    }

    @Test
    fun showsTonesBeyondItsEndsLikeItsEndPoints() {
        // A faded look: nothing is darker than a dark gray, and everything above a light gray is white.
        val table = curve(0f to 0.1f, 0.8f to 1f).table(11)

        assertEquals(0.1f, table[0], 1e-6f)
        assertEquals(1f, table[8], 1e-4f)
        assertEquals(1f, table[10], 1e-6f)
    }

    @Test
    fun fadesTowardsStraightAtLessThanFullStrength() {
        val faded = curve(0f to 0.2f, 1f to 1f)

        assertEquals(curve(0f to 0.1f, 1f to 1f), faded.mixedWithStraight(0.5f))
        assertTrue(faded.mixedWithStraight(0f).table(5).let { it[0] == 0f && it[4] == 1f })
        assertTrue(ToneCurve().mixedWithStraight(0.3f).isStraight)
    }
}
