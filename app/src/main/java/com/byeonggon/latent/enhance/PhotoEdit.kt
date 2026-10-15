package com.byeonggon.latent.enhance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What the sliders are set to. Zero leaves the picture as it is. [exposure] is in stops; the others
 * go from -1 to 1, except for those that can only add something, which go from 0 to 1.
 */
data class Adjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    /** Towards blue below zero, towards yellow above. */
    val temperature: Float = 0f,
    /** Towards green below zero, towards magenta above. */
    val tint: Float = 0f,
    /** Like [saturation], but for the colours that are not vivid yet. */
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    /** Contrast of fine detail, such as skin, bark or fabric. */
    val texture: Float = 0f,
    /** Contrast between each part of the picture and what surrounds it. */
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    /** Darkens the corners below zero, lightens them above. */
    val vignette: Float = 0f,
    /** From 0 to 1. */
    val grain: Float = 0f,
    /** From 0 to 1: a soft light that spreads from the bright parts, as from a diffusion filter. */
    val glow: Float = 0f,
    /** From 0 to 1. */
    val sharpening: Float = 0f,
    /** From 0 to 1: smooths out noise in brightness. */
    val noiseReduction: Float = 0f,
    /** From 0 to 1: smooths out speckles of false colour. */
    val colorNoiseReduction: Float = 0f,
)

/** Every setting of [Adjustments] by name, for what has to treat them all alike. */
enum class Adjustment(val key: String, val get: (Adjustments) -> Float, val set: (Adjustments, Float) -> Adjustments) {
    EXPOSURE("exposure", { it.exposure }, { all, value -> all.copy(exposure = value) }),
    CONTRAST("contrast", { it.contrast }, { all, value -> all.copy(contrast = value) }),
    HIGHLIGHTS("highlights", { it.highlights }, { all, value -> all.copy(highlights = value) }),
    SHADOWS("shadows", { it.shadows }, { all, value -> all.copy(shadows = value) }),
    WHITES("whites", { it.whites }, { all, value -> all.copy(whites = value) }),
    BLACKS("blacks", { it.blacks }, { all, value -> all.copy(blacks = value) }),
    TEMPERATURE("temperature", { it.temperature }, { all, value -> all.copy(temperature = value) }),
    TINT("tint", { it.tint }, { all, value -> all.copy(tint = value) }),
    VIBRANCE("vibrance", { it.vibrance }, { all, value -> all.copy(vibrance = value) }),
    SATURATION("saturation", { it.saturation }, { all, value -> all.copy(saturation = value) }),
    TEXTURE("texture", { it.texture }, { all, value -> all.copy(texture = value) }),
    CLARITY("clarity", { it.clarity }, { all, value -> all.copy(clarity = value) }),
    DEHAZE("dehaze", { it.dehaze }, { all, value -> all.copy(dehaze = value) }),
    VIGNETTE("vignette", { it.vignette }, { all, value -> all.copy(vignette = value) }),
    GRAIN("grain", { it.grain }, { all, value -> all.copy(grain = value) }),
    GLOW("glow", { it.glow }, { all, value -> all.copy(glow = value) }),
    SHARPENING("sharpening", { it.sharpening }, { all, value -> all.copy(sharpening = value) }),
    NOISE_REDUCTION("noiseReduction", { it.noiseReduction }, { all, value -> all.copy(noiseReduction = value) }),
    COLOR_NOISE_REDUCTION("colorNoiseReduction", { it.colorNoiseReduction }, { all, value -> all.copy(colorNoiseReduction = value) }),
}

/** The colours the mixer tells apart, each with the hue at its middle, in degrees around the colour wheel. */
enum class MixerBand(val hue: Float) {
    RED(0f),
    ORANGE(30f),
    YELLOW(60f),
    GREEN(120f),
    AQUA(180f),
    BLUE(240f),
    PURPLE(275f),
    MAGENTA(315f),
}

/**
 * Changes to single colours: for each [MixerBand], in their order, how far its hue is shifted, how
 * much more vivid and how much brighter it becomes, each from -1 to 1. In a black-and-white picture
 * only [luminance] counts, as how bright each colour comes out as gray.
 */
data class ColorMixer(
    val hue: List<Float> = UNCHANGED,
    val saturation: List<Float> = UNCHANGED,
    val luminance: List<Float> = UNCHANGED,
) {
    companion object {
        val UNCHANGED: List<Float> = List(MixerBand.entries.size) { 0f }
    }
}

