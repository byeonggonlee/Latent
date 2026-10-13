package com.byeonggon.cameramanager.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMatcherTest {
    private val minute = 60_000L
    private val maxGap = 15 * minute

    @Test
    fun interpolatesBetweenSurroundingFixes() {
        val before = TrackFix(0, 37.0, 127.0, 10.0)
        val after = TrackFix(10 * minute, 37.1, 127.2, 30.0)

        val point = TrackMatcher.locate(before, after, 5 * minute, maxGap)!!

        assertEquals(37.05, point.latitude, 1e-9)
        assertEquals(127.1, point.longitude, 1e-9)
        assertEquals(20.0, point.altitude!!, 1e-9)
    }

    @Test
    fun usesNearestFixWhenTheSurroundingFixesAreTooFarApart() {
        val before = TrackFix(0, 37.0, 127.0)
        val after = TrackFix(60 * minute, 38.0, 128.0)

        val point = TrackMatcher.locate(before, after, 58 * minute, maxGap)!!

        assertEquals(38.0, point.latitude, 1e-9)
        assertEquals(128.0, point.longitude, 1e-9)
    }

    @Test
    fun returnsNullWhenNoFixIsCloseEnough() {
        val before = TrackFix(0, 37.0, 127.0)
        val after = TrackFix(120 * minute, 38.0, 128.0)

        assertNull(TrackMatcher.locate(before, after, 60 * minute, maxGap))
        assertNull(TrackMatcher.locate(null, null, 60 * minute, maxGap))
    }

    @Test
    fun usesASingleFixOnOneSideOnlyWithinTheGap() {
        val before = TrackFix(0, 37.0, 127.0)

        assertEquals(GeoPoint(37.0, 127.0), TrackMatcher.locate(before, null, 10 * minute, maxGap))
        assertNull(TrackMatcher.locate(before, null, 16 * minute, maxGap))
    }
}
