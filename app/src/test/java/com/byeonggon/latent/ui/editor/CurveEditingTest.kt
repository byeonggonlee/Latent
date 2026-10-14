package com.byeonggon.latent.ui.editor

import com.byeonggon.latent.enhance.CurvePoint
import com.byeonggon.latent.enhance.ToneCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CurveEditingTest {
    private val curve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f)))

    @Test
    fun takesHoldOfThePointUnderTheFinger() {
        assertEquals(1, CurveEditing.nearest(curve, 0.52f, 0.57f, reach = 0.1f))
        assertEquals(2, CurveEditing.nearest(curve, 0.95f, 0.98f, reach = 0.1f))
        assertNull(CurveEditing.nearest(curve, 0.25f, 0.3f, reach = 0.1f))
        // Above the point by more than the reach, even though right over it.
        assertNull(CurveEditing.nearest(curve, 0.5f, 0.9f, reach = 0.1f))
    }

    @Test
    fun movesAPointNoFurtherThanItsNeighboursAndTheEdges() {
        assertEquals(CurvePoint(0.3f, 0.2f), CurveEditing.moved(curve, 1, 0.3f, 0.2f).points[1])
        assertEquals(CurvePoint(1f - CurveEditing.GAP, 1f), CurveEditing.moved(curve, 1, 2f, 3f).points[1])
        assertEquals(CurvePoint(CurveEditing.GAP, 0f), CurveEditing.moved(curve, 1, -1f, -1f).points[1])
        // The end points can come inwards, which fades the blacks or clips the whites.
        assertEquals(CurvePoint(0f, 0.15f), CurveEditing.moved(curve, 0, -0.2f, 0.15f).points[0])
        assertEquals(CurvePoint(0.8f, 1f), CurveEditing.moved(curve, 2, 0.8f, 1.4f).points[2])
    }

    @Test
    fun putsANewPointInItsPlaceAmongTheOthers() {
        val (added, index) = CurveEditing.added(curve, 0.75f, 0.9f)!!

        assertEquals(2, index)
        assertEquals(listOf(0f, 0.5f, 0.75f, 1f), added.points.map { it.x })
        assertNull(CurveEditing.added(curve, 0.51f, 0.9f))
        val full = ToneCurve(List(CurveEditing.MOST_POINTS) { CurvePoint(it / 9f, it / 9f) })
        assertNull(CurveEditing.added(full, 0.05f, 0.05f))
    }

    @Test
    fun takesAPointOffButLeavesTwo() {
        val straightened = CurveEditing.removed(curve, 1)

        assertEquals(ToneCurve(), straightened)
        assertSame(straightened, CurveEditing.removed(straightened, 0))
    }
}
