package io.github.jqssun.airplay.power

object EnergyPolicy {
    fun selectMode(
        phonePresent: Boolean,
        idleForMs: Long,
        batteryTemperatureC: Float,
        previousMode: EnergyMode = EnergyMode.ACTIVE,
    ): EnergyMode = when {
        previousMode == EnergyMode.THERMAL_PROTECTION &&
            batteryTemperatureC >= THERMAL_RECOVERY_C -> EnergyMode.THERMAL_PROTECTION
        batteryTemperatureC >= THERMAL_LIMIT_C -> EnergyMode.THERMAL_PROTECTION
        !phonePresent && idleForMs >= STANDBY_AFTER_MS -> EnergyMode.STANDBY
        else -> EnergyMode.ACTIVE
    }

    const val STANDBY_AFTER_MS = 45_000L
    const val THERMAL_LIMIT_C = 45f
    const val THERMAL_RECOVERY_C = 41f
}
