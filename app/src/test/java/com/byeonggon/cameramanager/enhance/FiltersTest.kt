package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    /** A picture with dark and bright tones and a few colours, for a filter to act on. */
    private val picture = LinearImage.fromArgb(
        IntArray(120 * 80) { index ->
            val x = index % 120
            val y = index / 80
            rgb(40 + x, 60 + (y * 2) % 150, 200 - x)
        },
        120,
        80,
    )

    @Test
    fun everyFilterCanBeToldFromTheOthers() {
        assertEquals(Filters.BUILT_IN.size, Filters.BUILT_IN.map { it.id }.toSet().size)
        assertEquals(Filters.BUILT_IN.size, Filters.BUILT_IN.map { it.name }.toSet().size)
        assertEquals(Filters.BUILT_IN.size, Filters.BUILT_IN.map { it.look }.toSet().size)
        assertTrue(Filters.BUILT_IN.none { it.custom })
    }

    @Test
    fun everyFilterChangesThePictureAndNothingButItsLook() {
        val original = picture.render(PhotoEdit())
        for (filter in Filters.BUILT_IN) {
            assertTrue(filter.name, filter.look.changesColors)
            assertEquals(filter.name, Framing(), filter.look.framing)
            assertTrue(filter.name, filter.look.lensCorrection)
            assertFalse(filter.name, picture.render(filter.look).contentEquals(original))
        }
    }

    @Test
    fun everyFilterSurvivesBeingStoredAndFadesToNothing() {
        for (filter in Filters.BUILT_IN) {
            assertEquals(filter.name, filter.look, PhotoEdit.fromJson(filter.look.toJson()))
            assertEquals(filter.name, PhotoEdit(), filter.look.lookAt(0f))
            assertEquals(filter.name, filter.look, filter.look.lookAt(1f))
        }
    }
}
