package com.byeonggon.cameramanager.enhance

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal const val LUMA_RED = 0.2126f
internal const val LUMA_GREEN = 0.7152f
internal const val LUMA_BLUE = 0.0722f

/**
 * A [ColorMixer] worked out for every hue, so that a pixel only has to look its own hue up. Hues
 * between two of the mixer's colours get a share of what is set for each.
 */
internal class MixerTable(mixer: ColorMixer) {
    /** How far the hue moves, in sixths of the colour wheel. */
    private val hueShift = FloatArray(STEPS + 1)
    private val saturation = FloatArray(STEPS + 1)
    private val luminance = FloatArray(STEPS + 1)

    init {
        val bands = MixerBand.entries
        for (step in 0..STEPS) {
            val hue = step * 360f / STEPS
            // The mixer colour at or before this hue and the one after it, going round the wheel.
            val before = bands.indices.last { bands[it].hue <= hue }
            val after = (before + 1) % bands.size
            val gap = (bands[after].hue - bands[before].hue).mod(360f)
            val share = (hue - bands[before].hue) / gap
            fun mixed(values: List<Float>) = values[before] + (values[after] - values[before]) * share
            hueShift[step] = mixed(mixer.hue) * HUE_REACH / 60f
            saturation[step] = 1f + mixed(mixer.saturation)
            luminance[step] = mixed(mixer.luminance) * LUMINANCE_REACH
        }
    }

    /** Changes the colour in [rgb], three display values, the way the mixer says for its hue. */
    fun mix(rgb: FloatArray) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val most = max(r, max(g, b))
        val chroma = most - min(r, min(g, b))
        // Grays have no hue to go by.
        if (chroma <= 0f || most <= 0f) return
        val hue = hueOf(r, g, b, most, chroma)
        val step = (hue * (STEPS / 6f)).toInt().coerceIn(0, STEPS)
        val vividness = chroma / most
        val light = lightOf(r, g, b)
        setColor(rgb, (hue + hueShift[step]).mod(6f), (vividness * saturation[step]).coerceIn(0f, 1f), most)
        // The colour keeps its brightness, then changes it as asked: more for vivid colours, as grays have no colour to tell them by.
        val mixedLight = lightOf(rgb[0], rgb[1], rgb[2])
        if (mixedLight <= 0f) return
        val scale = kotlin.math.sqrt(light / mixedLight) * max(0f, 1f + luminance[step] * vividness)
        rgb[0] *= scale
        rgb[1] *= scale
        rgb[2] *= scale
    }

    /** How bright the colour comes out in black and white. */
    fun gray(r: Float, g: Float, b: Float): Float {
        val luma = LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b
        val most = max(r, max(g, b))
        val chroma = most - min(r, min(g, b))
        if (chroma <= 0f || most <= 0f) return luma
        val step = (hueOf(r, g, b, most, chroma) * (STEPS / 6f)).toInt().coerceIn(0, STEPS)
        return luma * (1f + luminance[step] * chroma / most)
    }

    companion object {
        private const val STEPS = 360

        /** Degrees a colour moves around the wheel at a full hue setting. */
        private const val HUE_REACH = 30f
        private const val LUMINANCE_REACH = 0.6f

        /**
         * How much light a colour gives off, near enough: display values squared stand in for linear
         * light. Brightness as the eye sees it follows this more closely than it follows the display
         * values themselves, which matters when a colour changes its hue.
         */
        private fun lightOf(r: Float, g: Float, b: Float) = LUMA_RED * r * r + LUMA_GREEN * g * g + LUMA_BLUE * b * b

        /** Where a colour is on the colour wheel, in sixths of the way round from red: 0 to 6. */
        private fun hueOf(r: Float, g: Float, b: Float, most: Float, chroma: Float): Float {
            val hue = when (most) {
                r -> (g - b) / chroma
                g -> 2f + (b - r) / chroma
                else -> 4f + (r - g) / chroma
            }
            return if (hue < 0f) hue + 6f else hue
        }

        /** Writes the colour with the given hue (0 to 6), vividness and brightest channel to [rgb]. */
        fun setColor(rgb: FloatArray, hue: Float, vividness: Float, most: Float) {
            val sector = hue.toInt().coerceIn(0, 5)
            val within = hue - sector
            val least = most * (1f - vividness)
            val falling = most * (1f - vividness * within)
            val rising = most * (1f - vividness * (1f - within))
            when (sector) {
                0 -> set(rgb, most, rising, least)
                1 -> set(rgb, falling, most, least)
                2 -> set(rgb, least, most, rising)
                3 -> set(rgb, least, falling, most)
                4 -> set(rgb, rising, least, most)
                else -> set(rgb, most, least, falling)
            }
        }

        private fun set(rgb: FloatArray, r: Float, g: Float, b: Float) {
            rgb[0] = r
            rgb[1] = g
            rgb[2] = b
        }
    }
}

/**
 * A [ColorGrading] worked out for every brightness: how much red, green and blue to add to a pixel
 * that bright. What is added has no brightness of its own, so the picture's tones stay as they are.
 */
