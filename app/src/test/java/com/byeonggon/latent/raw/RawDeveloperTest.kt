package com.byeonggon.latent.raw

import com.byeonggon.latent.enhance.CameraCurve
import com.byeonggon.latent.enhance.LinearImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class RawDeveloperTest {
    private val curve = SonyArw.toneCurve(intArrayOf(8000, 10400, 12900, 14100))

    /** The stored value that the file's curve expands to (nearly) [sensorValue]. */
    private fun stored(sensorValue: Int) = (0..2047).minBy { kotlin.math.abs(curve[it shl 1] - sensorValue) }

    /** A sensor on which red, green and blue come out equally bright after black level and white balance (2, 1, 1.5). */
    private fun neutral(level: Int) = SonyArw.read(
        SyntheticArw.file(red = stored(512 + level / 2), green = stored(512 + level), blue = stored(512 + level * 2 / 3)),
    )

    /** A 16 by 16 mosaic lit evenly in each colour, read with no black level, white balance or colour change. */
    private fun evenlyLit(red: Int, green: Int, blue: Int, toneExponent: Float = 1f) = ArwImage(
        16, 16, 0, 0, 16, 16, 0, LinearImage.MAX_LEVEL, floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 1f,
        null, 1,
        ShortArray(16 * 16) { at ->
            val (x, y) = at % 16 to at / 16
            (if (x % 2 == 0 && y % 2 == 0) red else if (x % 2 == 1 && y % 2 == 1) blue else green).toShort()
        },
        toneExponent = toneExponent,
    )

    private fun developments(image: ArwImage) = listOf(RawDeveloper.developPreview(image), RawDeveloper.develop(image))

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

    @Test
    fun aCameraWhosePicturesAreDarkerHasItsBrightnessLoweredAndItsColoursKept() {
        for (developed in developments(evenlyLit(4096, 4096, 4096, toneExponent = 1.24f))) {
            val expected = (LinearImage.MAX_LEVEL * (4096.0 / LinearImage.MAX_LEVEL).pow(1.24)).roundToInt()
            for (channel in listOf(developed.red, developed.green, developed.blue)) {
                assertTrue(channel.all { kotlin.math.abs(it - expected) <= 1 })
            }
        }
        for (developed in developments(evenlyLit(6000, 3000, 1500, toneExponent = 1.24f))) {
            val (red, green, blue) = listOf(developed.red, developed.green, developed.blue).map { it.first().toDouble() }
            assertTrue("darker, not $green", green < 2200)
            assertEquals(2.0, red / green, 0.002)
            assertEquals(0.5, blue / green, 0.002)
        }
        for (level in listOf(0, LinearImage.MAX_LEVEL)) {
            for (developed in developments(evenlyLit(level, level, level, toneExponent = 1.24f))) {
                assertTrue(developed.green.all { it.toInt() == level })
            }
        }
    }

    @Test
    fun anyOtherCameraKeepsItsBrightnessAsTheSensorRecordedIt() {
        for (developed in developments(evenlyLit(6000, 3000, 1500))) {
            assertEquals(listOf(6000, 3000, 1500), listOf(developed.red, developed.green, developed.blue).map { it.first().toInt() })
        }
    }
}
