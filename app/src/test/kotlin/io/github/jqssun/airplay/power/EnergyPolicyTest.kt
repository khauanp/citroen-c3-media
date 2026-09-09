package io.github.jqssun.airplay.power

import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyPolicyTest {
    @Test
    fun `stays active while the phone is present`() {
        assertEquals(
            EnergyMode.ACTIVE,
            EnergyPolicy.selectMode(true, 120_000L, 35f),
        )
    }

    @Test
    fun `enters standby after forty five seconds without the phone`() {
        assertEquals(
            EnergyMode.STANDBY,
            EnergyPolicy.selectMode(false, EnergyPolicy.STANDBY_AFTER_MS, 35f),
        )
    }

    @Test
    fun `standby has priority when the phone is gone even if hot`() {
        assertEquals(
            EnergyMode.STANDBY,
            EnergyPolicy.selectMode(false, 120_000L, EnergyPolicy.THERMAL_LIMIT_C),
        )
    }

    @Test
    fun `protects from heat while in use`() {
        assertEquals(
            EnergyMode.THERMAL_PROTECTION,
            EnergyPolicy.selectMode(true, 0L, EnergyPolicy.THERMAL_LIMIT_C),
        )
    }

    @Test
    fun `does not enter protection below forty five`() {
        assertEquals(
            EnergyMode.ACTIVE,
            EnergyPolicy.selectMode(true, 0L, 44.9f),
        )
    }

    @Test
    fun `thermal protection recovers only below forty one`() {
        assertEquals(
            EnergyMode.THERMAL_PROTECTION,
            EnergyPolicy.selectMode(true, 0L, 41f, EnergyMode.THERMAL_PROTECTION),
        )
        assertEquals(
            EnergyMode.ACTIVE,
            EnergyPolicy.selectMode(true, 0L, 40.9f, EnergyMode.THERMAL_PROTECTION),
        )
    }
}
