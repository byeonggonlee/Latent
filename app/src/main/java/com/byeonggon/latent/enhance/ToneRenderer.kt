package com.byeonggon.latent.enhance

import java.util.WeakHashMap
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns linear pictures into finished sRGB pixels, with an edit applied on the way.
 *
 * Exposure and white balance act on the light itself, before the camera's tone curve, which is why a
 * darkened highlight keeps its gradation instead of turning flat gray. Everything else acts on the
 * picture as it is shown, in this order: tones (whites, blacks, contrast and the tone curves), haze,
 * the brightness of whole areas (shadows, highlights and clarity) and vividness. Up to here a part of
 * the picture can have settings of its own, which are blended in by its mask. Then come the colours
 * as a whole (the mixer, the grading and a LUT), and last what is laid over the picture (vignette and grain).
 *
 * What depends on the picture as a whole is judged by [overview], a small copy of it: auto enhance
 * reads its tones, and areas are told apart by how bright their surroundings are in it. The picture
 * that is rendered has to be framed the way the edit says, as that is how masks find their place.
 */
class ToneRenderer(internal val edit: PhotoEdit, overview: LinearImage) {
    internal val adjustments = edit.adjustments
    internal val overviewWidth = overview.width
    internal val everywhere: Stage
    internal val locals: List<Stage>
    internal val masks = edit.locals.map { it.mask }
    internal val localEdits = edit.locals
    internal val monochrome = edit.monochrome
    internal val mixer = if (edit.mixer != ColorMixer()) MixerTable(edit.mixer) else null
    internal val grading = if (edit.grading.copy(balance = 0f) != ColorGrading()) GradingTable(edit.grading) else null
    internal val vignette = if (adjustments.vignette != 0f) Vignette(adjustments.vignette) else null

    /** The edit's LUT, unless it is taken at no strength or its table is gone, and how much of it to take. */
    internal val lut: ColorLut? = edit.lut?.takeIf { it.intensity > 0f }?.let { LutStore.load(it.id) }
    internal val lutIntensity = edit.lut?.intensity ?: 0f

    init {
        val auto = if (edit.autoEnhance) autoLevelsOf(overview) else null
        // A part's own settings come on top of what applies everywhere.
        val settings = listOf(adjustments) + edit.locals.map { local ->
            Adjustment.entries.fold(adjustments) { all, each -> each.set(all, each.get(all) + each.get(local.adjustments)) }
        }
        // Each stage takes tens of milliseconds to set up, and they are set up again for every move of a slider.
        val stages = arrayOfNulls<Stage>(settings.size)
        IntStream.range(0, settings.size).parallel().forEach { index ->
            stages[index] = Stage(settings[index], edit.curves, auto, overview, monochrome)
        }
        everywhere = stages[0]!!
        locals = stages.drop(1).map { it!! }
    }

    /** What auto enhance makes of [overview]; the editor asks for the same picture again with every move of a slider. */
    private fun autoLevelsOf(overview: LinearImage): AutoLevels = synchronized(autoLevels) {
        autoLevels.getOrPut(overview) { AutoEnhance.analyze(asTheCameraShowsIt(overview)) }
    }

    /**
     * Renders [pixels], [width] by [rows] and as the camera made them, into themselves on the graphics
     * processor, rows from [top] of a picture [frameHeight] high: the same as rendering
     * [LinearImage.fromArgb] of them, without the processor making it. Returns false if it could not.
     */
    internal fun renderDisplayed(pixels: IntArray, width: Int, rows: Int, top: Int, frameHeight: Int): Boolean =
        useGraphicsProcessor && width * rows >= SMALLEST_FOR_GRAPHICS_PROCESSOR &&
            GpuTone.renderDisplayed(this, pixels, width, rows, top, frameHeight)

    /** Renders all of [source]. */
    fun render(source: LinearImage): IntArray =
        IntArray(source.width * source.height).also { render(source, it) }

    /**
     * Renders [source] into [output]. A large picture can be passed in strips to save memory, or only
     * a part of it rendered: [source] is then the part that starts [left] pixels across and [top]
     * rows down a picture of [frameWidth] by [frameHeight] pixels.
     */
    fun render(
        source: LinearImage,
        output: IntArray,
        top: Int = 0,
        frameHeight: Int = source.height,
        left: Int = 0,
        frameWidth: Int = source.width,
    ) {
        if (useGraphicsProcessor && source.width * source.height >= SMALLEST_FOR_GRAPHICS_PROCESSOR &&
            GpuTone.render(this, source, output, top, frameHeight, left, frameWidth)
        ) {
            return
        }
        renderCpu(source, output, top, frameHeight, left, frameWidth)
    }

