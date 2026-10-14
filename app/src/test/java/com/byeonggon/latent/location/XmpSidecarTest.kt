package com.byeonggon.latent.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpSidecarTest {
    @Test
    fun formatsCoordinatesAsDegreesAndDecimalMinutes() {
        assertEquals("37,33.990000N", XmpSidecar.coordinate(37.5665, 'N', 'S'))
        assertEquals("33,51.132000S", XmpSidecar.coordinate(-33.8522, 'N', 'S'))
        assertEquals("126,58.680000E", XmpSidecar.coordinate(126.978, 'E', 'W'))
    }

    @Test
    fun neverEmitsSixtyMinutes() {
        assertEquals("38,0.000000N", XmpSidecar.coordinate(37.99999999999, 'N', 'S'))
    }

    @Test
    fun includesAltitudeOnlyWhenKnown() {
        val withAltitude = XmpSidecar.build(GeoPoint(37.5665, 126.978, -12.34))
        assertTrue("""exif:GPSLatitude="37,33.990000N"""" in withAltitude)
        assertTrue("""exif:GPSLongitude="126,58.680000E"""" in withAltitude)
        assertTrue("""exif:GPSAltitudeRef="1"""" in withAltitude)
        assertTrue("""exif:GPSAltitude="123/10"/>""" in withAltitude)

        assertFalse("GPSAltitude" in XmpSidecar.build(GeoPoint(37.5665, 126.978)))
    }
}
