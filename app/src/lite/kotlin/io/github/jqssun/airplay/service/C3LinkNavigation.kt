package io.github.jqssun.airplay.service

data class GeoPoint(val latitude: Double, val longitude: Double)

data class C3LinkNavigation(
    val connected: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
    val routeId: String = "",
    val destination: String = "",
    val route: List<GeoPoint> = emptyList(),
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedMps: Double = 0.0,
    val courseDegrees: Double = 0.0,
    val instruction: String = "",
    val maneuver: String = "straight",
    val stepDistanceMeters: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val remainingSeconds: Double = 0.0,
    val lastUpdateElapsedMs: Long = 0L,
)

data class MapTileKey(val zoom: Int, val x: Int, val y: Int) {
    fun normalized(): MapTileKey? {
        if (zoom !in 0..20) return null
        val side = 1 shl zoom
        if (y !in 0 until side) return null
        val wrappedX = ((x % side) + side) % side
        return copy(x = wrappedX)
    }

    val diskName: String get() = "${zoom}_${x}_${y}.png"
}

data class MapTileChunk(
    val key: MapTileKey,
    val transferId: String,
    val part: Int,
    val parts: Int,
    val encodedData: String,
    val expiresAtEpochSeconds: Long,
)
