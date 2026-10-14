package com.byeonggon.latent.enhance

import androidx.annotation.StringRes
import com.byeonggon.latent.R

/**
 * A look with a name: tones and colours that can be given to any photo in one tap, and then dosed.
 * Of [look], only what [PhotoEdit.withLookOf] carries over counts.
 */
data class Filter(
    val id: String,
    /** The name the user gave a filter of their own; a built-in one is named by [builtInName]. */
    val name: String,
    val look: PhotoEdit,
    /** Whether the filter was saved by the user, and can be deleted again. */
    val custom: Boolean = false,
    @StringRes val builtInName: Int = 0,
)

/** The filters the app comes with. */
object Filters {
    private fun curve(vararg points: Pair<Float, Float>) = ToneCurve(points.map { CurvePoint(it.first, it.second) })

    private fun mixer(vararg bands: Pair<MixerBand, Triple<Float, Float, Float>>): ColorMixer {
        val byBand = bands.toMap()
        fun values(pick: (Triple<Float, Float, Float>) -> Float) = MixerBand.entries.map { band -> byBand[band]?.let(pick) ?: 0f }
        return ColorMixer(values { it.first }, values { it.second }, values { it.third })
    }

    /** Lifted blacks and gently held-back whites: the matte look of a print. */
    private val faded = curve(0f to 0.07f, 0.25f to 0.27f, 0.75f to 0.76f, 1f to 0.96f)

    /** Deeper shadows and brighter highlights around an unchanged middle. */
    private val punchy = curve(0f to 0f, 0.25f to 0.2f, 0.75f to 0.81f, 1f to 1f)

    private fun builtIn(id: String, @StringRes name: Int, look: PhotoEdit) = Filter(id, "", look, builtInName = name)