/** A colour to tint with: its [hue] in degrees around the colour wheel, and how much of it, from 0 to 1. */
data class Tint(val hue: Float = 0f, val amount: Float = 0f)

/**
 * Colours laid over the dark, the middle and the bright tones. [balance], from -1 to 1, moves the
 * border between them: above zero more of the picture counts as bright.
 */
data class ColorGrading(
    val shadows: Tint = Tint(),
    val midtones: Tint = Tint(),
    val highlights: Tint = Tint(),
    val balance: Float = 0f,
)

/**
 * Everything chosen for one photo. It is kept with the photo's library entry while the original
 * stays untouched, so an edit can be reopened, changed and undone at any time.
 */
data class PhotoEdit(
    val adjustments: Adjustments = Adjustments(),
    val autoEnhance: Boolean = false,
    /** Raw files only: undo what the lens did to straight lines and colour edges, like the camera does. */
    val lensCorrection: Boolean = true,
    val framing: Framing = Framing(),
    val curves: ToneCurves = ToneCurves(),
    val mixer: ColorMixer = ColorMixer(),
    val grading: ColorGrading = ColorGrading(),
    /** Black and white. */
    val monochrome: Boolean = false,
    /** Settings for parts of the picture only. */
    val locals: List<LocalEdit> = emptyList(),
    /** Blemishes taken out. */
    val spots: List<Spot> = emptyList(),
) {
    /** Whether the picture's tones and colours come out any different from the original's. */
    val changesColors: Boolean get() = copy(lensCorrection = true, framing = Framing()) != PhotoEdit()

    /** Whether the picture comes out any different from the original at all. */
    val changesPicture: Boolean get() = changesColors || framing != Framing()

    /**
     * This edit with the tones and colours of [other], its look. What belongs to this photo alone stays
     * its own: framing, lens correction, the settings for parts of the picture and the blemishes taken out.
     */
    fun withLookOf(other: PhotoEdit): PhotoEdit =
        other.copy(lensCorrection = lensCorrection, framing = framing, locals = locals, spots = spots)

    /**
     * The look of this edit at a strength of [amount]: nothing of it at 0, all of it at 1, and beyond
     * that more than was meant. This is how filters are dosed.
     */
    fun lookAt(amount: Float): PhotoEdit {
        // Adding zero turns the negative zero that a negative setting is scaled down to into plain zero, which is what "untouched" compares equal to.
        fun dosed(value: Float) = value * amount + 0f

        // A colour that nothing is left of is no colour at all.
        fun dosed(tint: Tint) = if (dosed(tint.amount) == 0f) Tint() else tint.copy(amount = dosed(tint.amount))
        return copy(
            adjustments = Adjustment.entries.fold(adjustments) { all, each -> each.set(all, dosed(each.get(all))) },
            curves = curves.mixedWithStraight(amount),
            mixer = ColorMixer(mixer.hue.map(::dosed), mixer.saturation.map(::dosed), mixer.luminance.map(::dosed)),
            grading = ColorGrading(dosed(grading.shadows), dosed(grading.midtones), dosed(grading.highlights), dosed(grading.balance)),
            monochrome = monochrome && amount > 0f,
        )
    }

    fun toJson(): String = toJsonObject().toString()

    /** Only what differs from an untouched picture is written; anything missing is read back as untouched. */
    fun toJsonObject(): JsonObject = JsonObject().apply {
        for (each in Adjustment.entries) each.get(adjustments).takeIf { it != 0f }?.let { addProperty(each.key, it) }
        if (autoEnhance) addProperty(AUTO_ENHANCE, true)
        if (!lensCorrection) addProperty(LENS_CORRECTION, false)
        if (monochrome) addProperty(MONOCHROME, true)
        if (framing != Framing()) {
            addProperty(QUARTER_TURNS, framing.quarterTurns)
            addProperty(ANGLE, framing.angle)
            addProperty(CROP_LEFT, framing.left)
            addProperty(CROP_TOP, framing.top)
            addProperty(CROP_RIGHT, framing.right)
            addProperty(CROP_BOTTOM, framing.bottom)
            if (framing.flipped) addProperty(FLIPPED, true)
            if (framing.vertical != 0f) addProperty(TILT_VERTICAL, framing.vertical)
            if (framing.horizontal != 0f) addProperty(TILT_HORIZONTAL, framing.horizontal)
        }
        if (curves != ToneCurves()) {
            val stored = JsonObject()
            for ((name, curve) in curves.byName()) if (!curve.isStraight) stored.add(name, curve.toJson())
            add(CURVES, stored)
        }
        if (mixer != ColorMixer()) {
            val stored = JsonObject()
            stored.add(HUE, numbers(mixer.hue))
            stored.add(SATURATION, numbers(mixer.saturation))
            stored.add(LUMINANCE, numbers(mixer.luminance))
            add(MIXER, stored)
        }
        if (locals.isNotEmpty()) {
            val stored = JsonArray()
            for (local in locals) {
                val each = JsonObject()
                each.addProperty(SHAPE, local.mask.shape.name)
                each.add(MASK, numbers(listOf(local.mask.startX, local.mask.startY, local.mask.endX, local.mask.endY, local.mask.feather)))
                if (local.mask.inverted) each.addProperty(INVERTED, true)
                if (local.mask.strokes.isNotEmpty()) {
                    val strokes = JsonArray()
                    for (stroke in local.mask.strokes) {
                        val one = JsonObject()
                        one.addProperty(RADIUS, stroke.radius)
                        if (stroke.erasing) one.addProperty(ERASING, true)
                        one.add(POINTS, numbers(stroke.points))
                        strokes.add(one)
                    }
                    each.add(STROKES, strokes)
                }
                for (setting in Adjustment.entries) setting.get(local.adjustments).takeIf { it != 0f }?.let { each.addProperty(setting.key, it) }
                if (local.darkest > 0f) each.addProperty(DARKEST, local.darkest)
                if (local.brightest < 1f) each.addProperty(BRIGHTEST, local.brightest)
                local.hue?.let {
                    each.addProperty(COLOUR_HUE, it)
                    if (local.hueSpread != LocalEdit.DEFAULT_HUE_SPREAD) each.addProperty(COLOUR_SPREAD, local.hueSpread)
                }
                stored.add(each)
            }
            add(LOCALS, stored)
        }
        if (spots.isNotEmpty()) {
            val stored = JsonArray()
            for (spot in spots) stored.add(numbers(listOf(spot.x, spot.y, spot.radius, spot.fromX, spot.fromY)))
            add(SPOTS, stored)
        }
        if (grading != ColorGrading()) {
            val stored = JsonObject()
            stored.add(SHADOWS, numbers(listOf(grading.shadows.hue, grading.shadows.amount)))
            stored.add(MIDTONES, numbers(listOf(grading.midtones.hue, grading.midtones.amount)))
            stored.add(HIGHLIGHTS, numbers(listOf(grading.highlights.hue, grading.highlights.amount)))
            stored.addProperty(BALANCE, grading.balance)
            add(GRADING, stored)
        }
    }

    companion object {
        private const val AUTO_ENHANCE = "autoEnhance"
        private const val LENS_CORRECTION = "lensCorrection"
        private const val MONOCHROME = "monochrome"
        private const val QUARTER_TURNS = "quarterTurns"
        private const val ANGLE = "angle"
        private const val CROP_LEFT = "cropLeft"
        private const val CROP_TOP = "cropTop"
        private const val CROP_RIGHT = "cropRight"
        private const val CROP_BOTTOM = "cropBottom"
        private const val FLIPPED = "flipped"
        private const val TILT_VERTICAL = "tiltVertical"
        private const val TILT_HORIZONTAL = "tiltHorizontal"
        private const val CURVES = "curves"
        private const val MIXER = "mixer"
        private const val HUE = "hue"
        private const val SATURATION = "saturation"
        private const val LUMINANCE = "luminance"
        private const val GRADING = "grading"
        private const val SHADOWS = "shadows"
        private const val MIDTONES = "midtones"
        private const val HIGHLIGHTS = "highlights"
        private const val BALANCE = "balance"
        private const val LOCALS = "locals"
        private const val SPOTS = "spots"
        private const val SHAPE = "shape"
        private const val MASK = "mask"
        private const val INVERTED = "inverted"
        private const val STROKES = "strokes"
        private const val DARKEST = "darkest"
        private const val BRIGHTEST = "brightest"
        private const val COLOUR_HUE = "colourHue"
        private const val COLOUR_SPREAD = "colourSpread"
        private const val RADIUS = "radius"
        private const val ERASING = "erasing"
        private const val POINTS = "points"

        /**
         * Reads what [toJson] wrote. Settings that a stored edit does not mention, because they were
         * left alone or added to the app later, are left alone. Returns null for anything unreadable.
         */
        fun fromJson(json: String?): PhotoEdit? {
            if (json == null) return null
            return try {
                fromJsonObject(JsonParser.parseString(json).asJsonObject)
            } catch (e: RuntimeException) {
                // Gson reports malformed text and values of the wrong kind with assorted unchecked exceptions.
                null
            }
        }

        /** Like [fromJson], but throws on values of the wrong kind. */
        fun fromJsonObject(stored: JsonObject): PhotoEdit {
            fun number(name: String, otherwise: Float = 0f) = stored.get(name)?.asFloat ?: otherwise
            return PhotoEdit(
                adjustments = Adjustment.entries.fold(Adjustments()) { all, each -> each.set(all, number(each.key)) },
                autoEnhance = stored.get(AUTO_ENHANCE)?.asBoolean ?: false,
                lensCorrection = stored.get(LENS_CORRECTION)?.asBoolean ?: true,
                framing = Framing(
                    quarterTurns = stored.get(QUARTER_TURNS)?.asInt ?: 0,
                    angle = number(ANGLE),
                    left = number(CROP_LEFT),
                    top = number(CROP_TOP),
                    right = number(CROP_RIGHT, otherwise = 1f),
                    bottom = number(CROP_BOTTOM, otherwise = 1f),
                    flipped = stored.get(FLIPPED)?.asBoolean ?: false,
                    vertical = number(TILT_VERTICAL),
                    horizontal = number(TILT_HORIZONTAL),
                ),
                curves = stored.getAsJsonObject(CURVES)?.let { curves ->
                    ToneCurves.named { name -> curves.get(name)?.let(ToneCurve::fromJson) ?: ToneCurve() }
                } ?: ToneCurves(),
                mixer = stored.getAsJsonObject(MIXER)?.let { mixer ->
                    ColorMixer(bandValues(mixer.get(HUE)), bandValues(mixer.get(SATURATION)), bandValues(mixer.get(LUMINANCE)))
                } ?: ColorMixer(),
                grading = stored.getAsJsonObject(GRADING)?.let { grading ->
                    ColorGrading(
                        shadows = tint(grading.get(SHADOWS)),
                        midtones = tint(grading.get(MIDTONES)),
                        highlights = tint(grading.get(HIGHLIGHTS)),
                        balance = grading.get(BALANCE)?.asFloat ?: 0f,
                    )
                } ?: ColorGrading(),
                monochrome = stored.get(MONOCHROME)?.asBoolean ?: false,
                locals = stored.getAsJsonArray(LOCALS)?.map { each ->
                    val local = each.asJsonObject
                    val mask = local.getAsJsonArray(MASK).map { it.asFloat }
                    LocalEdit(
                        Mask(
                            shape = MaskShape.valueOf(local.get(SHAPE).asString),
                            startX = mask[0],
                            startY = mask[1],
                            endX = mask[2],
                            endY = mask[3],
                            feather = mask[4],
                            inverted = local.get(INVERTED)?.asBoolean ?: false,
                            strokes = local.getAsJsonArray(STROKES)?.map { one ->
                                val stroke = one.asJsonObject
                                BrushStroke(
                                    points = stroke.getAsJsonArray(POINTS).map { it.asFloat },
                                    radius = stroke.get(RADIUS).asFloat,
                                    erasing = stroke.get(ERASING)?.asBoolean ?: false,
                                )
                            } ?: emptyList(),
                        ),
                        Adjustment.entries.fold(Adjustments()) { all, setting -> setting.set(all, local.get(setting.key)?.asFloat ?: 0f) },
                        darkest = local.get(DARKEST)?.asFloat ?: 0f,
                        brightest = local.get(BRIGHTEST)?.asFloat ?: 1f,
                        hue = local.get(COLOUR_HUE)?.asFloat,
                        hueSpread = local.get(COLOUR_SPREAD)?.asFloat ?: LocalEdit.DEFAULT_HUE_SPREAD,
                    )
                } ?: emptyList(),
                spots = stored.getAsJsonArray(SPOTS)?.map { each ->
                    val values = each.asJsonArray.map { it.asFloat }
                    Spot(values[0], values[1], values[2], values[3], values[4])
                } ?: emptyList(),
            )
        }

        private fun numbers(values: List<Float>) = JsonArray().apply { values.forEach(::add) }

        private fun bandValues(stored: JsonElement?): List<Float> {
            val values = stored?.asJsonArray?.map { it.asFloat } ?: return ColorMixer.UNCHANGED
            return List(MixerBand.entries.size) { values.getOrElse(it) { 0f } }
        }

        private fun tint(stored: JsonElement?): Tint {
            val values = stored?.asJsonArray ?: return Tint()
            return Tint(hue = values[0].asFloat, amount = values[1].asFloat)
        }
    }
}
