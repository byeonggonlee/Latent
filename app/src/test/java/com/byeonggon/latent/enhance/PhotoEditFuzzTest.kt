package com.byeonggon.latent.enhance

import com.byeonggon.latent.fuzz
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A stored edit comes back from a backup or a filter as text, which may be damaged or written by something else:
 * [PhotoEdit.fromJson] reads it or says it cannot (null).
 */
class PhotoEditFuzzTest {
    private val seed = PhotoEdit(
        adjustments = Adjustments(exposure = 0.4f, contrast = 0.2f, clarity = 0.3f, vignette = -0.2f, grain = 0.1f, sharpening = 0.4f, noiseReduction = 0.2f),
        lensCorrection = false,
        framing = Framing(quarterTurns = 1, angle = 3f, left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.85f, vertical = 0.1f),
        curves = ToneCurves(all = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f)))),
        mixer = ColorMixer(hue = List(8) { 0.1f }, saturation = List(8) { -0.1f }, luminance = List(8) { 0.05f }),
        grading = ColorGrading(shadows = Tint(220f, 0.3f), highlights = Tint(40f, 0.2f), balance = -0.2f),
        locals = listOf(
            LocalEdit(Mask(MaskShape.RADIAL, 0.5f, 0.5f, 0.8f, 0.7f, feather = 0.4f), Adjustments(exposure = 0.5f), hue = 30f),
            LocalEdit(
                Mask(MaskShape.BRUSH, 0f, 0f, 0f, 0f, strokes = listOf(BrushStroke(listOf(0.1f, 0.1f, 0.4f, 0.5f), radius = 0.05f))),
                Adjustments(saturation = -0.5f),
                darkest = 0.1f,
                brightest = 0.9f,
            ),
            LocalEdit(
                Mask(MaskShape.DETECTED, 0f, 0f, 0f, 0f, detection = Detection(DetectedKind.SUBJECT, "0123456789abcdef0123456789abcdef", "m", 1)),
                Adjustments(shadows = 0.3f),
            ),
        ),
        spots = listOf(Spot(0.3f, 0.3f, 0.05f, 0.6f, 0.6f)),
        lut = LutLook("0123456789abcdef0123456789abcdef", 0.5f),
    ).toJson()

    @Test
    fun theSeedReadsBackAsItWasWritten() {
        assertEquals(seed, PhotoEdit.fromJson(seed)!!.toJson())
    }

    @Test
    fun aDamagedEditIsReadOrIsNone() = fuzz(seed = 51, cases = 5_000) { mutator ->
        PhotoEdit.fromJson(mutator.json(seed))
    }
}
