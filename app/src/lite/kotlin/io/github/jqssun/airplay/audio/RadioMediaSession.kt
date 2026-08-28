package io.github.jqssun.airplay.audio

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.MediaState

/**
 * Publishes C3 Media as the active player so the radio's AVRCP buttons can
 * control the iPhone through DACP. Android 5 provides this API natively.
 */
class RadioMediaSession(private val service: AirPlayService) {
    private val session = MediaSession(service, "C3MediaRadio")
    private var lastTrack = TrackInfo()
    private var lastPlaying = false

    init {
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (!lastPlaying) service.togglePlayPause()
            }

            override fun onPause() {
                if (lastPlaying) service.togglePlayPause()
            }

            override fun onSkipToNext() = service.nextTrack()
            override fun onSkipToPrevious() = service.previousTrack()
            override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                if (!lastPlaying) service.togglePlayPause()
            }
        })
        session.isActive = true
        update(TrackInfo(), false, 0L)
    }

    fun update(state: MediaState) = update(state.track, state.playing, state.positionMs)

    fun update(track: TrackInfo, playing: Boolean, positionMs: Long) {
        if (track != lastTrack) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title.ifBlank { "C3 Media" })
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist.ifBlank { "iPhone" })
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                    .build(),
            )
            lastTrack = track
        }
        if (playing != lastPlaying || positionMs == 0L) {
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        positionMs,
                        if (playing) 1f else 0f,
                    )
                    .build(),
            )
            lastPlaying = playing
        }
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
