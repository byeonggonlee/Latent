package com.byeonggon.cameramanager.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.byeonggon.cameramanager.enhance.Framing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropLayoutTest {
    // A picture of 300 by 200 on a canvas of 640 by 440 with a margin of 20: drawn at twice its size, from (20, 20).
    private fun layout(framing: Framing = Framing()) = CropLayout(Size(640f, 440f), 20f, framing, 300f, 200f)

    private val all = Grip(left = true, top = true, right = true, bottom = true)

    @Test
    fun fitsTheWholePictureInsideTheMargin() {
        val layout = layout()

        assertEquals(2f, layout.scale, 1e-4f)
        assertEquals(listOf(20f, 20f, 620f, 420f), layout.kept.run { listOf(left, top, right, bottom) })

        // On its side the picture is 200 wide and 300 high, and the height is what limits it.
        val sideways = layout(Framing(quarterTurns = 1))
        assertEquals(400f / 300f, sideways.scale, 1e-4f)
        assertEquals(400f, sideways.kept.height, 1e-2f)

        // Levelled, its corners stick out and it has to be drawn smaller.
        assertTrue(layout(Framing(angle = 10f).shrunkToFit(300f, 200f)).scale < 2f)
    }

    @Test
    fun knowsWhereThePicturesCornersAreSeen() {
        // Top left, top right, bottom right, bottom left of the stored picture.
        assertEquals(listOf(20f, 20f, 620f, 20f, 620f, 420f, 20f, 420f), layout().corners.toList())
        // Mirrored, its top left corner is seen at the top right.
        assertEquals(listOf(620f, 20f, 20f, 20f), layout(Framing().mirrored()).corners.toList().take(4))
        // A quarter turn clockwise takes it to the top right as well, with the picture standing on its side.
        val sideways = layout(Framing(quarterTurns = 1)).corners
        assertEquals(320f + 400f / 3, sideways[0], 0.01f)
        assertEquals(20f, sideways[1], 0.01f)
        // Tilted, the bottom of the picture is seen wider than the top.
        val tilted = layout(Framing(vertical = 0.5f)).corners
        assertTrue(tilted[4] - tilted[6] > (tilted[2] - tilted[0]) * 1.3f)
    }

    @Test
    fun placesTheKeptRectangleOnThePicture() {
        val kept = layout(Framing(left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1f)).kept

        assertEquals(listOf(170f, 220f, 470f, 420f), listOf(kept.left, kept.top, kept.right, kept.bottom))
    }

    @Test
    fun takesHoldOfWhatIsUnderTheFinger() {
        val layout = layout(Framing(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)) // 170..470 by 120..320

        assertEquals(Grip(left = true, top = true, right = false, bottom = false), layout.gripAt(Offset(160f, 130f), 30f, keepShape = false))
        assertEquals(Grip(left = false, top = false, right = true, bottom = true), layout.gripAt(Offset(480f, 310f), 30f, keepShape = true))
        assertEquals(Grip(left = false, top = true, right = false, bottom = false), layout.gripAt(Offset(320f, 125f), 30f, keepShape = false))
        assertEquals(all, layout.gripAt(Offset(320f, 220f), 30f, keepShape = false))
        // A side cannot be pulled on its own while the shape is kept; the finger moves the rectangle instead.
        assertEquals(all, layout.gripAt(Offset(320f, 125f), 30f, keepShape = true))
        assertNull(layout.gripAt(Offset(60f, 220f), 30f, keepShape = false))
    }

    @Test
    fun resizesByItsCornersAndSides() {
        val framing = Framing(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
        val layout = layout(framing)

        // 60 canvas pixels are 30 of the picture's 300 across, 40 are 20 of its 200 down.
        val corner = layout.dragged(Grip(left = true, top = true, right = false, bottom = false), Offset(60f, 40f), keepShape = false)
        assertEquals(Framing(left = 0.35f, top = 0.35f, right = 0.75f, bottom = 0.75f), corner.rounded())

        val side = layout.dragged(Grip(left = false, top = false, right = true, bottom = false), Offset(-60f, 500f), keepShape = false)
        assertEquals(Framing(left = 0.25f, top = 0.25f, right = 0.65f, bottom = 0.75f), side.rounded())
    }

    @Test
    fun keepsItsShapeWhenAsked() {
        val framing = Framing(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)

        val smaller = layout(framing).dragged(Grip(left = false, top = false, right = true, bottom = true), Offset(-60f, -4f), keepShape = true)

        assertEquals(Framing(left = 0.25f, top = 0.25f, right = 0.65f, bottom = 0.65f), smaller.rounded())
    }

    @Test
    fun stopsAtTheEdgeOfThePictureAndAtASmallestSize() {
        val framing = Framing(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
        val layout = layout(framing)

        val moved = layout.dragged(all, Offset(1000f, 0f), keepShape = false)
        assertEquals(1f, moved.right, 1e-3f)
        assertEquals(0.5f, moved.width, 1e-3f)

        val grown = layout.dragged(Grip(left = true, top = true, right = false, bottom = false), Offset(-1000f, -1000f), keepShape = false)
        assertEquals(0f, grown.left, 1e-3f)
        assertEquals(0f, grown.top, 1e-3f)

        val squeezed = layout.dragged(Grip(left = true, top = false, right = false, bottom = false), Offset(1000f, 0f), keepShape = false)
        assertTrue(squeezed.width in 0.05f..0.1f)
        assertEquals(0.75f, squeezed.right, 1e-6f)
    }

    private fun Framing.rounded() = copy(
        left = Math.round(left * 1000) / 1000f,
        top = Math.round(top * 1000) / 1000f,
        right = Math.round(right * 1000) / 1000f,
        bottom = Math.round(bottom * 1000) / 1000f,
    )
}
