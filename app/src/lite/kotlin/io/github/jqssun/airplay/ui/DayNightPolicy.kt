package io.github.jqssun.airplay.ui

import java.util.Calendar

/** Clock-only policy so the dashboard remains usable without internet or GPS. */
object DayNightPolicy {
    @JvmStatic
    fun isDaytime(hourOfDay: Int): Boolean = hourOfDay in DAY_START_HOUR until NIGHT_START_HOUR

    @JvmStatic
    fun isDaytimeNow(): Boolean = isDaytime(
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    )

    @JvmStatic
    fun activeBrightnessNow(): Float = if (isDaytimeNow()) DAY_BRIGHTNESS else NIGHT_BRIGHTNESS

    const val DAY_START_HOUR = 7
    const val NIGHT_START_HOUR = 19
    const val DAY_BRIGHTNESS = 1f
    const val NIGHT_BRIGHTNESS = 0.78f
}
