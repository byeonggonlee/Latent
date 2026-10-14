package com.byeonggon.cameramanager.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class HealingTest {
    private val width = 200
    private val height = 150

    /** A gentle gradient with a fine texture, and a dark blemish centred on ([x], [y]). */
    private fun skinWithBlemish(x: Int, y: Int, size: Int = 6): LinearImage {
        val picture = LinearImage(width, height)
        for (row in 0 until height) for (column in 0 until width) {
            val texture = if ((row + column) % 4 == 0) 120 else 0
            val level = 4000 + column * 10 + texture
            val blemish = hypot((column - x).toDouble(), (row - y).toDouble()) < size
            val at = row * width + column
            val value = (if (blemish) level / 3 else level).toShort()
            picture.red[at] = value
            picture.green[at] = value
            picture.blue[at] = value
        }
        return picture
    }

    /** How far the picture around ([x], [y]) is from the blemish-free gradient, at most. */
    private fun worstAround(picture: LinearImage, x: Int, y: Int, reach: Int): Int =
        (y - reach..y + reach).maxOf { row ->
            (x - reach..x + reach).maxOf { column -> abs(picture.green[row * width + column] - (4000 + column * 10 + 60)) }
        }

    @Test
    fun takesABlemishOutAndKeepsTheGradientAroundIt() {
        val picture = skinWithBlemish(100, 75)
        val (fromX, fromY) = Healing.sourceFor(picture, 0.5f, 0.5f, radius = 0.05f)

        Healing.heal(picture, listOf(Spot(0.5f, 0.5f, 0.05f, fromX, fromY)))

        // Within the texture's own swing of its local average: the dark blob is gone and the brightness matches.
        assertTrue("off by ${worstAround(picture, 100, 75, 5)}", worstAround(picture, 100, 75, 5) < 250)
        // Beyond the spot nothing changed.
        assertEquals(4000 + 20 * 10, picture.green[75 * width + 20].toInt())
    }

    @Test
    fun takesThePatchFromNearbyAndFromWithinThePicture() {
        val picture = skinWithBlemish(100, 75)

        val (fromX, fromY) = Healing.sourceFor(picture, 0.5f, 0.5f, radius = 0.05f)

        val distance = hypot((fromX - 0.5f) * width.toDouble(), (fromY - 0.5f) * height.toDouble())
        assertTrue("$distance pixels away", distance in 15.0..50.0)
        assertTrue(fromX in 0f..1f && fromY in 0f..1f)
    }

    @Test
    fun evensOutAPatchFromABrighterPlace() {
        val picture = skinWithBlemish(60, 75)
        // Patched from far to the right, where the gradient is much brighter; the spot reaches well beyond the blemish, as it should.
        Healing.heal(picture, listOf(Spot(0.3f, 0.5f, 0.05f, 0.8f, 0.5f)))

        assertTrue("off by ${worstAround(picture, 60, 75, 4)}", worstAround(picture, 60, 75, 4) < 250)
    }

    @Test
    fun findsTheBlemishInAPictureStoredOnItsSide() {
        // Stored on its side: the upright picture's point (0.3, 0.5) is stored at (0.5, 0.7).
        val stored = LinearImage(150, 200)
        for (row in 0 until 200) for (column in 0 until 150) {
            val blemish = hypot((column - 75).toDouble(), (row - 140).toDouble()) < 5
            val value = (if (blemish) 1000 else 5000).toShort()
            stored.red[row * 150 + column] = value
            stored.green[row * 150 + column] = value
            stored.blue[row * 150 + column] = value
        }

        Healing.heal(stored, listOf(Spot(0.3f, 0.5f, 0.04f, 0.6f, 0.5f)), uprightTurns = 1)

        assertEquals(5000, stored.green[140 * 150 + 75].toInt())
    }

    @Test
    fun isStoredWithTheEditButStaysWithItsPhoto() {
        val edit = PhotoEdit(spots = listOf(Spot(0.2f, 0.3f, 0.02f, 0.25f, 0.35f), Spot(0.6f, 0.6f, 0.01f, 0.55f, 0.6f)))

        assertEquals(edit, PhotoEdit.fromJson(edit.toJson()))
        assertTrue(edit.changesColors)
        assertEquals(edit.spots, edit.withLookOf(PhotoEdit(Adjustments(exposure = 1f))).spots)
        assertEquals(emptyList<Spot>(), PhotoEdit().withLookOf(edit).spots)
    }
}
