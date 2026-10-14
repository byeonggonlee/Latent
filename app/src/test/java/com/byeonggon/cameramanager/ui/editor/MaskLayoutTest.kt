package com.byeonggon.cameramanager.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.byeonggon.cameramanager.enhance.Mask
import com.byeonggon.cameramanager.enhance.MaskShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaskLayoutTest {
    // A picture of 300 by 200 on a canvas of 640 by 640 with a margin of 20: drawn 600 wide, from (20, 120).
    private val layout = MaskLayout(Size(640f, 640f), 20f, 300f, 200f)
    private val linear = Mask(MaskShape.LINEAR, startX = 0.5f, startY = 0.25f, endX = 0.5f, endY = 0.75f)
    private val radial = Mask(MaskShape.RADIAL, startX = 0.5f, startY = 0.5f, endX = 0.75f, endY = 0.75f)

    @Test
    fun fitsThePictureIntoTheCanvas() {
        assertEquals(listOf(20f, 120f, 620f, 520f), layout.picture.run { listOf(left, top, right, bottom) })
        assertEquals(Offset(320f, 220f), layout.startOf(linear))
        assertEquals(Offset(320f, 420f), layout.endOf(linear))
    }

    @Test
    fun takesHoldOfTheNearerOfAMasksTwoPoints() {
        assertEquals(MaskGrip.START, layout.gripAt(linear, Offset(330f, 230f), reach = 40f))
        assertEquals(MaskGrip.END, layout.gripAt(linear, Offset(300f, 410f), reach = 40f))
        assertNull(layout.gripAt(linear, Offset(100f, 320f), reach = 40f))
        // Close together, the points are still told apart by which is nearer.
        val short = linear.copy(endY = 0.3f)
        assertEquals(MaskGrip.END, layout.gripAt(short, Offset(320f, 245f), reach = 40f))
    }

    @Test
    fun movesTheEndsOfALineOneAtATime() {
        // 60 canvas pixels are a tenth of the picture across, 40 are a tenth of it down.
        assertEquals(linear.copy(startX = 0.6f, startY = 0.35f), layout.dragged(linear, MaskGrip.START, Offset(60f, 40f)).rounded())
        assertEquals(linear.copy(endX = 0.4f, endY = 0.65f), layout.dragged(linear, MaskGrip.END, Offset(-60f, -40f)).rounded())
    }

    @Test
    fun movesAnEllipseByItsCentreAndSizesItByItsEdge() {
        val moved = layout.dragged(radial, MaskGrip.START, Offset(60f, 40f)).rounded()
        assertEquals(radial.copy(startX = 0.6f, startY = 0.6f, endX = 0.85f, endY = 0.85f), moved)

        val wider = layout.dragged(radial, MaskGrip.END, Offset(60f, 0f)).rounded()
        assertEquals(radial.copy(endX = 0.85f), wider)
    }

    @Test
    fun letsAMaskReachOnlySoFarOutOfThePicture() {
        val far = layout.dragged(linear, MaskGrip.END, Offset(0f, 5000f))

        assertEquals(1.5f, far.endY, 1e-6f)
        // An ellipse dragged against that limit keeps its size.
        val pushed = layout.dragged(radial, MaskGrip.START, Offset(5000f, 0f))
        assertEquals(radial.endX - radial.startX, pushed.endX - pushed.startX, 1e-5f)
    }

    private fun Mask.rounded() = copy(
        startX = Math.round(startX * 1000) / 1000f,
        startY = Math.round(startY * 1000) / 1000f,
        endX = Math.round(endX * 1000) / 1000f,
        endY = Math.round(endY * 1000) / 1000f,
    )
}
