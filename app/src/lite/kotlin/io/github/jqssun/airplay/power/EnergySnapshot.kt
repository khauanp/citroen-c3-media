package io.github.jqssun.airplay.power

enum class EnergyMode { ACTIVE, STANDBY, THERMAL_PROTECTION }

data class EnergySnapshot(
    val mode: EnergyMode = EnergyMode.ACTIVE,
    val phonePresent: Boolean = false,
    val batteryPercent: Int = -1,
    val batteryTemperatureC: Float = 0f,
    val charging: Boolean = false,
    val availableMemoryMb: Long = 0L,
) {
    val thermalLimited: Boolean get() = mode == EnergyMode.THERMAL_PROTECTION
}
