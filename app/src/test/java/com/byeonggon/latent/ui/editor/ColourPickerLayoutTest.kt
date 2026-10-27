package com.byeonggon.latent.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ColourPickerLayoutTest {
    @Test fun mapsOnlyTheFittedWholePhotoAndIncludesItsEdges() {
        val layout = ColourPickerLayout(Size(640f, 640f), 20f, 300f, 200f)
        assertNull(layout.pointAt(Offset(10f, 320f)))
        assertNull(layout.pointAt(Offset(320f, 100f)))
        assertEquals(0f to 0f, layout.pointAt(Offset(20f, 120f)))
        assertEquals(1f to 1f, layout.pointAt(Offset(620f, 520f)))
        assertEquals(0.5f to 0.5f, layout.pointAt(Offset(320f, 320f)))
        assertNull(ColourPickerLayout(Size(10f, 10f), 20f, 300f, 200f).pointAt(Offset(5f, 5f)))
    }

    @Test fun normalisedPointsRemainOnTheSamePlaceAfterResizing() {
        val wide = ColourPickerLayout(Size(800f, 400f), 20f, 200f, 300f)
        val tall = ColourPickerLayout(Size(400f, 800f), 20f, 200f, 300f)
        for (layout in listOf(wide, tall)) {
            val p = layout.onCanvas(0.25f, 0.75f)
            val actual = layout.pointAt(p)!!
            assertEquals(0.25f, actual.first, 1e-6f)
            assertEquals(0.75f, actual.second, 1e-6f)
        }
    }

    @Test fun theMagnifierStaysInsideThePhotoWithoutCoveringTheSample() {
        val layout = ColourPickerLayout(Size(640f, 640f), 20f, 300f, 200f)
        for (p in listOf(0f to 0f, 1f to 1f, 0.5f to 0.5f, 0.1f to 0.9f)) {
            val rect = layout.magnifierAt(p, 96f, 20f)!!
            assertTrue(rect.left >= layout.picture.left && rect.right <= layout.picture.right)
            assertTrue(rect.top >= layout.picture.top && rect.bottom <= layout.picture.bottom)
            assertFalse(rect.contains(layout.onCanvas(p.first, p.second)))
        }
    }
}