    /** The exact working RGB before [localIndex], with the whole upright picture fitted in the mask view. */
    internal fun colourInput(source: LinearImage, localIndex: Int): ColourPickPlane {
        require(localIndex in localEdits.indices)
        val size = source.width * source.height
        val picture = ShownPicture(source.width, source.height, FloatArray(size), FloatArray(size), FloatArray(size))
        renderCpu(source, IntArray(0), 0, source.height, 0, source.width, localIndex, picture)
        return ColourPickPlane(picture)
    }

    private fun renderCpu(
        source: LinearImage,
        output: IntArray,
        top: Int,
        frameHeight: Int,
        left: Int,
        frameWidth: Int,
        beforeLocal: Int = locals.size,
        sampling: ShownPicture? = null,
    ) {
        val width = source.width
        // Everything the loop reads, held in locals: it runs for every pixel of the picture.
        val sourceRed = source.red
        val sourceGreen = source.green
        val sourceBlue = source.blue
        val everywhere = everywhere
        val locals = locals
        val placement = if (masks.isEmpty()) null else Placement(if (sampling == null) edit.framing else Framing(), frameWidth, frameHeight)
        val weights = placement?.let { masks.map { mask -> MaskWeights(mask, it.wholeWidth, it.wholeHeight) } }
        val monochrome = monochrome
        val mixer = mixer
        val grading = grading
        val lut = lut
        val lutIntensity = lutIntensity
        val vignette = vignette
        val grain = if (adjustments.grain > 0f) Grain(adjustments.grain, frameWidth, frameHeight) else null
        val columns = if (everywhere.usesSurroundings || locals.any { it.usesSurroundings }) {
            GuidedMap.columns(overviewWidth, frameWidth, left, width)
        } else {
            null
        }
        val centreX = frameWidth / 2f
        val centreY = frameHeight / 2f

        forRowBands(source.height) { from, until ->
            val rows = everywhere.rows(width)
            val localRows = locals.map { it.rows(width) }
            val rgb = FloatArray(3)
            val part = FloatArray(3)
            val looked = FloatArray(3)
            for (y in from until until) {
                val frameY = y + top
                val down = (frameY + 0.5f) / frameHeight
                if (columns != null) {
                    everywhere.fill(rows, columns, down)
                    for (index in locals.indices) locals[index].fill(localRows[index], columns, down)
                }
                val fromCentreY = (frameY + 0.5f - centreY) / centreY
                var index = y * width
                for (x in 0 until width) {
                    val redLevel = sourceRed[index].toInt()
                    val greenLevel = sourceGreen[index].toInt()
                    val blueLevel = sourceBlue[index].toInt()
                    everywhere.shade(redLevel, greenLevel, blueLevel, x, rows, rgb)

                    if (placement != null && weights != null) {
                        val wholeX = placement.xOf(left + x, frameY)
                        val wholeY = placement.yOf(left + x, frameY)
                        // Tone ranges go by the picture as it is shown before any part has settings of its own.
                        val luma = LUMA_RED * rgb[0] + LUMA_GREEN * rgb[1] + LUMA_BLUE * rgb[2]
                        for (local in 0 until beforeLocal) {
                            val weight = weights[local].at(wholeX, wholeY) * localEdits[local].toneWeight(luma) * localEdits[local].colourWeight(rgb[0], rgb[1], rgb[2])
                            if (weight <= 0f) continue
                            locals[local].shade(redLevel, greenLevel, blueLevel, x, localRows[local], part)
                            rgb[0] += (part[0] - rgb[0]) * weight
                            rgb[1] += (part[1] - rgb[1]) * weight
                            rgb[2] += (part[2] - rgb[2]) * weight
                        }
                    }

                    if (sampling != null) {
                        sampling.red[index] = rgb[0]
                        sampling.green[index] = rgb[1]
                        sampling.blue[index] = rgb[2]
                        index++
                        continue
                    }

                    if (monochrome) {
                        val gray = mixer?.gray(max(0f, rgb[0]), max(0f, rgb[1]), max(0f, rgb[2]))
                            ?: (LUMA_RED * rgb[0] + LUMA_GREEN * rgb[1] + LUMA_BLUE * rgb[2])
                        rgb[0] = gray
                        rgb[1] = gray
                        rgb[2] = gray
                    } else if (mixer != null) {
                        rgb[0] = max(0f, rgb[0])
                        rgb[1] = max(0f, rgb[1])
                        rgb[2] = max(0f, rgb[2])
                        mixer.mix(rgb)
                    }
                    var r = rgb[0]
                    var g = rgb[1]
                    var b = rgb[2]

                    if (grading != null) {
                        val step = grading.step(LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b)
                        r += grading.red[step]
                        g += grading.green[step]
                        b += grading.blue[step]
                    }

                    if (lut != null) {
                        lut.lookup(r, g, b, looked)
                        r += (looked[0] - r) * lutIntensity
                        g += (looked[1] - g) * lutIntensity
                        b += (looked[2] - b) * lutIntensity
                    }

                    if (vignette != null) {
                        val fromCentreX = (left + x + 0.5f - centreX) / centreX
                        val factor = vignette.factorAt(fromCentreX * fromCentreX + fromCentreY * fromCentreY)
                        r *= factor
                        g *= factor
                        b *= factor
                    }

                    if (grain != null) {
                        val fleck = grain.at(left + x, frameY, (LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b).coerceIn(0f, 1f))
                        r += fleck
                        g += fleck
                        b += fleck
                    }

                    output[index] = OPAQUE or (toByte(r) shl 16) or (toByte(g) shl 8) or toByte(b)
                    index++
                }
            }
        }
    }

