package io.github.jqssun.airplay.ui

import org.junit.Assert.assertFalse
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
}
