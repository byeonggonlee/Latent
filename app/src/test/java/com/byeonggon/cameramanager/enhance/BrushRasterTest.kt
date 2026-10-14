package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushRasterTest {
    private fun brush(vararg strokes: BrushStroke, feather: Float = 0.3f) =
        Mask(MaskShape.BRUSH, 0f, 0f, 0f, 0f, feather = feather, strokes = strokes.toList())

    /** Across the middle of the picture, from a fifth of the way to four fifths. */
    private val across = BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f), radius = 0.05f)

    @Test
    fun coversWhereTheBrushWentAndNothingElse() {
        val mask = brush(across)

        assertEquals(1f, mask.weightAt(0.5f, 0.5f, 300f, 200f), 1e-3f)
        assertEquals(1f, mask.weightAt(0.25f, 0.52f, 300f, 200f), 1e-3f)
        assertEquals(0f, mask.weightAt(0.5f, 0.9f, 300f, 200f), 0f)
        assertEquals(0f, mask.weightAt(0.95f, 0.5f, 300f, 200f), 0f)
        // At its edge the stroke fades out.
        val edge = mask.weightAt(0.5f, 0.5f + 0.045f * 300f / 200f, 300f, 200f)
        assertTrue("$edge", edge in 0.05f..0.95f)
        assertEquals(1f, mask.copy(inverted = true).weightAt(0.5f, 0.9f, 300f, 200f), 0f)
    }

    @Test
    fun anErasingStrokeTakesAwayWhatWasPainted() {
        val mask = brush(across, BrushStroke(listOf(0.5f, 0.3f, 0.5f, 0.7f), radius = 0.03f, erasing = true))

        assertEquals(0f, mask.weightAt(0.5f, 0.5f, 300f, 200f), 1e-3f)
        assertEquals(1f, mask.weightAt(0.3f, 0.5f, 300f, 200f), 1e-3f)
    }

    @Test
    fun aDabIsRoundOnThePictureWhateverItsShape() {
        val dab = brush(BrushStroke(listOf(0.5f, 0.5f), radius = 0.1f), feather = 0f)

        // On a picture twice as wide as high, a tenth of the long side is a fifth of the height.
        assertEquals(1f, dab.weightAt(0.5f, 0.68f, 400f, 200f), 1e-3f)
        assertEquals(0f, dab.weightAt(0.5f, 0.72f, 400f, 200f), 1e-3f)
        assertEquals(1f, dab.weightAt(0.59f, 0.5f, 400f, 200f), 1e-3f)
        assertEquals(0f, dab.weightAt(0.61f, 0.5f, 400f, 200f), 1e-3f)
    }

    @Test
    fun brightensOnlyWhatWasPainted() {
        val flat = LinearImage.fromArgb(IntArray(120 * 80) { (0xFF shl 24) or 0x646464 }, 120, 80)
        val edit = PhotoEdit(locals = listOf(LocalEdit(brush(across), Adjustments(exposure = 1f))))

        val rendered = flat.render(edit)

        assertTrue((rendered[40 * 120 + 60] ushr 8 and 0xFF) > 130)
        assertEquals(100, rendered[5 * 120 + 60] ushr 8 and 0xFF)
        assertEquals(edit, PhotoEdit.fromJson(edit.toJson()))
    }
}
