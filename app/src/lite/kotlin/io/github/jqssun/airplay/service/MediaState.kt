package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.power.EnergySnapshot

enum class DisplayMode { STARTING, IDLE, AUDIO, MIRROR, NAVIGATION, PIN, STANDBY, ERROR }

data class MediaState(
    val serverRunning: Boolean = false,
    val connectionCount: Int = 0,
    val mode: DisplayMode = DisplayMode.STARTING,
    val track: TrackInfo = TrackInfo(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val pin: String = "",
    val message: String = "Iniciando receptor…",
    val energy: EnergySnapshot = EnergySnapshot(),
    val navigation: C3LinkNavigation = C3LinkNavigation(),
)

fun interface MediaStateListener {
    fun onMediaState(state: MediaState)
}
