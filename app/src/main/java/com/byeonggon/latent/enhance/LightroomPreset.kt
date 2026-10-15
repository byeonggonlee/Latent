package com.byeonggon.latent.enhance

import android.util.Xml
import androidx.annotation.StringRes
import com.byeonggon.latent.R
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a Lightroom preset asks for that this app cannot do, in the groups it is told to the person in. */
enum class Unsupported(@StringRes val label: Int) {
    WHITE_BALANCE(R.string.preset_skip_white_balance),
    CALIBRATION(R.string.preset_skip_calibration),
    PARAMETRIC_CURVE(R.string.preset_skip_parametric),
    LENS(R.string.preset_skip_lens),
    GEOMETRY(R.string.preset_skip_geometry),
    DETAIL(R.string.preset_skip_detail),
    EFFECTS(R.string.preset_skip_effects),
    GRADING(R.string.preset_skip_grading),
    PROFILE(R.string.preset_skip_profile),
    MASKS(R.string.preset_skip_masks),
    OTHER(R.string.preset_skip_other),
}

/** A look read from a Lightroom preset or a photo's develop settings: its [name] if it has one, the [edit] it comes to, how many [used] settings went into it, and what was [skipped]. */
data class ImportedPreset(val name: String?, val edit: PhotoEdit, val used: Int, val skipped: Set<Unsupported>)

/**
 * Reads the XMP files of Lightroom (presets, and the develop settings of a photo's sidecar) into a
 * [PhotoEdit]. Settings are in the "crs" namespace, as attributes of the description or as elements;
 * a curve is a list of points. Tones and colours map onto the look of an edit: exposure, contrast,
 * highlights, shadows, whites, blacks, relative white balance, texture, clarity, dehaze, vibrance,
 * saturation, colour mixer, colour grading, point curves, vignette, grain and the detail settings.
 */
object LightroomPreset {
    private const val CRS = "http://ns.adobe.com/camera-raw-settings/1.0/"
    private const val LARGEST = 2_000_000

    /** Reads [input]; null when it is not a file of settings, or cannot be read. */
    fun parse(input: InputStream): ImportedPreset? {
        val settings = try {
            readSettings(input.readNBytes(LARGEST).inputStream())
        } catch (e: IOException) {
            return null
        } catch (e: XmlPullParserException) {
            return null
        } ?: return null
        return settings.toPreset()
    }

    private class Settings(val values: Map<String, String>, val lists: Map<String, List<String>>) {
        fun number(key: String): Float? = values[key]?.trim()?.removePrefix("+")?.toFloatOrNull()
    }

