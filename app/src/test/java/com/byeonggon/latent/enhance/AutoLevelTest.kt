package com.byeonggon.latent.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

class AutoLevelTest {
    /** Squares of 24 pixels, the whole pattern turned clockwise by [degrees] about the middle of the picture. */
    private fun checkerboard(degrees: Double, width: Int = 480, height: Int = 320): LinearImage {
        val picture = LinearImage(width, height)
        val cos = cos(Math.toRadians(degrees))
        val sin = sin(Math.toRadians(degrees))
        for (y in 0 until height) for (x in 0 until width) {
            var lit = 0
            // Several samples in a pixel keep the edges smooth, as a photograph's are.
            for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                val px = x + (sx + 0.5) / SAMPLES - width / 2.0
                val py = y + (sy + 0.5) / SAMPLES - height / 2.0
                val u = px * cos + py * sin
                val v = -px * sin + py * cos
                if ((floor(u / 24).toInt() + floor(v / 24).toInt()) % 2 == 0) lit++
            }
            val level = (MAX + (lit / (SAMPLES * SAMPLES).toFloat()) * (MAX * 8)).toInt().toShort()
            picture.red[y * width + x] = level
            picture.green[y * width + x] = level
            picture.blue[y * width + x] = level
        }
        return picture
    }

    private val MAX = 800
    private val SAMPLES = 8

    @Test
    fun findsHowFarAPictureOfStraightLinesLeans() {
        for (lean in listOf(0.0, 3.0, -6.0, 11.0, -2.5)) {
            val found = AutoLevel.tiltOf(checkerboard(lean))
            assertNotNull("lean $lean", found)
            assertEquals("lean $lean", lean, found!!.toDouble(), 0.3)
        }
    }

    @Test
    fun levellingByTheTiltLevelsThePicture() {
        val leaning = checkerboard(4.0)
        val tilt = AutoLevel.tiltOf(leaning)!!
        // Turning the picture counter-clockwise by what it leans clockwise: the framing's angle is clockwise.
        val framing = Framing(angle = -tilt).shrunkToFit(leaning.width.toFloat(), leaning.height.toFloat())
        val levelled = leaning.framed(framing)
        assertEquals(0.0, AutoLevel.tiltOf(levelled)!!.toDouble(), 0.5)
    }

    @Test
    fun givesNoAnswerWithoutAClearDirection() {
        val random = Random(7)
        val noise = LinearImage(480, 320)
        for (i in noise.red.indices) {
            val level = (200 + random.nextInt(600)).toShort()
            noise.red[i] = level
            noise.green[i] = level
            noise.blue[i] = level
        }
        assertNull(AutoLevel.tiltOf(noise))
        // A pattern turned by more than the window allows is left to the person.
        assertNull(AutoLevel.tiltOf(checkerboard(30.0)))
        assertNull(AutoLevel.tiltOf(LinearImage(480, 320)))
    }
}
