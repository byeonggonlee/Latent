package com.byeonggon.latent.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadEntryTest {
    @Test
    fun readsANumberInTheUnitsTheSliderShows() {
        // A percentage on a slider that goes from -1 to 1.
        assertEquals(0.25f, readEntry("25", 0.01f, -1f..1f)!!, 1e-6f)
        assertEquals(-0.12f, readEntry("-12", 0.01f, -1f..1f)!!, 1e-6f)
        assertEquals(0.4f, readEntry("+40", 0.01f, -1f..1f)!!, 1e-6f)
        // Stops of exposure, typed with a comma as in many languages.
        assertEquals(1.5f, readEntry(" 1,5 ", 1f, -3f..3f)!!, 1e-6f)
        // A typographic minus, as some keyboards offer.
        assertEquals(-0.5f, readEntry("−0.5", 1f, -3f..3f)!!, 1e-6f)
    }

    @Test
    fun keepsAValueWithinWhatTheSliderReaches() {
        assertEquals(1f, readEntry("250", 0.01f, -1f..1f)!!, 1e-6f)
        assertEquals(-3f, readEntry("-9", 1f, -3f..3f)!!, 1e-6f)
        assertEquals(0f, readEntry("-20", 0.01f, 0f..1f)!!, 1e-6f)
    }

    @Test
    fun refusesAnythingThatIsNotANumber() {
        assertNull(readEntry("", 1f, -3f..3f))
        assertNull(readEntry("-", 1f, -3f..3f))
        assertNull(readEntry("one", 1f, -3f..3f))
        assertNull(readEntry("NaN", 1f, -3f..3f))
        assertNull(readEntry("Infinity", 1f, -3f..3f))
    }
}
