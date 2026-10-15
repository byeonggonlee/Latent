package com.byeonggon.latent.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMathTest {
    @Test
    fun placesThePointsOfTheWorldWhereMapTilesHaveThem() {
        assertEquals(0.5, MapMath.worldX(0.0), 1e-9)
        assertEquals(0.5, MapMath.worldY(0.0), 1e-9)
        assertEquals(0.0, MapMath.worldX(-180.0), 1e-9)
        assertEquals(1.0, MapMath.worldX(180.0), 1e-9)
        // The projection's northern and southern limits are the top and the bottom of the map.
        assertEquals(0.0, MapMath.worldY(MapMath.MAX_LATITUDE), 1e-6)
        assertEquals(1.0, MapMath.worldY(-MapMath.MAX_LATITUDE), 1e-6)
    }

    @Test
    fun findsTheTileSeoulIsOn() {
        // Seoul City Hall, at zoom 10, is on tile 873/396 in the usual slippy-map numbering.
        val x = MapMath.worldX(126.9784)
        val y = MapMath.worldY(37.5666)
        assertEquals(873, MapMath.tileOf(x, 10))
        assertEquals(396, MapMath.tileOf(y, 10))
    }

    @Test
    fun givesBackWhatItWasGiven() {
        for ((latitude, longitude) in listOf(37.5666 to 126.9784, -33.86 to 151.21, 64.13 to -21.9, 0.0 to 0.0)) {
            assertEquals(latitude, MapMath.latitudeOf(MapMath.worldY(latitude)), 1e-9)
            assertEquals(longitude, MapMath.longitudeOf(MapMath.worldX(longitude)), 1e-9)
        }
    }

    @Test
    fun keepsTilesWithinTheWorld() {
        assertEquals(0, MapMath.tileOf(-0.1, 3))
        assertEquals(7, MapMath.tileOf(1.0, 3))
        assertEquals(1024, MapMath.tilesAcross(10))
    }

    @Test
    fun groupsPhotosTakenCloseTogetherAndNotThoseFarApart() {
        val world = MapMath.worldSize(12.0, 256.0)
        val points = listOf(
            MapPoint(1, 37.5665, 126.9780), MapPoint(2, 37.5666, 126.9785), MapPoint(3, 37.5667, 126.9790),
            MapPoint(4, 35.1796, 129.0756), // Busan
        )

        val clusters = Clusters.of(points, world, cellPixels = 64.0)

        assertEquals(2, clusters.size)
        val seoul = clusters.single { it.count == 3 }
        assertEquals(listOf(1L, 2L, 3L), seoul.ids)
        assertEquals(37.5666, seoul.latitude, 1e-3)
        assertEquals(126.9785, seoul.longitude, 1e-3)
        assertEquals(listOf(4L), clusters.single { it.count == 1 }.ids)
    }

    @Test
    fun separatesThemAgainWhenZoomedIn() {
        val points = listOf(MapPoint(1, 37.5665, 126.9780), MapPoint(2, 37.5666, 126.9785))
        assertEquals(1, Clusters.of(points, MapMath.worldSize(12.0, 256.0), 64.0).size)
        assertEquals(2, Clusters.of(points, MapMath.worldSize(20.0, 256.0), 64.0).size)
        assertTrue(Clusters.of(emptyList(), MapMath.worldSize(5.0, 256.0), 64.0).isEmpty())
    }
}
