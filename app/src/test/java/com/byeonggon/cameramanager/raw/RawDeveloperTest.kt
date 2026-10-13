package com.byeonggon.cameramanager.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDeveloperTest {
    private fun red(pixel: Int) = (pixel shr 16) and 0xFF
    private fun green(pixel: Int) = (pixel shr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF

    private val curve = SonyArw.toneCurve(intArrayOf(8000, 10400, 12900, 14100))

    /** The stored value that the file's curve expands to (nearly) [sensorValue]. */
    private fun stored(sensorValue: Int) = (0..2047).minBy { kotlin.math.abs(curve[it shl 1] - sensorValue) }

    /** A sensor on which red, green and blue come out equally bright after black level and white balance (2, 1, 1.5). */
    private fun neutral(level: Int) = SonyArw.read(
        SyntheticArw.file(red = stored(512 + level / 2), green = stored(512 + level), blue = stored(512 + level * 2 / 3)),
    )

    @Test
    fun developsTheCroppedAreaAtHalfAndAtFullSize() {
        val image = neutral(3000)

        assertEquals(28 to 4, RawDeveloper.developPreview(image).let { it.width to it.height })
        assertEquals(56 to 8, RawDeveloper.develop(image).let { it.width to it.height })
    }

    @Test
    fun rendersAnEvenlyLitNeutralSceneAsOneShadeOfGray() {
        val image = neutral(3000)

        for (developed in listOf(RawDeveloper.developPreview(image), RawDeveloper.develop(image))) {
            val first = developed.pixels.first()
            assertTrue(developed.pixels.all { it == first })
            // The stored values are coarse in the highlights, hence the small tolerance.
            assertEquals(green(first).toDouble(), red(first).toDouble(), 3.0)
            assertEquals(green(first).toDouble(), blue(first).toDouble(), 3.0)
            assertTrue("a mid-tone, not ${green(first)}", green(first) in 60..230)
        }
    }

    @Test
    fun rendersABrighterSceneBrighter() {
        val darker = RawDeveloper.develop(neutral(1500)).pixels.first()
        val brighter = RawDeveloper.develop(neutral(6000)).pixels.first()

        assertTrue(green(brighter) > green(darker))
    }

    @Test
    fun turnsBlownHighlightsNeutralWhite() {
        // Every photosite at the top of its range, whatever its colour.
        val image = SonyArw.read(SyntheticArw.file(red = 2047, green = 2047, blue = 2047))

        val pixel = RawDeveloper.develop(image).pixels.first()

        assertEquals(listOf(255, 255, 255), listOf(red(pixel), green(pixel), blue(pixel)))
    }
}
