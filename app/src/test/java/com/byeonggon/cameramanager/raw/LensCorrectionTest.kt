package com.byeonggon.cameramanager.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LensCorrectionTest {
    private val width = 240
    private val height = 160

    /** Gray, with one brighter dot whose centre can be found again afterwards. */
    private fun pictureWithDot(dotX: Int, dotY: Int): DevelopedImage {
        val pixels = IntArray(width * height) { index ->
            val distance = hypot((index % width - dotX).toDouble(), (index / width - dotY).toDouble())
            val level = if (distance < 4) 250 else 60
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }
        return DevelopedImage(width, height, pixels)
    }

    private fun dotDistanceFromCentre(image: DevelopedImage): Double {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        image.pixels.forEachIndexed { index, pixel ->
            if ((pixel shr 8) and 0xFF > 150) {
                sumX += index % width
                sumY += index / width
                count++
            }
        }
        return hypot(sumX / count - (width - 1) / 2.0, sumY / count - (height - 1) / 2.0)
    }

    private fun profile(distortion: IntArray) = LensProfile(distortion, IntArray(distortion.size), IntArray(distortion.size))

    @Test
    fun leavesThePictureAloneWhenTheLensNeedsNoCorrection() {
        val picture = pictureWithDot(180, 110)

        val straightened = LensCorrection(profile(IntArray(11)), width, height).straighten(picture)

        assertArrayEquals(picture.pixels, straightened.pixels)
    }

    @Test
    fun aLensThatOnlyMagnifiesNeedsNoStraightening() {
        // The same stretch at every distance is a plain zoom, which the correction undoes to keep the edges.
        val picture = pictureWithDot(180, 110)

        val straightened = LensCorrection(profile(IntArray(11) { 300 }), width, height).straighten(picture)

        assertArrayEquals(picture.pixels, straightened.pixels)
    }

    @Test
    fun movesDetailOutwardsWhereTheLensSqueezedItTowardsTheCentre() {
        // The profile of a real zoom lens at its wide end: squeezed in the middle distances, stretched in the corners.
        val lens = LensCorrection(profile(SyntheticArw.DISTORTION), width, height)
        val picture = pictureWithDot(180, 110)

        val before = dotDistanceFromCentre(picture)
        val after = dotDistanceFromCentre(lens.straighten(picture))

        assertTrue("moved from $before to $after", after > before + 1.0 && after < before + 5.0)
    }

    @Test
    fun movesRedAgainstGreenWhereTheLensSeparatedTheColours() {
        val fringed = LensProfile(IntArray(11), redAberration = IntArray(11) { 30000 }, blueAberration = IntArray(11))
        val picture = pictureWithDot(200, 130)

        val straightened = LensCorrection(fringed, width, height).straighten(picture)

        // The dot was drawn with all colours in one place, so pulling red into place leaves a fringe here.
        val fringes = straightened.pixels.count { (it shr 16) and 0xFF != (it shr 8) and 0xFF }
        val blueStillOnGreen = straightened.pixels.all { (it shr 8) and 0xFF == it and 0xFF }
        assertTrue("$fringes pixels with red off green", fringes > 10)
        assertTrue(blueStillOnGreen)
    }
}
