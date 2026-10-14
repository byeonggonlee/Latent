package com.byeonggon.cameramanager.enhance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import kotlin.math.max
import kotlin.math.sqrt

/** A point of a tone curve: the tone [x] is shown as the tone [y], both from 0 (black) to 1 (white). */
data class CurvePoint(val x: Float, val y: Float)

/**
 * A tone curve drawn smoothly through [points], which run from the darkest tone to the brightest.
 * Tones darker than the first point or brighter than the last are shown like those points.
 */
data class ToneCurve(val points: List<CurvePoint> = STRAIGHT) {
    val isStraight: Boolean get() = points == STRAIGHT

    /**
     * The curve's value for every tone from 0 to 1, in [size] even steps. Between its points the curve
     * never swings past them: where the points rise, so does the curve.
     */
    fun table(size: Int): FloatArray {
        val count = points.size
        if (count < 2) return FloatArray(size) { points.firstOrNull()?.y ?: it / (size - 1f) }
        val rise = FloatArray(count - 1) { (points[it + 1].y - points[it].y) / max(points[it + 1].x - points[it].x, SMALLEST_STEP) }
        // Slopes at the points: the average of the rises either side, flat at a peak or a dip.
        val slope = FloatArray(count) { index ->
            when {
                index == 0 -> rise[0]
                index == count - 1 -> rise[count - 2]
                rise[index - 1] * rise[index] <= 0f -> 0f
                else -> (rise[index - 1] + rise[index]) / 2
            }
        }
        // Slopes too steep for the rise between two points would make the curve overshoot (Fritsch-Carlson).
        for (index in 0 until count - 1) {
            if (rise[index] == 0f) {
                slope[index] = 0f
                slope[index + 1] = 0f
                continue
            }
            val before = slope[index] / rise[index]
            val after = slope[index + 1] / rise[index]
            val steepness = before * before + after * after
            if (steepness > 9f) {
                val limit = 3f / sqrt(steepness)
                slope[index] = limit * before * rise[index]
                slope[index + 1] = limit * after * rise[index]
            }
        }
        var segment = 0
        return FloatArray(size) { step ->
            val x = step / (size - 1f)
            while (segment < count - 2 && x > points[segment + 1].x) segment++
            val from = points[segment]
            val to = points[segment + 1]
            val value = when {
                x <= points.first().x -> points.first().y
                x >= points.last().x -> points.last().y
                else -> {
                    val width = max(to.x - from.x, SMALLEST_STEP)
                    val t = (x - from.x) / width
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * from.y + (t3 - 2 * t2 + t) * width * slope[segment] +
                        (-2 * t3 + 3 * t2) * to.y + (t3 - t2) * width * slope[segment + 1]
                }
            }
            value.coerceIn(0f, 1f)
        }
    }

    /** The curve at a strength of [amount]: straight at 0, itself at 1. */
    fun mixedWithStraight(amount: Float): ToneCurve {
        val mixed = points.map { CurvePoint(it.x, (it.x + (it.y - it.x) * amount).coerceIn(0f, 1f)) }
        return if (mixed.all { it.x == it.y }) ToneCurve() else ToneCurve(mixed)
    }

    fun toJson(): JsonArray = JsonArray().apply {
        for (point in points) {
            add(
                JsonArray().apply {
                    add(point.x)
                    add(point.y)
                },
            )
        }
    }

    companion object {
        val STRAIGHT = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        private const val SMALLEST_STEP = 1e-4f

        /** Reads what [toJson] wrote, putting the points in order. Throws on anything else. */
        fun fromJson(stored: JsonElement): ToneCurve {
            val points = stored.asJsonArray
                .map { it.asJsonArray.let { pair -> CurvePoint(pair[0].asFloat.coerceIn(0f, 1f), pair[1].asFloat.coerceIn(0f, 1f)) } }
                .sortedBy { it.x }
            return if (points.size < 2) ToneCurve() else ToneCurve(points)
        }
    }
}

/** A curve for all three colours together, followed by one for each colour on its own. */
data class ToneCurves(
    val all: ToneCurve = ToneCurve(),
    val red: ToneCurve = ToneCurve(),
    val green: ToneCurve = ToneCurve(),
    val blue: ToneCurve = ToneCurve(),
) {
    fun byName(): List<Pair<String, ToneCurve>> = listOf("all" to all, "red" to red, "green" to green, "blue" to blue)

    fun mixedWithStraight(amount: Float) = ToneCurves(
        all.mixedWithStraight(amount),
        red.mixedWithStraight(amount),
        green.mixedWithStraight(amount),
        blue.mixedWithStraight(amount),
    )

    companion object {
        /** The curves that [curve] gives for the names used by [byName]. */
        fun named(curve: (String) -> ToneCurve) = ToneCurves(curve("all"), curve("red"), curve("green"), curve("blue"))
    }
}
