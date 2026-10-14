package com.byeonggon.cameramanager.raw

import com.byeonggon.cameramanager.enhance.CameraCurve
import com.byeonggon.cameramanager.enhance.LinearImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDeveloperTest {
    private val curve = SonyArw.toneCurve(intArrayOf(8000, 10400, 12900, 14100))

    /** The stored value that the file's curve expands to (nearly) [sensorValue]. */
    private fun stored(sensorValue: Int) = (0..2047).minBy { kotlin.math.abs(curve[it shl 1] - sensorValue) }

    /** A sensor on which red, green and blue come out equally bright after black level and white balance (2, 1, 1.5). */
    private fun neutral(level: Int) = SonyArw.read(
        SyntheticArw.file(red = stored(512 + level / 2), green = stored(512 + level), blue = stored(512 + level * 2 / 3)),
    )

    /** How bright a level is shown, from 0 to 255. */
    private fun shown(level: Short) = CameraCurve.displayOfLevel[level.toInt()] * 255.0

    @Test
    fun developsTheCroppedAreaAtAQuarterAtHalfAndAtFullSize() {
        val image = neutral(3000)

        assertEquals(14 to 2, RawDeveloper.developPreview(image, cells = 2).let { it.width to it.height })
        assertEquals(28 to 4, RawDeveloper.developPreview(image).let { it.width to it.height })
        assertEquals(56 to 8, RawDeveloper.develop(image).let { it.width to it.height })
    }

    @Test
    fun developsAnEvenlyLitNeutralSceneAsOneShadeOfGray() {
        val image = neutral(3000)

        val developments = listOf(RawDeveloper.developPreview(image, cells = 2), RawDeveloper.developPreview(image), RawDeveloper.develop(image))
        for (developed in developments) {
            assertTrue(developed.red.all { it == developed.red.first() })
            assertTrue(developed.green.all { it == developed.green.first() })
            assertTrue(developed.blue.all { it == developed.blue.first() })
            // The stored values are coarse in the highlights, hence the small tolerance.
            val gray = shown(developed.green.first())
            assertEquals(gray, shown(developed.red.first()), 3.0)
            assertEquals(gray, shown(developed.blue.first()), 3.0)
            assertTrue("a mid-tone, not $gray", gray in 60.0..230.0)
        }
    }

    @Test
    fun developsABrighterSceneBrighter() {
        val darker = RawDeveloper.develop(neutral(1500)).green.first()
        val brighter = RawDeveloper.develop(neutral(6000)).green.first()

        assertTrue(brighter > darker)
    }

    @Test
    fun turnsBlownHighlightsNeutralWhite() {
        // Every photosite at the top of its range, whatever its colour.
        val image = SonyArw.read(SyntheticArw.file(red = 2047, green = 2047, blue = 2047))

        val developed = RawDeveloper.develop(image)

        val white = LinearImage.MAX_LEVEL.toShort()
        assertEquals(listOf(white, white, white), listOf(developed.red.first(), developed.green.first(), developed.blue.first()))
    }
}
