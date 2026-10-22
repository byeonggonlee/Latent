package com.byeonggon.latent.location

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.time.DateTimeException
import java.time.OffsetDateTime

data class TimelinePoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
)

/**
 * Streams location points out of the Timeline.json that Android exports from
 * Settings > Location > Location services > Timeline > Export Timeline data.
 *
 * The file can be tens of megabytes, so it is never loaded as a whole.
 */
object TimelineParser {
    // A visit only has a start and an end; filling it in keeps photos taken mid-visit within matching range.
    private const val VISIT_STEP_MS = 10 * 60_000L

    /**
     * The longest visit that is filled in: a stay of a week at most, as a Timeline records them. A longer one, or one that
     * ends before it starts, is a damaged record; filled in, a single one could add millions of points.
     */
    internal const val LONGEST_VISIT_MS = 7 * 24 * 60 * 60_000L

    /** Streams the points of the file in [reader]; [onSkippedVisit] is called for each visit too long to be filled in. */
    fun parse(reader: Reader, onSkippedVisit: () -> Unit = {}): Sequence<TimelinePoint> = sequence {
        val json = JsonReader(reader)
        if (json.peek() != JsonToken.BEGIN_OBJECT) return@sequence
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "semanticSegments" -> {
                    json.beginArray()
                    while (json.hasNext()) readSegment(json, onSkippedVisit)
                    json.endArray()
                }
                "rawSignals" -> {
                    json.beginArray()
                    while (json.hasNext()) readRawSignal(json)?.let { yield(it) }
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
    }

    private suspend fun SequenceScope<TimelinePoint>.readSegment(json: JsonReader, onSkippedVisit: () -> Unit) {
        var start: Long? = null
        var end: Long? = null
        var visit: Pair<Double, Double>? = null
        var activityStart: Pair<Double, Double>? = null
        var activityEnd: Pair<Double, Double>? = null

        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "startTime" -> start = parseTime(json.nextStringOrNull())
                "endTime" -> end = parseTime(json.nextStringOrNull())
                "timelinePath" -> {
                    json.beginArray()
                    while (json.hasNext()) readPathPoint(json)?.let { yield(it) }
                    json.endArray()
                }
                "visit" -> visit = readNestedLatLng(json, "topCandidate", "placeLocation", "latLng")
                "activity" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "start" -> activityStart = readNestedLatLng(json, "latLng")
                            "end" -> activityEnd = readNestedLatLng(json, "latLng")
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()

        val from = start ?: return
        val to = end ?: return
        visit?.let { (latitude, longitude) ->
            if (to < from || to - from > LONGEST_VISIT_MS) {
                onSkippedVisit()
                return@let
            }
            var time = from
            while (time < to) {
                yield(TimelinePoint(time, latitude, longitude))
                time += VISIT_STEP_MS
            }
            yield(TimelinePoint(to, latitude, longitude))
        }
        activityStart?.let { yield(TimelinePoint(from, it.first, it.second)) }
        activityEnd?.let { yield(TimelinePoint(to, it.first, it.second)) }
    }

    private fun readPathPoint(json: JsonReader): TimelinePoint? {
        var latLng: Pair<Double, Double>? = null
        var time: Long? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "point" -> latLng = parseLatLng(json.nextStringOrNull())
                "time" -> time = parseTime(json.nextStringOrNull())
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (latLng != null && time != null) TimelinePoint(time, latLng.first, latLng.second) else null
    }

    private fun readRawSignal(json: JsonReader): TimelinePoint? {
        var point: TimelinePoint? = null
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == "position") point = readPosition(json) else json.skipValue()
        }
        json.endObject()
        return point
    }

    private fun readPosition(json: JsonReader): TimelinePoint? {
        var latLng: Pair<Double, Double>? = null
        var time: Long? = null
        var altitude: Double? = null
        var accuracy: Float? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "LatLng", "latLng" -> latLng = parseLatLng(json.nextStringOrNull())
                "timestamp" -> time = parseTime(json.nextStringOrNull())
                "altitudeMeters" -> altitude = json.nextNumberOrNull()
                "accuracyMeters" -> accuracy = json.nextNumberOrNull()?.toFloat()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (latLng != null && time != null) {
            TimelinePoint(time, latLng.first, latLng.second, altitude, accuracy)
        } else {
            null
        }
    }

    /** Follows [path] through nested objects and parses the string found at its end. */
    private fun readNestedLatLng(json: JsonReader, vararg path: String): Pair<Double, Double>? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return null
        }
        var result: Pair<Double, Double>? = null
        json.beginObject()
        while (json.hasNext()) {
            val name = json.nextName()
            when {
                name != path.first() -> json.skipValue()
                path.size == 1 -> result = parseLatLng(json.nextStringOrNull())
                else -> result = readNestedLatLng(json, *path.copyOfRange(1, path.size))
            }
        }
        json.endObject()
        return result
    }

    /** Parses coordinates written as "37.5665°, 126.9780°". */
    internal fun parseLatLng(text: String?): Pair<Double, Double>? {
        val parts = text?.replace("°", "")?.split(',') ?: return null
        if (parts.size != 2) return null
        val latitude = parts[0].trim().toDoubleOrNull() ?: return null
        val longitude = parts[1].trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    /** A time as milliseconds since 1970, or null when it cannot be read or lies beyond what that can count. */
    private fun parseTime(text: String?): Long? = try {
        text?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (e: DateTimeException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

    private fun JsonReader.nextStringOrNull(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.nextNumberOrNull(): Double? = when (peek()) {
        JsonToken.NUMBER -> nextDouble()
        JsonToken.STRING -> nextString().toDoubleOrNull()
        else -> {
            skipValue()
            null
        }
    }
}