internal class GradingTable(grading: ColorGrading) {
    val red = FloatArray(STEPS + 1)
    val green = FloatArray(STEPS + 1)
    val blue = FloatArray(STEPS + 1)

    init {
        val shadows = colorOf(grading.shadows)
        val midtones = colorOf(grading.midtones)
        val highlights = colorOf(grading.highlights)
        // Above zero, the balance makes more of the picture count as bright.
        val power = 2f.pow(-grading.balance)
        for (step in 0..STEPS) {
            val level = (step / STEPS.toFloat()).pow(power)
            val dark = (1f - level) * (1f - level)
            val bright = level * level
            val middle = 1f - dark - bright
            red[step] = dark * shadows[0] + middle * midtones[0] + bright * highlights[0]
            green[step] = dark * shadows[1] + middle * midtones[1] + bright * highlights[1]
            blue[step] = dark * shadows[2] + middle * midtones[2] + bright * highlights[2]
        }
    }

    fun step(luma: Float): Int = (luma * STEPS).toInt().coerceIn(0, STEPS)

    /** The tint as amounts of red, green and blue that add up to no brightness at all. */
    private fun colorOf(tint: Tint): FloatArray {
        val rgb = FloatArray(3)
        MixerTable.setColor(rgb, (tint.hue / 60f).mod(6f), vividness = 1f, most = 1f)
        val luma = LUMA_RED * rgb[0] + LUMA_GREEN * rgb[1] + LUMA_BLUE * rgb[2]
        return FloatArray(3) { (rgb[it] - luma) * tint.amount * REACH }
    }

    private companion object {
        const val STEPS = 256
        const val REACH = 0.3f
    }
}

/**
 * Darker or lighter corners: the factor for every distance from the picture's centre, where the
 * middles of the sides are at a distance of one. Looked up by the distance squared.
 */
internal class Vignette(amount: Float) {
    private val factor = FloatArray(STEPS + 1) { step ->
        val distance = kotlin.math.sqrt(step * MAX_SQUARED_DISTANCE / STEPS)
        val reached = ((distance - ONSET) / (FULL - ONSET)).coerceIn(0f, 1f)
        val falloff = reached * reached * (3f - 2f * reached)
        if (amount < 0f) 1f + amount * DARKEST * falloff else 1f + amount * LIGHTEST * falloff
    }

    fun factorAt(squaredDistance: Float): Float =
        factor[(squaredDistance * (STEPS / MAX_SQUARED_DISTANCE)).toInt().coerceIn(0, STEPS)]

    private companion object {
        const val STEPS = 512
        const val MAX_SQUARED_DISTANCE = 2f // the corners
        const val ONSET = 0.35f
        const val FULL = 1.35f
        const val DARKEST = 0.75f
        const val LIGHTEST = 0.6f
    }
}

/**
 * Film grain: fine random flecks of lighter and darker. The flecks have a fixed size relative to
 * the whole picture, so a small preview shows the same grain as the full picture seen from afar.
 */
internal class Grain(private val amount: Float, frameWidth: Int, frameHeight: Int) {
    private val pixelsPerFleck = max(frameWidth, frameHeight) / FLECKS_ALONG_THE_LONG_SIDE

    // A pixel that covers several flecks shows their average, which is fainter than a single fleck.
    private val strength = amount * STRENGTH * min(1f, pixelsPerFleck)

    /** How much lighter or darker the pixel at ([x], [y]) of the whole picture becomes, given its brightness. */
    fun at(x: Int, y: Int, luma: Float): Float {
        val fleck = if (pixelsPerFleck <= 1f) {
            fleck((x / pixelsPerFleck).toInt(), (y / pixelsPerFleck).toInt())
        } else {
            val across = x / pixelsPerFleck
            val down = y / pixelsPerFleck
            val left = across.toInt()
            val top = down.toInt()
            val upper = fleck(left, top) + (fleck(left + 1, top) - fleck(left, top)) * (across - left)
            val lower = fleck(left, top + 1) + (fleck(left + 1, top + 1) - fleck(left, top + 1)) * (across - left)
            upper + (lower - upper) * (down - top)
        }
        // Grain shows most in the middle tones and fades out towards black and white.
        return fleck * strength * (FAINTEST + (1f - FAINTEST) * 4f * luma * abs(1f - luma)).coerceAtMost(1f)
    }

    /** A value from -1 to 1 that is always the same for the same fleck, and unrelated to its neighbours'. */
    private fun fleck(x: Int, y: Int): Float {
        var hash = x * 374761393 + y * 668265263
        hash = (hash xor (hash ushr 13)) * 1274126177
        hash = hash xor (hash ushr 16)
        return (hash and 0xFFFF) / 32767.5f - 1f
    }

    private companion object {
        const val FLECKS_ALONG_THE_LONG_SIDE = 2400f
        const val STRENGTH = 0.16f
        const val FAINTEST = 0.3f
    }
}
