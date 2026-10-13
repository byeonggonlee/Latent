package com.byeonggon.cameramanager.location

import kotlin.math.abs

data class GeoPoint(val latitude: Double, val longitude: Double, val altitude: Double? = null)

data class TrackFix(val timestamp: Long, val latitude: Double, val longitude: Double, val altitude: Double? = null) {
    fun toGeoPoint() = GeoPoint(latitude, longitude, altitude)
}

object TrackMatcher {
    /**
     * Estimates where a photo taken at [timestamp] was shot, given the recorded fixes right [before] and
     * [after] it. Interpolates when the two fixes are close enough together, otherwise falls back to the
     * nearest fix as long as it is within [maxGapMs].
     */
    fun locate(before: TrackFix?, after: TrackFix?, timestamp: Long, maxGapMs: Long): GeoPoint? {
        if (before != null && after != null) {
            val span = after.timestamp - before.timestamp
            if (span <= 0L) return before.toGeoPoint()
            if (span <= 2 * maxGapMs) {
                val fraction = (timestamp - before.timestamp).toDouble() / span
                return GeoPoint(
                    latitude = lerp(before.latitude, after.latitude, fraction),
                    longitude = lerp(before.longitude, after.longitude, fraction),
                    altitude = interpolateAltitude(before.altitude, after.altitude, fraction),
                )
            }
        }
        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.timestamp - timestamp) } ?: return null
        return if (abs(nearest.timestamp - timestamp) <= maxGapMs) nearest.toGeoPoint() else null
    }

    private fun lerp(start: Double, end: Double, fraction: Double) = start + (end - start) * fraction

    private fun interpolateAltitude(start: Double?, end: Double?, fraction: Double): Double? = when {
        start != null && end != null -> lerp(start, end, fraction)
        fraction < 0.5 -> start ?: end
        else -> end ?: start
    }
}
