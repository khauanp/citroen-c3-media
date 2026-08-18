package io.github.jqssun.airplay.power

object EnergyPolicy {
    fun selectMode(
        phonePresent: Boolean,
        idleForMs: Long,
        batteryTemperatureC: Float,
    ): EnergyMode = when {
        !phonePresent && idleForMs >= STANDBY_AFTER_MS -> EnergyMode.STANDBY
        batteryTemperatureC >= THERMAL_LIMIT_C -> EnergyMode.THERMAL_PROTECTION
        else -> EnergyMode.ACTIVE
    }

    const val STANDBY_AFTER_MS = 45_000L
    const val THERMAL_LIMIT_C = 43f
}
