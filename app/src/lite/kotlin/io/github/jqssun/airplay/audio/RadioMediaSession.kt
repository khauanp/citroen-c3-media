package io.github.jqssun.airplay.audio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.RemoteControlClient
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.KeyEvent
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.MediaState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes C3 Media as the active player so the radio's AVRCP buttons can
 * control the iPhone through DACP. Android 5 provides this API natively.
 */
class RadioMediaSession(private val service: AirPlayService) {
    private val worker = HandlerThread("C3MediaRadio", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(worker.looper)
    private val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val receiver = ComponentName(service, RadioButtonReceiver::class.java)
    private val mediaButtonIntent = PendingIntent.getBroadcast(
        service,
        0,
        Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver),
        PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private val session = MediaSession(service, "C3MediaRadio")
    @Suppress("DEPRECATION")
    private val remoteControlClient = RemoteControlClient(mediaButtonIntent)
    private var lastTrack = TrackInfo()
    private var lastPlaying = false
    @Volatile private var pendingState: MediaState? = null
    private val updateQueued = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    init {
        RadioButtonReceiver.attach(this)
        runCatching {
            session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            session.setMediaButtonReceiver(mediaButtonIntent)
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                override fun onPause() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                override fun onSkipToNext() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                override fun onSkipToPrevious() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }, handler)
            session.isActive = true
        }
        @Suppress("DEPRECATION")
        runCatching {
            audioManager.registerMediaButtonEventReceiver(receiver)
            remoteControlClient.setTransportControlFlags(
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY or
                    RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
                    RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or
                    RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
                    RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS,
            )
            audioManager.registerRemoteControlClient(remoteControlClient)
        }
        applyUpdate(TrackInfo(), false, 0L)
    }

    fun update(state: MediaState) {
        if (released.get()) return
        pendingState = state
        if (!updateQueued.compareAndSet(false, true)) return
        handler.post {
            do {
                val current = pendingState
                pendingState = null
                if (current != null) applyUpdate(current.track, current.playing, current.positionMs)
                updateQueued.set(false)
            } while (pendingState != null && updateQueued.compareAndSet(false, true))
        }
    }

    private fun applyUpdate(track: TrackInfo, playing: Boolean, positionMs: Long) {
        if (track != lastTrack) {
            runCatching {
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, track.title.ifBlank { "C3 Media" })
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist.ifBlank { "iPhone" })
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                        .build(),
                )
            }
            lastTrack = track
        }
        if (playing != lastPlaying || positionMs == 0L) {
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            runCatching {
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
                @Suppress("DEPRECATION")
                remoteControlClient.setPlaybackState(
                    if (playing) RemoteControlClient.PLAYSTATE_PLAYING else RemoteControlClient.PLAYSTATE_PAUSED,
                    positionMs,
                    if (playing) 1f else 0f,
                )
            }
            lastPlaying = playing
        }
    }

    fun dispatchMediaKey(keyCode: Int) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> service.nextTrack()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> service.previousTrack()
                KeyEvent.KEYCODE_MEDIA_PLAY -> if (!lastPlaying) service.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (lastPlaying) service.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> service.togglePlayPause()
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        RadioButtonReceiver.detach(this)
        @Suppress("DEPRECATION")
        runCatching {
            audioManager.unregisterRemoteControlClient(remoteControlClient)
            audioManager.unregisterMediaButtonEventReceiver(receiver)
        }
        runCatching {
            session.isActive = false
            session.release()
        }
        worker.quitSafely()
    }
}
