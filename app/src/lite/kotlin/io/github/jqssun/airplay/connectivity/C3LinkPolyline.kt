package io.github.jqssun.airplay.connectivity

import io.github.jqssun.airplay.service.GeoPoint
import kotlin.math.pow

/** Strict Google/OSRM encoded-polyline decoder with bounded memory use. */
object C3LinkPolyline {
    fun decode(encoded: String, maximumPoints: Int = 12_000, precision: Int = 5): List<GeoPoint> {
        if (encoded.isEmpty() || maximumPoints <= 0 || precision !in 5..6) return emptyList()
        val scale = 10.0.pow(precision)
        val points = ArrayList<GeoPoint>(minOf(maximumPoints, encoded.length / 4))
        var index = 0
        var latitude = 0L
        var longitude = 0L
        while (index < encoded.length && points.size < maximumPoints) {
            val lat = decodeValue(encoded, index) ?: return emptyList()
            val lon = decodeValue(encoded, lat.nextIndex) ?: return emptyList()
            index = lon.nextIndex
            latitude += lat.value
            longitude += lon.value
            val decodedLat = latitude / scale
            val decodedLon = longitude / scale
            if (decodedLat !in -90.0..90.0 || decodedLon !in -180.0..180.0) return emptyList()
            points += GeoPoint(decodedLat, decodedLon)
        }
        return if (index == encoded.length) points else emptyList()
    }

    private fun decodeValue(encoded: String, start: Int): DecodedValue? {
        var index = start
        var result = 0L
        var shift = 0
        while (index < encoded.length && shift <= 60) {
            val value = encoded[index++].code - 63
            if (value !in 0..63) return null
            result = result or ((value and 0x1f).toLong() shl shift)
            if (value < 0x20) {
                val signed = if (result and 1L == 0L) result shr 1 else (result shr 1).inv()
                return DecodedValue(signed, index)
            }
            shift += 5
        }
        return null
    }

    private data class DecodedValue(val value: Long, val nextIndex: Int)
}
