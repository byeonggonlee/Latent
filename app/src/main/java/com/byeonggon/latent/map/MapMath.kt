package com.byeonggon.latent.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Places on the earth as a map shows them: the Web Mercator projection that map tiles are cut by. */
object MapMath {
    /** The northernmost and southernmost latitudes the projection reaches. */
    const val MAX_LATITUDE = 85.0511287798

    /** Where a place is on the whole world's map, from 0 to 1 across and from 0 at the north to 1 at the south. */
    fun worldX(longitude: Double): Double = (longitude + 180.0) / 360.0

    fun worldY(latitude: Double): Double {
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return 0.5 - ln(tan(PI / 4 + Math.toRadians(clamped) / 2)) / (2 * PI)
    }

    fun longitudeOf(worldX: Double): Double = worldX * 360.0 - 180.0

    fun latitudeOf(worldY: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * worldY))))

    /** How many tiles make a side of the world's map at [zoom], a whole number. */
    fun tilesAcross(zoom: Int): Int = 1 shl zoom

    /** The tile of zoom level [zoom] that holds a world position, as its column or row. */
    fun tileOf(world: Double, zoom: Int): Int = floor(world * tilesAcross(zoom)).toInt().coerceIn(0, tilesAcross(zoom) - 1)

    /** The size of the whole world's map, in pixels, at a zoom level that may lie between whole ones. */
    fun worldSize(zoom: Double, tilePixels: Double): Double = tilePixels * 2.0.pow(zoom)
}

/** A photo, or several, at a place on the map. */
data class MapPoint(val id: Long, val latitude: Double, val longitude: Double)

/** Photos taken close together as the map is looked at now: where they are shown, how many, and which. */
data class Cluster(val latitude: Double, val longitude: Double, val ids: List<Long>) {
    val count: Int get() = ids.size
}

object Clusters {
    /**
     * Groups [points] that fall in the same square of [cellPixels] on a map drawn with whole worlds of
     * [worldPixels]. The squares lie on the world and not on the screen, so moving the map does not
     * regroup the photos; only zooming does. Each group stands at the average place of its photos.
     */
    fun of(points: List<MapPoint>, worldPixels: Double, cellPixels: Double): List<Cluster> {
        val cell = cellPixels / worldPixels
        val groups = LinkedHashMap<Long, MutableList<MapPoint>>()
        for (point in points) {
            val column = floor(MapMath.worldX(point.longitude) / cell).toLong()
            val row = floor(MapMath.worldY(point.latitude) / cell).toLong()
            groups.getOrPut(column * 1_000_003L + row) { ArrayList() }.add(point)
        }
        return groups.values.map { group ->
            // Averaged on the map and not on the globe, so that the group stands within the square.
            val x = group.sumOf { MapMath.worldX(it.longitude) } / group.size
            val y = group.sumOf { MapMath.worldY(it.latitude) } / group.size
            Cluster(MapMath.latitudeOf(y), MapMath.longitudeOf(x), group.map { it.id })
        }
    }
}
