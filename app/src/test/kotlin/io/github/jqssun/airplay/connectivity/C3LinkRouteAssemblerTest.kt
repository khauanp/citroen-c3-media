package io.github.jqssun.airplay.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class C3LinkRouteAssemblerTest {
    @Test fun assemblesOutOfOrderAndRepeatedParts() {
        val assembler = C3LinkRouteAssembler()
        assertNull(assembler.accept("route-a", 2, 3, "C", 1_000))
        assertNull(assembler.accept("route-a", 0, 3, "A", 1_100))
        assertNull(assembler.accept("route-a", 0, 3, "A", 1_200))
        assertEquals("ABC", assembler.accept("route-a", 1, 3, "B", 1_300))
    }

    @Test fun resetsExpiredAssemblyWithoutMixingRoutes() {
        val assembler = C3LinkRouteAssembler(timeoutMs = 100)
        assertNull(assembler.accept("old", 0, 2, "old-", 1_000))
        assertNull(assembler.accept("new", 1, 2, "route", 1_200))
        assertEquals("new-route", assembler.accept("new", 0, 2, "new-", 1_220))
    }
}
