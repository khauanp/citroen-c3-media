package io.github.jqssun.airplay.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class C3LinkPolylineTest {
    @Test fun decodesCanonicalPolyline() {
        val points = C3LinkPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 0.00001)
        assertEquals(-120.2, points[0].longitude, 0.00001)
        assertEquals(43.252, points[2].latitude, 0.00001)
        assertEquals(-126.453, points[2].longitude, 0.00001)
    }

    @Test fun rejectsTruncatedOrInvalidCoordinates() {
        assertTrue(C3LinkPolyline.decode("_p~iF~ps|U_").isEmpty())
        assertTrue(C3LinkPolyline.decode("not a polyline\u0000").isEmpty())
    }
}
