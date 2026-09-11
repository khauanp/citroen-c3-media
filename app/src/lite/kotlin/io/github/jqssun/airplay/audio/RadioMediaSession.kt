package io.github.jqssun.airplay.audio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
 * Publishes only transport state to Android 5 and routes radio keys to DACP.
 *
 * Do not combine MediaSession with RemoteControlClient on the K00E. The ASUS
 * Android-5 Bluetooth stack treats them as two competing AVRCP players and can
 * terminate the app when iOS changes metadata. Song text/cover remains owned by
 * the C3 dashboard; the radio integration only needs playback state and keys.
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
    private val session: MediaSession? = runCatching { MediaSession(service, "C3MediaRadio") }.getOrNull()
    private var lastPlaying = false
    private var lastPublishedPositionMs = Long.MIN_VALUE
    @Volatile private var latestPlaying = false
    private var pendingPause: PublishedState? = null
    private var pauseQueued = false
    private val publishPause = Runnable {
        pauseQueued = false
        val paused = pendingPause
        pendingPause = null
        if (paused != null && !latestPlaying && !released.get()) publishNow(paused)
    }
    @Volatile private var pendingUpdate: PublishedState? = null
    private val updateQueued = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    init {
        RadioButtonReceiver.attach(this)
        runCatching {
            session?.apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                setMediaButtonReceiver(mediaButtonIntent)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    override fun onPause() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    override fun onSkipToNext() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    override fun onSkipToPrevious() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        @Suppress("DEPRECATION")
                        val event = mediaButtonIntent
                            .getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                        if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            dispatchMediaKey(event.keyCode)
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                }, handler)
                isActive = true
            }
        }
        @Suppress("DEPRECATION")
        runCatching { audioManager.registerMediaButtonEventReceiver(receiver) }
        update(TrackInfo(), false, 0L)
    }

    fun update(state: MediaState) = update(state.track, state.playing, state.positionMs)

    @Suppress("UNUSED_PARAMETER")
    fun update(track: TrackInfo, playing: Boolean, positionMs: Long) {
        if (released.get()) return
        latestPlaying = playing
        // Never retain TrackInfo here: it carries the cover Bitmap. Keeping radio
        // publication primitive-only isolates track changes from the old BT stack.
        pendingUpdate = PublishedState(playing, positionMs.coerceAtLeast(0L))
        if (!updateQueued.compareAndSet(false, true)) return
        handler.post {
            do {
                val current = pendingUpdate
                pendingUpdate = null
                if (current != null) applyUpdate(current)
                updateQueued.set(false)
            } while (pendingUpdate != null && updateQueued.compareAndSet(false, true))
        }
    }

    private fun applyUpdate(state: PublishedState) {
        if (state.playing) {
            pendingPause = null
            pauseQueued = false
            handler.removeCallbacks(publishPause)
            publishNow(state)
            return
        }
        if (lastPlaying) {
            pendingPause = state
            if (!pauseQueued) {
                pauseQueued = true
                handler.postDelayed(publishPause, PAUSE_PUBLICATION_GRACE_MS)
            }
            return
        }
        publishNow(state)
    }

    private fun publishNow(state: PublishedState) {
        val positionChanged = lastPublishedPositionMs == Long.MIN_VALUE ||
            kotlin.math.abs(state.positionMs - lastPublishedPositionMs) >= POSITION_REFRESH_MS
        if (state.playing == lastPlaying && !positionChanged) return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS
        runCatching {
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        state.positionMs,
                        if (state.playing) 1f else 0f,
                    )
                    .build(),
            )
        }
        lastPlaying = state.playing
        lastPublishedPositionMs = state.positionMs
    }

    fun dispatchMediaKey(keyCode: Int) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> service.nextTrack()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> service.previousTrack()
                KeyEvent.KEYCODE_MEDIA_PLAY -> if (!latestPlaying) service.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (latestPlaying) service.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> service.togglePlayPause()
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        RadioButtonReceiver.detach(this)
        @Suppress("DEPRECATION")
        runCatching { audioManager.unregisterMediaButtonEventReceiver(receiver) }
        runCatching {
            session?.isActive = false
            session?.release()
        }
        pendingUpdate = null
        pendingPause = null
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }

    private data class PublishedState(val playing: Boolean, val positionMs: Long)

    private companion object {
        const val POSITION_REFRESH_MS = 5_000L
        const val PAUSE_PUBLICATION_GRACE_MS = 12_000L
    }
}
