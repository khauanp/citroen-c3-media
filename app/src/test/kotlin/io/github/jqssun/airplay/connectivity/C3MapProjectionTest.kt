package io.github.jqssun.airplay.connectivity

import io.github.jqssun.airplay.service.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class C3MapProjectionTest {
    @Test fun worldProjectionRoundTripsCuritiba() {
        val zoom = 17
        val longitude = -49.2733
        val latitude = -25.4284
        assertEquals(longitude, C3MapProjection.worldXToLongitude(C3MapProjection.longitudeToWorldX(longitude, zoom), zoom), 0.000001)
        assertEquals(latitude, C3MapProjection.worldYToLatitude(C3MapProjection.latitudeToWorldY(latitude, zoom), zoom), 0.000001)
    }

    @Test fun routeBearingLooksAheadFromNearestPoint() {
        val route = listOf(
            GeoPoint(-25.4284, -49.2733),
            GeoPoint(-25.4280, -49.2733),
            GeoPoint(-25.4270, -49.2733),
        )
        val bearing = C3MapProjection.routeBearingDegrees(-25.4283, -49.2733, route)
        assertTrue(bearing != null)
        assertEquals(0.0, bearing!!, 1.0)
    }

    @Test fun speedZoomIsBoundedForTablet() {
        assertEquals(18, C3MapProjection.zoomForSpeed(0.0))
        assertEquals(17, C3MapProjection.zoomForSpeed(8.0))
        assertEquals(16, C3MapProjection.zoomForSpeed(15.0))
        assertEquals(15, C3MapProjection.zoomForSpeed(30.0))
    }
}