    val BUILT_IN: List<Filter> = listOf(
        builtIn("vivid", R.string.look_vivid, PhotoEdit(Adjustments(contrast = 0.15f, vibrance = 0.35f, clarity = 0.15f))),
        builtIn(
            "warm", R.string.look_warm,
            PhotoEdit(
                Adjustments(temperature = 0.22f, tint = 0.04f, vibrance = 0.1f),
                grading = ColorGrading(highlights = Tint(42f, 0.2f)),
            ),
        ),
        builtIn(
            "cool", R.string.look_cool,
            PhotoEdit(Adjustments(temperature = -0.22f, contrast = 0.05f), grading = ColorGrading(shadows = Tint(215f, 0.25f))),
        ),
        builtIn(
            "soft", R.string.look_soft,
            PhotoEdit(Adjustments(contrast = -0.1f, saturation = -0.08f, clarity = -0.1f), curves = ToneCurves(all = faded)),
        ),
        builtIn(
            "landscape", R.string.look_landscape,
            PhotoEdit(
                Adjustments(highlights = -0.3f, shadows = 0.2f, vibrance = 0.3f, clarity = 0.25f, dehaze = 0.15f),
                mixer = mixer(MixerBand.BLUE to Triple(0f, 0.2f, -0.15f), MixerBand.GREEN to Triple(0f, 0.15f, 0f), MixerBand.AQUA to Triple(0f, 0.1f, -0.1f)),
            ),
        ),
        builtIn(
            "portrait", R.string.look_portrait,
            PhotoEdit(
                Adjustments(shadows = 0.15f, vibrance = 0.1f, texture = -0.2f, clarity = -0.12f),
                mixer = mixer(MixerBand.ORANGE to Triple(0f, -0.05f, 0.12f), MixerBand.RED to Triple(0f, -0.05f, 0.05f)),
            ),
        ),
        builtIn(
            "food", R.string.look_food,
            PhotoEdit(Adjustments(contrast = 0.1f, shadows = 0.15f, temperature = 0.08f, vibrance = 0.3f, clarity = 0.1f)),
        ),
        builtIn(
            "teal-orange", R.string.look_teal_orange,
            PhotoEdit(
                Adjustments(contrast = 0.1f, vibrance = 0.1f),
                grading = ColorGrading(shadows = Tint(190f, 0.5f), highlights = Tint(35f, 0.45f)),
            ),
        ),
        builtIn(
            "film", R.string.look_film,
            PhotoEdit(
                Adjustments(saturation = -0.1f, vignette = -0.2f, grain = 0.35f),
                curves = ToneCurves(all = faded),
                grading = ColorGrading(shadows = Tint(170f, 0.18f), highlights = Tint(45f, 0.2f)),
            ),
        ),
        builtIn(
            "vintage", R.string.look_vintage,
            PhotoEdit(
                Adjustments(contrast = -0.05f, temperature = 0.18f, saturation = -0.25f, vignette = -0.3f, grain = 0.3f),
                curves = ToneCurves(all = faded),
                grading = ColorGrading(shadows = Tint(280f, 0.12f), highlights = Tint(50f, 0.25f)),
            ),
        ),
        builtIn(
            "dramatic", R.string.look_dramatic,
            PhotoEdit(
                Adjustments(contrast = 0.3f, highlights = -0.4f, shadows = 0.2f, saturation = -0.1f, clarity = 0.4f, dehaze = 0.2f, vignette = -0.25f),
            ),
        ),
        builtIn(
            "cinema", R.string.look_cinema,
            PhotoEdit(
                Adjustments(contrast = 0.1f, saturation = -0.15f, vignette = -0.3f),
                curves = ToneCurves(all = curve(0f to 0.03f, 0.3f to 0.25f, 0.7f to 0.74f, 1f to 0.93f)),
                grading = ColorGrading(shadows = Tint(200f, 0.4f), midtones = Tint(30f, 0.1f), highlights = Tint(40f, 0.3f), balance = -0.1f),
            ),
        ),
        builtIn(
            "hdr", R.string.look_hdr,
            PhotoEdit(Adjustments(highlights = -0.6f, shadows = 0.55f, whites = 0.1f, blacks = -0.1f, vibrance = 0.25f, texture = 0.25f, clarity = 0.5f)),
        ),
        builtIn(
            "glow", R.string.look_glow,
            PhotoEdit(Adjustments(contrast = -0.05f, highlights = -0.1f, vibrance = 0.15f, clarity = -0.2f, glow = 0.5f)),
        ),
        builtIn("mono", R.string.look_mono, PhotoEdit(Adjustments(contrast = 0.1f), monochrome = true)),
        builtIn(
            "mono-contrast", R.string.look_mono_contrast,
            PhotoEdit(
                Adjustments(contrast = 0.35f, blacks = -0.2f, clarity = 0.3f),
                curves = ToneCurves(all = punchy),
                mixer = mixer(MixerBand.BLUE to Triple(0f, 0f, -0.5f), MixerBand.AQUA to Triple(0f, 0f, -0.3f), MixerBand.ORANGE to Triple(0f, 0f, 0.15f)),
                monochrome = true,
            ),
        ),
        builtIn(
            "mono-film", R.string.look_mono_film,
            PhotoEdit(Adjustments(vignette = -0.2f, grain = 0.45f), curves = ToneCurves(all = faded), monochrome = true),
        ),
        builtIn(
            "noir", R.string.look_noir,
            PhotoEdit(
                Adjustments(contrast = 0.45f, blacks = -0.3f, shadows = -0.2f, clarity = 0.2f, vignette = -0.45f, grain = 0.25f),
                mixer = ColorMixer(luminance = List(MixerBand.entries.size) { if (it == MixerBand.RED.ordinal || it == MixerBand.ORANGE.ordinal) 0.15f else -0.1f }),
                monochrome = true,
            ),
        ),
        builtIn(
            "sepia", R.string.look_sepia,
            PhotoEdit(
                Adjustments(contrast = 0.05f),
                grading = ColorGrading(shadows = Tint(32f, 0.4f), midtones = Tint(36f, 0.25f), highlights = Tint(45f, 0.25f)),
                monochrome = true,
            ),
        ),
    )
}
