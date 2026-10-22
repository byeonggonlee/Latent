package com.byeonggon.latent.location

import com.byeonggon.latent.fuzz
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringReader

/**
 * Damaged and odd Timeline exports: the parser hands back points or fails as the import expects it to (an
 * [IOException] or an [IllegalStateException], which the screen turns into a message), and every point it does hand
 * back is a place on Earth at a time.
 */
class TimelineParserFuzzTest {
    private val seed = """
        {
          "semanticSegments": [
            {
              "startTime": "2024-05-01T09:00:00.000+09:00",
              "endTime": "2024-05-01T10:30:00.000+09:00",
              "timelinePath": [
                {"point": "37.5665°, 126.9780°", "time": "2024-05-01T09:05:00.000+09:00"},
                {"point": "37.5700°, 126.9800°", "time": "2024-05-01T09:10:00.000+09:00"}
              ]
            },
            {
              "startTime": "2024-05-01T11:00:00.000+09:00",
              "endTime": "2024-05-01T12:00:00.000+09:00",
              "visit": {"hierarchyLevel": 0, "topCandidate": {"placeId": "x", "placeLocation": {"latLng": "35.1796°, 129.0756°"}}}
            },
            {
              "startTime": "2024-05-01T12:00:00.000+09:00",
              "endTime": "2024-05-01T12:40:00.000+09:00",
              "activity": {"start": {"latLng": "35.1796°, 129.0756°"}, "end": {"latLng": "35.1000°, 129.0000°"}, "distanceMeters": 1200.5}
            }
          ],
          "rawSignals": [
            {"position": {"LatLng": "37.4979°, 127.0276°", "accuracyMeters": 12, "altitudeMeters": 41.2, "source": "WIFI", "timestamp": "2024-05-02T08:00:00.000+09:00"}},
            {"wifiScan": {"deliveryTime": "2024-05-02T08:00:01.000+09:00"}}
          ],
          "userLocationProfile": {"frequentPlaces": []}
        }
    """.trimIndent()

    @Test
    fun theSeedIsATimeline() {
        assertTrue(TimelineParser.parse(StringReader(seed)).count() > 5)
    }

    @Test
    fun damagedExportsGivePlacesOrTheFailureTheImportReports() = fuzz(seed = 1, cases = 3_000) { mutator ->
        val text = mutator.json(seed)
        val points = try {
            TimelineParser.parse(StringReader(text)).toList()
        } catch (e: IOException) {
            return@fuzz
        } catch (e: IllegalStateException) {
            return@fuzz
        }
        for (point in points) assertTrue("$point", point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
        // A visit is filled in at ten-minute steps for a week at most; nothing grows past what the text could say.
        assertTrue("${points.size} points from ${text.length} characters", points.size <= 1_100 * (1 + text.length / 40))
    }

    @Test
    fun bytesOfAnyKindGivePlacesOrTheFailureTheImportReports() = fuzz(seed = 2, cases = 3_000) { mutator ->
        val text = String(mutator.bytes(seed.toByteArray()), Charsets.UTF_8)
        try {
            TimelineParser.parse(StringReader(text)).toList()
        } catch (e: IOException) {
        } catch (e: IllegalStateException) {
        }
    }
}