    private fun readSettings(input: InputStream): Settings? {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val values = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, MutableList<String>>()
        val prefixes = HashSet<String>()
        var property: String? = null
        var inItem = false
        val text = StringBuilder()
        val item = StringBuilder()
        var found = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    val local = tag.substringAfter(':')
                    if (local == "Description") {
                        for (index in 0 until parser.attributeCount) {
                            val name = parser.getAttributeName(index)
                            if (name.startsWith("xmlns:") && parser.getAttributeValue(index) == CRS) prefixes += name.removePrefix("xmlns:")
                        }
                        for (index in 0 until parser.attributeCount) {
                            val name = parser.getAttributeName(index)
                            if (':' in name && name.substringBefore(':') in prefixes) {
                                values[name.substringAfter(':')] = parser.getAttributeValue(index)
                                found = true
                            }
                        }
                    } else if (local == "li" && property != null) {
                        inItem = true
                        item.clear()
                    } else if (':' in tag && tag.substringBefore(':') in prefixes) {
                        property = local
                        text.clear()
                        found = true
                    }
                }
                XmlPullParser.TEXT -> if (inItem) item.append(parser.text) else if (property != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name
                    val local = tag.substringAfter(':')
                    if (local == "li" && inItem) {
                        lists.getOrPut(property!!) { ArrayList() } += item.toString().trim()
                        inItem = false
                    } else if (property != null && local == property && ':' in tag && tag.substringBefore(':') in prefixes) {
                        if (property !in lists && text.isNotBlank()) values[property] = text.toString().trim()
                        property = null
                    }
                }
            }
            event = parser.next()
        }
        return if (found) Settings(values, lists) else null
    }

    // Keys that say what the file is, not what it does.
    private val ABOUT_THE_FILE = setOf(
        "PresetType", "Cluster", "UUID", "SupportsAmount", "SupportsAmount2", "SupportsColor", "SupportsMonochrome", "SupportsHighDynamicRange",
        "SupportsNormalDynamicRange", "SupportsSceneReferred", "SupportsOutputReferred", "CameraModelRestriction", "Copyright", "ContactInfo",
        "Version", "ProcessVersion", "HasSettings", "CompatibleVersion", "Group", "Name", "SortName", "ShortName", "Description", "ToneCurveName2012",
        "AlreadyApplied", "RawFileName", "Rating", "Label", "Title", "Look", "LookTable",
    )

    private val CURVES = listOf("ToneCurvePV2012", "ToneCurvePV2012Red", "ToneCurvePV2012Green", "ToneCurvePV2012Blue")
    private val BANDS = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")

    /** The settings that are taken, by the keys they are under. */
    private fun Settings.toPreset(): ImportedPreset {
        val taken = HashSet<String>()
        fun scaled(key: String, divisor: Float, low: Float = -1f, high: Float = 1f): Float {
            val value = number(key) ?: return 0f
            taken += key
            return (value / divisor).coerceIn(low, high)
        }

        val adjustments = Adjustments(
            exposure = scaled("Exposure2012", 1f, -3f, 3f),
            contrast = scaled("Contrast2012", 100f),
            highlights = scaled("Highlights2012", 100f),
            shadows = scaled("Shadows2012", 100f),
            whites = scaled("Whites2012", 100f),
            blacks = scaled("Blacks2012", 100f),
            // Only the relative white balance of a preset is meant to be given to other photos.
            temperature = scaled("IncrementalTemperature", 100f),
            tint = scaled("IncrementalTint", 100f),
            vibrance = scaled("Vibrance", 100f),
            saturation = scaled("Saturation", 100f),
            texture = scaled("Texture", 100f),
            clarity = scaled("Clarity2012", 100f),
            dehaze = scaled("Dehaze", 100f),
            vignette = scaled("PostCropVignetteAmount", 100f),
            grain = scaled("GrainAmount", 100f, 0f, 1f),
            sharpening = scaled("Sharpness", 150f, 0f, 1f),
            noiseReduction = scaled("LuminanceSmoothing", 100f, 0f, 1f),
            colorNoiseReduction = scaled("ColorNoiseReduction", 100f, 0f, 1f),
        )

        val monochrome = values["ConvertToGrayscale"]?.equals("True", ignoreCase = true) == true
        if (values.containsKey("ConvertToGrayscale")) taken += "ConvertToGrayscale"
        val mixer = ColorMixer(
            hue = BANDS.map { scaled("HueAdjustment$it", 100f) },
            saturation = BANDS.map { scaled("SaturationAdjustment$it", 100f) },
            // In black and white the mix of the colours is the gray mixer's.
            luminance = BANDS.map { scaled(if (monochrome && values.containsKey("GrayMixer$it")) "GrayMixer$it" else "LuminanceAdjustment$it", 100f) },
        )

        fun tint(hue: String, saturation: String): Tint {
            val amount = scaled(saturation, 100f, 0f, 1f)
            // A colour that nothing is left of has no hue to speak of.
            if (amount == 0f) return Tint()
            val degrees = number(hue)?.also { taken += hue } ?: 0f
            return Tint(degrees.mod(360f), amount)
        }
        val grading = ColorGrading(
            shadows = tint("SplitToningShadowHue", "SplitToningShadowSaturation"),
            midtones = tint("ColorGradeMidtoneHue", "ColorGradeMidtoneSat"),
            highlights = tint("SplitToningHighlightHue", "SplitToningHighlightSaturation"),
            balance = scaled("SplitToningBalance", 100f),
        )

        val curves = ToneCurves(
            all = curve("ToneCurvePV2012", taken),
            red = curve("ToneCurvePV2012Red", taken),
            green = curve("ToneCurvePV2012Green", taken),
            blue = curve("ToneCurvePV2012Blue", taken),
        )

        val edit = PhotoEdit(adjustments = adjustments, curves = curves, mixer = mixer, grading = grading, monochrome = monochrome)
        val skipped = HashSet<Unsupported>()
        for ((key, value) in values) {
            if (key in taken || key in ABOUT_THE_FILE || isNeutral(value)) continue
            skipped += unsupportedGroupOf(key)
        }
        for (key in lists.keys) if (key !in taken && key !in CURVES && key !in ABOUT_THE_FILE) skipped += unsupportedGroupOf(key)
        // Settings that are present but come to nothing do not count as used.
        val used = taken.count { key -> key in CURVES || !isNeutral(values[key] ?: "x") }
        return ImportedPreset(name = lists["Name"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: values["Name"], edit = edit, used = used, skipped = skipped)
    }

    /** The curve under [key], points of 0 to 255; straight when there is none, or it is not one. */
    private fun Settings.curve(key: String, taken: MutableSet<String>): ToneCurve {
        val points = lists[key]?.mapNotNull { pair ->
            val parts = pair.split(',').map { it.trim().toFloatOrNull() }
            if (parts.size == 2 && parts[0] != null && parts[1] != null) CurvePoint((parts[0]!! / 255f).coerceIn(0f, 1f), (parts[1]!! / 255f).coerceIn(0f, 1f)) else null
        }.orEmpty().sortedBy { it.x }
        if (points.size < 2 || isDiagonal(points)) return ToneCurve()
        taken += key
        return ToneCurve(points)
    }

    // A curve of the two end points, from black to black and white to white, is no curve.
    private fun isDiagonal(points: List<CurvePoint>) = points.all { abs(it.x - it.y) < 0.002f }

    private fun isNeutral(value: String): Boolean {
        val text = value.trim()
        return text.isEmpty() || text.equals("False", true) || text.equals("None", true) || text.equals("Linear", true) ||
            text.removePrefix("+").removePrefix("-").toFloatOrNull() == 0f
    }

    private fun unsupportedGroupOf(key: String): Unsupported = when {
        key == "Temperature" || key == "Tint" || key == "WhiteBalance" -> Unsupported.WHITE_BALANCE
        key.startsWith("Camera") && key != "CameraModelRestriction" || key.startsWith("RGBTable") || key.startsWith("Lookup") -> Unsupported.PROFILE
        key.startsWith("ShadowTint") || Regex("^(Red|Green|Blue)(Hue|Saturation)$").matches(key) -> Unsupported.CALIBRATION
        key.startsWith("Parametric") -> Unsupported.PARAMETRIC_CURVE
        key.startsWith("Lens") || key.contains("Defringe") || key.contains("ChromaticAberration") || key.startsWith("AutoLateralCA") -> Unsupported.LENS
        key.startsWith("Upright") || key.startsWith("Perspective") || key.startsWith("Crop") || key.startsWith("Orientation") -> Unsupported.GEOMETRY
        key.startsWith("Sharpen") || key == "LuminanceSmoothingDetail" || key == "LuminanceNoiseReductionContrast" || key.startsWith("ColorNoiseReduction") -> Unsupported.DETAIL
        key.startsWith("PostCropVignette") || key.startsWith("Grain") || key.startsWith("Glow") -> Unsupported.EFFECTS
        key.startsWith("ColorGrade") || key.startsWith("SplitToning") -> Unsupported.GRADING
        key.contains("Corrections") || key.startsWith("Mask") -> Unsupported.MASKS
        else -> Unsupported.OTHER
    }

    /** The settings as a percentage, for showing: 0.37 as "37". */
    internal fun percent(value: Float) = (value * 100).roundToInt()
}
