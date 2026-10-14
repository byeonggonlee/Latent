package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditTest {
    @Test
    fun survivesBeingStored() {
        val edit = PhotoEdit(
            Adjustments(
                exposure = 0.35f,
                contrast = -0.2f,
                highlights = -1f,
                shadows = 0.64f,
                temperature = 0.07f,
                tint = -0.03f,
                vibrance = 0.5f,
                saturation = -0.11f,
            ),
            autoEnhance = true,
            lensCorrection = false,
        )

        assertEquals(edit, PhotoEdit.fromJson(edit.toJson()))
        assertEquals(PhotoEdit(), PhotoEdit.fromJson(PhotoEdit().toJson()))
    }

    @Test
    fun leavesSettingsAStoredEditDoesNotMentionAsTheyAreByDefault() {
        val edit = PhotoEdit.fromJson("""{"exposure":1.5}""")

        assertEquals(PhotoEdit(Adjustments(exposure = 1.5f)), edit)
        assertTrue(edit!!.lensCorrection)
    }

    @Test
    fun hasNothingToSayAboutWhatItCannotRead() {
        assertNull(PhotoEdit.fromJson(null))
        assertNull(PhotoEdit.fromJson("not an edit"))
        assertNull(PhotoEdit.fromJson("[1, 2]"))
        assertNull(PhotoEdit.fromJson("""{"exposure":"bright"}"""))
    }

    @Test
    fun knowsWhetherItChangesThePicturesColours() {
        assertFalse(PhotoEdit().changesColors)
        assertFalse(PhotoEdit(lensCorrection = false).changesColors)
        assertTrue(PhotoEdit(autoEnhance = true).changesColors)
        assertTrue(PhotoEdit(Adjustments(tint = 0.01f)).changesColors)
    }

    private val elaborate = PhotoEdit(
        Adjustments(whites = 0.2f, blacks = -0.1f, texture = 0.3f, clarity = 0.4f, dehaze = -0.2f, vignette = -0.5f, grain = 0.6f, sharpening = 0.7f, noiseReduction = 0.2f, colorNoiseReduction = 0.3f),
        curves = ToneCurves(
            all = ToneCurve(listOf(CurvePoint(0f, 0.1f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 0.9f))),
            blue = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.8f))),
        ),
        mixer = ColorMixer(
            hue = listOf(0.1f, 0f, 0f, -0.2f, 0f, 0f, 0f, 0f),
            saturation = listOf(0f, 0.3f, 0f, 0f, 0f, -1f, 0f, 0f),
            luminance = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f),
        ),
        grading = ColorGrading(shadows = Tint(210f, 0.4f), midtones = Tint(30f, 0.1f), highlights = Tint(45f, 0.3f), balance = -0.2f),
        monochrome = true,
    )

    @Test
    fun storesCurvesSingleColoursAndTints() {
        assertEquals(elaborate, PhotoEdit.fromJson(elaborate.toJson()))
    }

    @Test
    fun writesNothingForWhatWasLeftAlone() {
        assertEquals("{}", PhotoEdit().toJson())
        assertEquals("""{"exposure":0.5}""", PhotoEdit(Adjustments(exposure = 0.5f)).toJson())
    }

    @Test
    fun readsEditsStoredBeforeTheNewToolsExisted() {
        val stored = """{"exposure":1.0,"contrast":0.0,"highlights":0.0,"shadows":0.0,"temperature":0.0,"tint":0.0,"vibrance":0.0,""" +
            """"saturation":-0.5,"autoEnhance":false,"lensCorrection":true,"quarterTurns":1,"angle":2.5,"cropLeft":0.1,"cropTop":0.0,"cropRight":0.9,"cropBottom":1.0}"""

        assertEquals(
            PhotoEdit(Adjustments(exposure = 1f, saturation = -0.5f), framing = Framing(1, 2.5f, 0.1f, 0f, 0.9f, 1f)),
            PhotoEdit.fromJson(stored),
        )
    }

    @Test
    fun storesMirroringAndPerspective() {
        val edit = PhotoEdit(framing = Framing(1, 2.5f, 0.1f, 0.2f, 0.9f, 0.8f, flipped = true, vertical = 0.3f, horizontal = -0.2f))

        assertEquals(edit, PhotoEdit.fromJson(edit.toJson()))
    }

    @Test
    fun givesItsLookToAnotherPhotoButNotItsFraming() {
        val own = PhotoEdit(Adjustments(exposure = 1f), lensCorrection = false, framing = Framing(quarterTurns = 1))

        val taken = own.withLookOf(elaborate.copy(framing = Framing(angle = 3f)))

        assertEquals(elaborate.copy(lensCorrection = false, framing = Framing(quarterTurns = 1)), taken)
    }

    @Test
    fun aLookCanBeDosed() {
        val half = elaborate.lookAt(0.5f)

        assertEquals(0.1f, half.adjustments.whites, 1e-6f)
        assertEquals(-0.25f, half.adjustments.vignette, 1e-6f)
        assertEquals(CurvePoint(0f, 0.05f), half.curves.all.points.first())
        assertEquals(-0.5f, half.mixer.saturation[5], 1e-6f)
        assertEquals(Tint(210f, 0.2f), half.grading.shadows)
        assertEquals(-0.1f, half.grading.balance, 1e-6f)
        assertTrue(half.monochrome)
        // At no strength at all nothing of the look is left, not even a colour that no longer tints anything.
        assertEquals(PhotoEdit(), elaborate.lookAt(0f))
    }
}
