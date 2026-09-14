package io.github.jqssun.airplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayNightPolicyTest {
    @Test
    fun `day begins at seven`() {
        assertFalse(DayNightPolicy.isDaytime(6))
        assertTrue(DayNightPolicy.isDaytime(7))
    }

    @Test
    fun `night begins at nineteen`() {
        assertTrue(DayNightPolicy.isDaytime(18))
        assertFalse(DayNightPolicy.isDaytime(19))
    }

    @Test
    fun `every hour selects exactly one automatic theme`() {
        val daylightHours = (0..23).count(DayNightPolicy::isDaytime)
        assertEquals(12, daylightHours)
    }

    @Test
    fun `daylight uses maximum readable brightness`() {
        assertEquals(1f, DayNightPolicy.DAY_BRIGHTNESS, 0f)
        assertEquals(0.78f, DayNightPolicy.NIGHT_BRIGHTNESS, 0f)
    }
}
