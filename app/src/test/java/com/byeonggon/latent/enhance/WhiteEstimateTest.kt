package com.byeonggon.latent.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

class WhiteEstimateTest {
    /**
     * A scene of patches in [colours] (linear, 0 to 1), each lit by a light of [temperature] and [tint] as the
     * sliders would undo them: what the camera would record with its white balance that far off.
     */
    private fun scene(colours: List<Triple<Double, Double, Double>>, temperature: Double = 0.0, tint: Double = 0.0, seed: Long = 7): LinearImage {
        val width = 160
        val height = 120
        val random = Random(seed)
        val patches = Array(8 * 6) { colours[random.nextInt(colours.size)] to 0.4 + random.nextDouble() * 0.6 }
        val red = 2.0.pow(-ToneRenderer.TEMPERATURE_STOPS * temperature)
        val green = 2.0.pow(ToneRenderer.TINT_STOPS * tint)
        val blue = 2.0.pow(ToneRenderer.TEMPERATURE_STOPS * temperature)
        val image = LinearImage(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (colour, light) = patches[(y / 20) * 8 + x / 20]
                // A little grain, so that there are edges inside the patches too.
                val grain = 1 + (random.nextDouble() - 0.5) * 0.04
                fun level(value: Double) = (value * light * grain * LinearImage.MAX_LEVEL).roundToInt().coerceIn(0, LinearImage.MAX_LEVEL).toShort()
                val index = y * width + x
                image.red[index] = level(colour.first * red)
                image.green[index] = level(colour.second * green)
                image.blue[index] = level(colour.third * blue)
            }
        }
        return image
    }

    private val grays = listOf(Triple(0.6, 0.6, 0.6), Triple(0.3, 0.3, 0.3), Triple(0.12, 0.12, 0.12))
    private val everyday = grays + listOf(Triple(0.5, 0.25, 0.15), Triple(0.15, 0.35, 0.2), Triple(0.2, 0.25, 0.5), Triple(0.55, 0.5, 0.2))

    @Test
    fun leavesAPictureTheCameraBalancedRightAsItIs() {
        assertEquals(0f to 0f, WhiteEstimate.nudge(scene(grays)))
    }

    @Test
    fun turnsTheEstimatesIntoTheSlidersThatUndoTheLight() {
        val light = doubleArrayOf(2.0.pow(-0.15), 2.0.pow(0.35 * 0.2), 2.0.pow(0.15))
        // A light that a temperature of 0.3 and a tint of 0.2 put right.
        assertEquals(0.3f, WhiteEstimate.temperatureFor(light), 1e-4f)
        assertEquals(0.2f, WhiteEstimate.tintFor(light), 1e-4f)
    }

    @Test
    fun warmsABluishPictureALittleWhereAllThreeAgree() {
        val slight = WhiteEstimate.nudge(scene(everyday, temperature = 0.1))
        val strong = WhiteEstimate.nudge(scene(everyday, temperature = 0.8))

        assertTrue("$slight", slight.first > 0f && slight.first < 0.1f)
        // Half the way, never more than a little.
        assertEquals(0.1f, strong.first, 1e-6f)
        assertEquals(0f, strong.second, 0.02f)
    }

    @Test
    fun keepsNearlyAllOfALampLitRoom() {
        // Asked to cool it by 3, the most Auto takes is a little.
        val (temperature, _) = WhiteEstimate.nudge(scene(everyday, temperature = -3.0))

        assertEquals(-0.1f, temperature, 1e-6f)
    }

    @Test
    fun leavesAPictureAloneWhereTheWaysDisagree() {
        // Orange everywhere but for a sign of a cool white: the average calls for cooling, the white for warming.
        val image = scene(listOf(Triple(0.6, 0.3, 0.1), Triple(0.4, 0.2, 0.07)))
        for (y in 40 until 80) {
            for (x in 40 until 100) {
                val index = y * image.width + x
                image.red[index] = (0.86 * LinearImage.MAX_LEVEL).toInt().toShort()
                image.green[index] = (0.88 * LinearImage.MAX_LEVEL).toInt().toShort()
                image.blue[index] = (0.92 * LinearImage.MAX_LEVEL).toInt().toShort()
            }
        }
        val estimates = WhiteEstimate.of(image)!!
        assertTrue(WhiteEstimate.temperatureFor(estimates.grayWorld) < -0.5f)
        assertTrue(WhiteEstimate.temperatureFor(estimates.whitePatch) > 0.05f)

        assertEquals(0f to 0f, WhiteEstimate.nudge(image))
    }

    @Test
    fun judgesNothingByAPictureMostlyClippedOrBlack() {
        val image = LinearImage(40, 30)
        image.red.fill(LinearImage.MAX_LEVEL.toShort())
        assertNull(WhiteEstimate.of(image))
        assertEquals(0f to 0f, WhiteEstimate.nudge(image))
    }
}
