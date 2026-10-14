package com.byeonggon.cameramanager.raw

import com.byeonggon.cameramanager.enhance.LinearImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LensCorrectionTest {
    private val width = 240
    private val height = 160

    /** Gray, with one brighter dot whose centre can be found again afterwards. */
    private fun pictureWithDot(dotX: Int, dotY: Int): LinearImage {
        val levels = ShortArray(width * height) { index ->
            val distance = hypot((index % width - dotX).toDouble(), (index / width - dotY).toDouble())
            if (distance < 4) 12000 else 2000
        }
        return LinearImage(width, height, levels.copyOf(), levels.copyOf(), levels.copyOf())
    }

    private fun dotDistanceFromCentre(image: LinearImage): Double {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        image.green.forEachIndexed { index, level ->
            if (level > 7000) {
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
        val before = picture.green.copyOf()

        LensCorrection(profile(IntArray(11)), width, height).straighten(picture)

        assertArrayEquals(before, picture.green)
        assertArrayEquals(before, picture.red)
        assertArrayEquals(before, picture.blue)
    }

    @Test
    fun aLensThatOnlyMagnifiesNeedsNoStraightening() {
        // The same stretch at every distance is a plain zoom, which the correction undoes to keep the edges.
        val picture = pictureWithDot(180, 110)
        val before = picture.green.copyOf()

        LensCorrection(profile(IntArray(11) { 300 }), width, height).straighten(picture)

        assertArrayEquals(before, picture.green)
    }

    @Test
    fun movesDetailOutwardsWhereTheLensSqueezedItTowardsTheCentre() {
        // The profile of a real zoom lens at its wide end: squeezed in the middle distances, stretched in the corners.
        val lens = LensCorrection(profile(SyntheticArw.DISTORTION), width, height)
        val picture = pictureWithDot(180, 110)

        val before = dotDistanceFromCentre(picture)
        lens.straighten(picture)
        val after = dotDistanceFromCentre(picture)

        assertTrue("moved from $before to $after", after > before + 1.0 && after < before + 5.0)
    }

    @Test
    fun movesRedAgainstGreenWhereTheLensSeparatedTheColours() {
        val fringed = LensProfile(IntArray(11), redAberration = IntArray(11) { 30000 }, blueAberration = IntArray(11))
        val picture = pictureWithDot(200, 130)

        LensCorrection(fringed, width, height).straighten(picture)

        // The dot was drawn with all colours in one place, so pulling red into place leaves a fringe here.
        val fringes = picture.red.indices.count { picture.red[it] != picture.green[it] }
        assertTrue("$fringes pixels with red off green", fringes > 10)
        assertArrayEquals(picture.green, picture.blue)
    }
}
