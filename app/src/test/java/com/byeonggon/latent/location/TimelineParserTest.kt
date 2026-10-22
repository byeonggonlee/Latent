package com.byeonggon.latent.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.OffsetDateTime

class TimelineParserTest {
    private fun millis(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun parse(json: String) = TimelineParser.parse(StringReader(json)).toList()

    @Test
    fun readsPathPointsVisitsActivitiesAndRawSignals() {
        val points = parse(
            """
            {
              "semanticSegments": [
                {
                  "startTime": "2026-10-01T09:00:00.000+09:00",
                  "endTime": "2026-10-01T11:00:00.000+09:00",
                  "timelinePath": [
                    {"point": "37.5665°, 126.9780°", "time": "2026-10-01T09:05:00.000+09:00"},
                    {"point": "37.5700°, 126.9820°", "time": "2026-10-01T09:20:00.000+09:00"}
                  ]
                },
                {
                  "startTime": "2026-10-01T11:00:00.000+09:00",
                  "endTime": "2026-10-01T11:25:00.000+09:00",
                  "visit": {
                    "hierarchyLevel": 0,
                    "topCandidate": {
                      "placeId": "abc",
                      "placeLocation": {"latLng": "35.1796°, 129.0756°"}
                    }
                  }
                },
                {
                  "startTime": "2026-10-01T12:00:00.000+09:00",
                  "endTime": "2026-10-01T12:30:00.000+09:00",
                  "activity": {
                    "start": {"latLng": "35.0000°, 129.0000°"},
                    "end": {"latLng": "35.1000°, 129.1000°"},
                    "distanceMeters": 1234.5
                  }
                }
              ],
              "rawSignals": [
                {"wifiScan": {"deliveryTime": "2026-10-01T13:00:00.000+09:00"}},
                {
                  "position": {
                    "LatLng": "33.4996°, 126.5312°",
                    "accuracyMeters": 13,
                    "altitudeMeters": 50.5,
                    "source": "GPS",
                    "timestamp": "2026-10-01T13:00:01.000+09:00"
                  }
                }
              ],
              "userLocationProfile": {"frequentPlaces": []}
            }
            """.trimIndent(),
        )

        assertEquals(
            TimelinePoint(millis("2026-10-01T09:05:00+09:00"), 37.5665, 126.9780),
            points[0],
        )
        assertEquals(millis("2026-10-01T09:20:00+09:00"), points[1].timestamp)

        // A 25-minute visit is filled in every 10 minutes and closed at its end time.
        val visit = points.filter { it.latitude == 35.1796 }
        assertEquals(
            listOf("11:00", "11:10", "11:20", "11:25").map { millis("2026-10-01T$it:00+09:00") },
            visit.map { it.timestamp },
        )

        assertTrue(TimelinePoint(millis("2026-10-01T12:00:00+09:00"), 35.0, 129.0) in points)
        assertTrue(TimelinePoint(millis("2026-10-01T12:30:00+09:00"), 35.1, 129.1) in points)

        val raw = points.last()
        assertEquals(TimelinePoint(millis("2026-10-01T13:00:01+09:00"), 33.4996, 126.5312, 50.5, 13f), raw)
        assertEquals(9, points.size)
    }

    @Test
    fun ignoresFilesWithoutTimelineData() {
        assertEquals(emptyList<TimelinePoint>(), parse("""{"timelineObjects": [{"placeVisit": {}}]}"""))
        assertEquals(emptyList<TimelinePoint>(), parse("""[{"startTime": "2026-10-01T09:00:00Z"}]"""))
    }

    private fun visit(start: String, end: String) = """
        {"semanticSegments": [{
          "startTime": "$start", "endTime": "$end",
          "visit": {"topCandidate": {"placeLocation": {"latLng": "37.5°, 127.0°"}}}
        }]}
    """

    @Test
    fun skipsATimeBeyondWhatMillisecondsCanCount() {
        // Read as a date, but past the end of a Long's milliseconds: no point, and no crash of the import.
        val points = parse(
            """
            {"rawSignals": [
              {"position": {"timestamp": "+999999999-01-01T00:00:00Z", "LatLng": "37.5°, 127.0°"}},
              {"position": {"timestamp": "2026-10-01T09:00:00Z", "LatLng": "37.5°, 127.0°"}}
            ],
            "semanticSegments": [{
              "startTime": "-999999999-01-01T00:00:00Z", "endTime": "2026-10-01T10:00:00Z",
              "activity": {"start": {"latLng": "37.5°, 127.0°"}, "end": {"latLng": "37.6°, 127.1°"}}
            }]}
            """,
        )

        assertEquals(listOf(millis("2026-10-01T09:00:00Z")), points.map { it.timestamp })
    }

    @Test
    fun leavesOutAVisitTooLongToFillIn() {
        // 162 characters that would have filled in over 500 million points.
        var skipped = 0
        val points = TimelineParser.parse(StringReader(visit("0001-01-01T00:00:00Z", "9999-12-31T23:59:59Z"))) { skipped++ }.toList()

        assertEquals(emptyList<TimelinePoint>(), points)
        assertEquals(1, skipped)
    }

    @Test
    fun leavesOutAVisitThatEndsBeforeItBegins() {
        var skipped = 0
        val points = TimelineParser.parse(StringReader(visit("2026-10-02T09:00:00Z", "2026-10-01T09:00:00Z"))) { skipped++ }.toList()

        assertEquals(emptyList<TimelinePoint>(), points)
        assertEquals(1, skipped)
    }

    @Test
    fun fillsInAStayOfAFewDays() {
        var skipped = 0
        val points = TimelineParser.parse(StringReader(visit("2026-10-01T09:00:00Z", "2026-10-04T09:00:00Z"))) { skipped++ }.toList()

        // Every ten minutes for three days, and the end.
        assertEquals(3 * 24 * 6 + 1, points.size)
        assertEquals(millis("2026-10-04T09:00:00Z"), points.last().timestamp)
        assertEquals(0, skipped)
    }

    @Test
    fun rejectsMalformedCoordinates() {
        assertNull(TimelineParser.parseLatLng("not a coordinate"))
        assertNull(TimelineParser.parseLatLng("95.0°, 10.0°"))
        assertEquals(-33.5 to 151.25, TimelineParser.parseLatLng("-33.5°, 151.25°"))
    }
}
