package com.byeonggon.latent.enhance

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The colour of the light a picture seems lit by, judged three ways from [LinearImage]'s linear levels: the average
 * of the picture (gray-world), the average of its edges (gray-edge) and its brightest parts (white-patch). Each is
 * misled by a different picture: a scene of one colour, a sky at dusk, a lamp-lit room. Measured on the same photos in
 * `docs/auto-colour-evaluation.md`, none is right often enough to be trusted alone, so a raw, whose colours are
 * already the camera's white balance, is only nudged where all three agree ([nudge]).
 */
internal object WhiteEstimate {
    /** Beyond this share of white a channel may have been clipped; below the other, the colour is mostly noise. */
    private const val CLIPPED = 0.97
    private const val DARK = 0.01

    /** The Minkowski power of the edges: the stronger edges count for more, as in van de Weijer, Gevers and Gijsenij. */
    private const val EDGE_POWER = 6.0

    /** The brightest share of the picture taken for its white. */
    private const val WHITE_SHARE = 0.05

    /** Of the smallest correction the three agree on, the part that is taken, and the most taken. */
    private const val NUDGE_SHARE = 0.5f
    private const val MOST_TEMPERATURE = 0.1f
    private const val MOST_TINT = 0.05f

    /** Red, green and blue of the light as each way judges it, in proportion only. */
    class Estimates(val grayWorld: DoubleArray, val grayEdge: DoubleArray, val whitePatch: DoubleArray) {
        val all: List<DoubleArray> get() = listOf(grayWorld, grayEdge, whitePatch)
    }

    /** The three estimates; null when too little of the picture is neither clipped nor dark to judge it by. */
    fun of(image: LinearImage): Estimates? {
        val width = image.width
        val height = image.height
        val count = width * height
        val scale = 1.0 / LinearImage.MAX_LEVEL
        val red = DoubleArray(count) { image.red[it] * scale }
        val green = DoubleArray(count) { image.green[it] * scale }
        val blue = DoubleArray(count) { image.blue[it] * scale }
        val usable = BooleanArray(count) {
            val most = maxOf(red[it], green[it], blue[it])
            most > DARK && most < CLIPPED
        }
        val used = usable.count { it }
        if (used < count / 20) return null

        val world = DoubleArray(3)
        for (index in 0 until count) {
            if (!usable[index]) continue
            world[0] += red[index]
            world[1] += green[index]
            world[2] += blue[index]
        }

        val edges = DoubleArray(3)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!usable[index] || !usable[index - 1] || !usable[index + 1] || !usable[index - width] || !usable[index + width]) continue
                for ((channel, values) in listOf(red, green, blue).withIndex()) {
                    val across = (values[index + 1] - values[index - 1]) / 2
                    val down = (values[index + width] - values[index - width]) / 2
                    edges[channel] += sqrt(across * across + down * down).pow(EDGE_POWER)
                }
            }
        }
        for (channel in 0..2) edges[channel] = edges[channel].pow(1 / EDGE_POWER)

        val brightest = (0 until count).filter { usable[it] }.sortedByDescending { red[it] + green[it] + blue[it] }
        val white = DoubleArray(3)
        for (index in brightest.take(maxOf(1, (brightest.size * WHITE_SHARE).toInt()))) {
            white[0] += red[index]
            white[1] += green[index]
            white[2] += blue[index]
        }
        return Estimates(world, edges, white)
    }

    /** The temperature that takes [light] to neutral, in the slider's units (see [ToneRenderer.TEMPERATURE_STOPS]). */
    fun temperatureFor(light: DoubleArray): Float = (ln(light[2] / light[0]) / ln(2.0) / (2 * ToneRenderer.TEMPERATURE_STOPS)).toFloat()

    /** The tint that then takes [light] to neutral: green against red and blue, which the temperature has made equal. */
    fun tintFor(light: DoubleArray): Float = (ln(light[1] / sqrt(light[0] * light[2])) / ln(2.0) / ToneRenderer.TINT_STOPS).toFloat()

    /**
     * The temperature and tint that move a raw, as the camera balanced it, a little towards neutral: on each, only
     * where gray-world, gray-edge and white-patch all ask for the same way, by half the least of them and at most
     * [MOST_TEMPERATURE] and [MOST_TINT]. Where they disagree, the picture's colour is left as the camera saw it.
     */
    fun nudge(image: LinearImage): Pair<Float, Float> {
        val estimates = of(image)?.all?.takeIf { lights -> lights.all { light -> light.all { it > 0.0 } } } ?: return 0f to 0f
        return agreed(estimates.map(::temperatureFor), MOST_TEMPERATURE) to agreed(estimates.map(::tintFor), MOST_TINT)
    }

    private fun agreed(asked: List<Float>, most: Float): Float {
        val way = sign(asked.first())
        if (way == 0f || asked.any { sign(it) != way }) return 0f
        return way * min(asked.minOf { abs(it) } * NUDGE_SHARE, most)
    }
}