    private fun toByte(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

    /** The picture with nothing but the camera's rendering, which is what auto enhance has to improve on. */
    private fun asTheCameraShowsIt(image: LinearImage): IntArray {
        val display = CameraCurve.displayOfLevel
        return IntArray(image.width * image.height) { index ->
            OPAQUE or
                (toByte(display[image.red[index].toInt()]) shl 16) or
                (toByte(display[image.green[index].toInt()]) shl 8) or
                toByte(display[image.blue[index].toInt()])
        }
    }

    /**
     * What one set of settings makes of a pixel's light, as far as a part of the picture can have
     * settings of its own: tones, haze, the brightness of areas, and vividness.
     */
    internal class Stage(
        private val adjustments: Adjustments,
        curves: ToneCurves,
        auto: AutoLevels?,
        overview: LinearImage,
        monochrome: Boolean,
    ) {
        internal val red: FloatArray
        internal val green: FloatArray
        internal val blue: FloatArray
        internal val saturation = (1f + adjustments.saturation) * (auto?.saturation ?: 1f)
        internal val vibrance = adjustments.vibrance

        // A picture without colours has none to make more vivid.
        internal val colorful = !monochrome && (saturation != 1f || vibrance != 0f)

        // Softening stops short of evening fine contrast out altogether; beyond that it would turn it over.
        internal val clarity = adjustments.clarity * (if (adjustments.clarity > 0f) CLARITY_AT_FULL else SOFTENING_AT_FULL)
        internal val haze: Haze?
        internal val clearness: GuidedMap?
        internal val evenHaze: Float
        internal val tones: ShadowsAndHighlights?
        internal val surroundings: GuidedMap?
        internal val nearSurroundings: GuidedMap?

        /** Whether [fill] has anything to do. */
        val usesSurroundings: Boolean

        init {
            val balance = whiteBalance(adjustments.temperature, adjustments.tint)
            val exposure = 2f.pow(adjustments.exposure)
            val tone = toneTable(curves.all)
            red = curve(balance[0] * exposure, auto?.let { it::red }, tone, curves.red)
            // Unless something treats the colours differently, all three share one curve.
            val shared = auto == null && balance[0] == balance[1] && balance[1] == balance[2] &&
                curves.red == curves.green && curves.green == curves.blue
            green = if (shared) red else curve(balance[1] * exposure, auto?.let { it::green }, tone, curves.green)
            blue = if (shared) red else curve(balance[2] * exposure, auto?.let { it::blue }, tone, curves.blue)

            val hazy = adjustments.dehaze != 0f
            val toned = adjustments.shadows != 0f || adjustments.highlights != 0f
            val clear = adjustments.clarity != 0f
            if (hazy || toned || clear) {
                val width = overview.width
                val height = overview.height
                val shownRed = FloatArray(width * height) { red[overview.red[it].toInt()] }
                val shownGreen = FloatArray(width * height) { green[overview.green[it].toInt()] }
                val shownBlue = FloatArray(width * height) { blue[overview.blue[it].toInt()] }
                val brightness = FloatArray(width * height) { LUMA_RED * shownRed[it] + LUMA_GREEN * shownGreen[it] + LUMA_BLUE * shownBlue[it] }
                val longSide = max(width, height)
                fun around(reach: Float) =
                    GuidedMap.of(brightness, brightness, width, height, max(1, (longSide * reach).roundToInt()), EDGE_VARIANCE)
                haze = if (hazy) Haze(shownRed, shownGreen, shownBlue, brightness, width, height, adjustments.dehaze) else null
                tones = if (toned) ShadowsAndHighlights(adjustments.shadows, adjustments.highlights) else null
                surroundings = if (toned) around(SURROUNDINGS) else null
                nearSurroundings = if (clear) around(NEAR_SURROUNDINGS) else null
            } else {
                haze = null
                tones = null
                surroundings = null
                nearSurroundings = null
            }
            clearness = haze?.clearness
            evenHaze = if (haze != null && clearness == null) haze.stretchFor(1f) else 0f
            usesSurroundings = clearness != null || surroundings != null || nearSurroundings != null
        }

        /** What [fill] works out for one row of the picture: for each pixel, how to tell its surroundings from its own brightness. */
        class Rows(width: Int, clear: Boolean, around: Boolean, near: Boolean) {
            val clearSlopes = FloatArray(if (clear) width else 0)
            val clearOffsets = FloatArray(if (clear) width else 0)
            val slopes = FloatArray(if (around) width else 0)
            val offsets = FloatArray(if (around) width else 0)
            val nearSlopes = FloatArray(if (near) width else 0)
            val nearOffsets = FloatArray(if (near) width else 0)
        }

        fun rows(width: Int) = Rows(width, clearness != null, surroundings != null, nearSurroundings != null)

        /** Prepares [rows] for the row that is [down] the picture, from 0 to 1. */
        fun fill(rows: Rows, columns: GuidedMap.Columns, down: Float) {
            clearness?.row(columns, down, rows.clearSlopes, rows.clearOffsets)
            surroundings?.row(columns, down, rows.slopes, rows.offsets)
            nearSurroundings?.row(columns, down, rows.nearSlopes, rows.nearOffsets)
        }

        /** Writes to [rgb] what the pixel in column [x] with the given light levels is shown as. */
        fun shade(redLevel: Int, greenLevel: Int, blueLevel: Int, x: Int, rows: Rows, rgb: FloatArray) {
            var r = red[redLevel]
            var g = green[greenLevel]
            var b = blue[blueLevel]

            if (haze != null) {
                // Away from the veil's colour to lift it, towards it to lay more on.
                val stretch = if (clearness == null) {
                    evenHaze
                } else {
                    haze.stretchFor(rows.clearSlopes[x] * (LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b) + rows.clearOffsets[x])
                }
                r = max(0f, r + (r - haze.airRed) * stretch)
                g = max(0f, g + (g - haze.airGreen) * stretch)
                b = max(0f, b + (b - haze.airBlue) * stretch)
            }

            if (tones != null || nearSurroundings != null) {
                val luma = LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b
                var gain = 1f
                if (tones != null) gain = tones.gainFor(rows.slopes[x] * luma + rows.offsets[x], luma)
                if (nearSurroundings != null && luma > 0f) {
                    // Further from what surrounds it, or closer to it; strongest in the middle tones.
                    val apart = luma - (rows.nearSlopes[x] * luma + rows.nearOffsets[x])
                    val middle = (4f * luma * (1f - luma)).coerceIn(0f, 1f)
                    gain *= max(0f, 1f + clarity * middle * apart / luma)
                }
                r *= gain
                g *= gain
                b *= gain
            }

            if (colorful) {
                val luma = LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b
                var factor = saturation
                if (vibrance != 0f) {
                    val most = max(r, max(g, b))
                    if (most > 0f) factor *= 1f + vibrance * min(r, min(g, b)) / most
                }
                r = luma + (r - luma) * factor
                g = luma + (g - luma) * factor
                b = luma + (b - luma) * factor
            }
            rgb[0] = r
            rgb[1] = g
            rgb[2] = b
        }

        /**
         * The display value of every level of one colour: light, [gain] times as much of it, through the
         * camera's curve; then auto enhance, the tones common to all colours, and the colour's [own] curve.
         */
        private fun curve(gain: Float, auto: ((Float) -> Float)?, tone: FloatArray?, own: ToneCurve): FloatArray {
            val display = CameraCurve.displayOfLevel
            if (gain == 1f && auto == null && tone == null && own.isStraight) return display
            val ownTable = if (own.isStraight) null else own.table(TONE_STEPS + 1)
            return FloatArray(LinearImage.LEVELS) { level ->
                var value = if (gain == 1f) display[level] else between(display, level * gain)
                if (auto != null) value = auto(value)
                if (tone != null) value = between(tone, value * TONE_STEPS)
                if (ownTable != null) value = between(ownTable, value * TONE_STEPS)
                value
            }
        }

        /** What whites, blacks, contrast and the tone curve for [all] colours make of every display value; null if nothing. */
        private fun toneTable(all: ToneCurve): FloatArray? {
            val whites = adjustments.whites * WHITES_REACH
            val blacks = adjustments.blacks * BLACKS_REACH
            val contrast = CONTRAST_AT_FULL.pow(adjustments.contrast)
            if (whites == 0f && blacks == 0f && contrast == 1f && all.isStraight) return null
            val curve = if (all.isStraight) null else all.table(TONE_STEPS + 1)
            return FloatArray(TONE_STEPS + 1) { step ->
                var value = step / TONE_STEPS.toFloat()
                // Whites stretch or squeeze the bright end the more the brighter a tone is; blacks do the same from the dark end.
                value = min(1f, value * (1f + whites * value * value))
                val darkness = 1f - value
                value = 1f - min(1f, darkness * (1f - blacks * darkness * darkness))
                if (contrast != 1f) value = contrasted(value, contrast)
                if (curve != null) value = between(curve, value * TONE_STEPS)
                value
            }
        }

        /** The value of [table] at a position between its entries; beyond its end, its last. */
        private fun between(table: FloatArray, position: Float): Float {
            val last = table.size - 1
            if (position >= last) return table[last]
            if (position <= 0f) return table[0]
            val below = position.toInt()
            return table[below] + (table[below + 1] - table[below]) * (position - below)
        }

        /** An S-curve around the middle gray: tones are pushed away from it, or pulled towards it below a power of one. */
        private fun contrasted(value: Float, power: Float): Float =
            if (value < MIDDLE_GRAY) {
                MIDDLE_GRAY * (value / MIDDLE_GRAY).pow(power)
            } else {
                1f - (1f - MIDDLE_GRAY) * ((1f - value) / (1f - MIDDLE_GRAY)).pow(power)
            }

        /** Gains for red, green and blue that shift the colour of the light without making it brighter or darker. */
        private fun whiteBalance(temperature: Float, tint: Float): FloatArray {
            if (temperature == 0f && tint == 0f) return floatArrayOf(1f, 1f, 1f)
            val red = 2f.pow(TEMPERATURE_STOPS * temperature)
            val green = 2f.pow(-TINT_STOPS * tint)
            val blue = 2f.pow(-TEMPERATURE_STOPS * temperature)
            val brightness = LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue
            return floatArrayOf(red / brightness, green / brightness, blue / brightness)
        }
    }

    companion object {
        /** Long side of the small copy a picture's tones are judged by. */
        const val OVERVIEW_EDGE = 320

        /**
         * Whether tones may be rendered on the graphics processor where there is one. Off, the
         * processor does it: for comparing the two.
         */
        @Volatile
        internal var useGraphicsProcessor = true

        /** Below this many pixels, handing a picture to the graphics processor and back costs more than it saves. */
        private const val SMALLEST_FOR_GRAPHICS_PROCESSOR = 250_000

        /** Auto enhance's judgement of the pictures in use, by the picture itself rather than its pixels. */
        private val autoLevels = WeakHashMap<LinearImage, AutoLevels>()

        private const val OPAQUE = 0xFF shl 24
        private const val TONE_STEPS = 4096
        private const val MIDDLE_GRAY = 0.45f
        private const val CONTRAST_AT_FULL = 1.6f
        private const val WHITES_REACH = 0.2f
        private const val BLACKS_REACH = 0.12f
        private const val TEMPERATURE_STOPS = 0.5f
        private const val TINT_STOPS = 0.35f
        private const val CLARITY_AT_FULL = 1.5f
        private const val SOFTENING_AT_FULL = 0.8f

        /** How far a pixel's surroundings reach, as a fraction of the picture's long side: for shadows and highlights, and for clarity. */
        private const val SURROUNDINGS = 0.04f
        private const val NEAR_SURROUNDINGS = 0.015f

        /** Brightness differences well above this (squared) count as an edge rather than as texture. */
        private const val EDGE_VARIANCE = 0.01f
    }
}

/** Renders the whole picture with [edit] applied, detail included; [scale] is as for [DetailFilter]. */
fun LinearImage.render(edit: PhotoEdit, scale: Float = 1f): IntArray {
    val pixels = ToneRenderer(edit, downscaled(ToneRenderer.OVERVIEW_EDGE)).render(this)
    val detail = DetailFilter(edit.adjustments, scale)
    return if (detail.isNeeded) detail.apply(pixels, width, height) else pixels
}
