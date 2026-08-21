package io.github.jqssun.airplay.connectivity

import io.github.jqssun.airplay.service.GeoPoint
import io.github.jqssun.airplay.service.MapTileKey
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

object C3MapProjection {
    const val TILE_SIZE = 256.0
    private const val MAX_LATITUDE = 85.05112878

    fun worldSize(zoom: Int): Double = (1 shl zoom) * TILE_SIZE

    fun longitudeToWorldX(longitude: Double, zoom: Int): Double =
        (longitude + 180.0) / 360.0 * worldSize(zoom)

    fun latitudeToWorldY(latitude: Double, zoom: Int): Double {
        val radians = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180.0
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * worldSize(zoom)
    }

    fun worldXToLongitude(worldX: Double, zoom: Int): Double = worldX / worldSize(zoom) * 360.0 - 180.0

    fun worldYToLatitude(worldY: Double, zoom: Int): Double =
        Math.toDegrees(kotlin.math.atan(sinh(PI * (1.0 - 2.0 * worldY / worldSize(zoom)))))

    fun zoomForSpeed(speedMps: Double): Int = when {
        speedMps < 4.0 -> 18
        speedMps < 10.0 -> 17
        speedMps < 20.0 -> 16
        else -> 15
    }

    fun visibleTiles(latitude: Double, longitude: Double, zoom: Int, width: Double, height: Double): List<MapTileKey> {
        if (width <= 0.0 || height <= 0.0) return emptyList()
        val centerX = floor(longitudeToWorldX(longitude, zoom) / TILE_SIZE).toInt()
        val centerY = floor(latitudeToWorldY(latitude, zoom) / TILE_SIZE).toInt()
        val radiusX = (width / TILE_SIZE / 2.0).toInt() + 2
        val radiusY = (height / TILE_SIZE / 2.0).toInt() + 2
        return buildList {
            for (y in centerY - radiusY..centerY + radiusY) {
                for (x in centerX - radiusX..centerX + radiusX) {
                    MapTileKey(zoom, x, y).normalized()?.let(::add)
                }
            }
        }.distinct().sortedBy { (it.x - centerX) * (it.x - centerX) + (it.y - centerY) * (it.y - centerY) }
    }

    fun routeBearingDegrees(latitude: Double, longitude: Double, route: List<GeoPoint>, lookAheadMeters: Double = 70.0): Double? {
        if (route.size < 2 || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val longitudeScale = max(cos(Math.toRadians(latitude)), 0.01)
        val nearest = route.indices.minByOrNull { index ->
            val point = route[index]
            val dx = (point.longitude - longitude) * longitudeScale
            val dy = point.latitude - latitude
            dx * dx + dy * dy
        } ?: return null
        var target = minOf(nearest + 1, route.lastIndex)
        var traveled = 0.0
        while (target < route.lastIndex && traveled < lookAheadMeters) {
            traveled += distanceMeters(route[target - 1], route[target])
            if (traveled < lookAheadMeters) target++
        }
        val from = if (nearest == route.lastIndex) route[route.lastIndex - 1] else GeoPoint(latitude, longitude)
        val to = route[target]
        if (distanceMeters(from, to) < 1.0) return null
        return bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude)
    }

    fun shortestBearingDelta(from: Double, to: Double): Double = (to - from + 540.0) % 360.0 - 180.0

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val radians = PI / 180.0
        val x = (b.longitude - a.longitude) * radians * cos((a.latitude + b.latitude) * radians / 2.0)
        val y = (b.latitude - a.latitude) * radians
        return sqrt(x * x + y * y) * 6_371_000.0
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
