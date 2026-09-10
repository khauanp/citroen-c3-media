package io.github.jqssun.airplay.audio

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.KeyEvent
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.MediaState
import java.util.concurrent.atomic.AtomicBoolean

/** Routes Android-5 media keys to DACP without publishing a local player. */
class RadioMediaSession(private val service: AirPlayService) {
    private val worker = HandlerThread("C3MediaKeys", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(worker.looper)
    private val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val receiver = ComponentName(service, RadioButtonReceiver::class.java)
    @Volatile private var latestPlaying = false
    private val released = AtomicBoolean(false)

    init {
        // AUX carries the sound. A local MediaSession is deliberately absent:
        // changing its PlaybackState entered the unstable ASUS Android-5 stack.
        RadioButtonReceiver.attach(this)
        @Suppress("DEPRECATION")
        runCatching { audioManager.registerMediaButtonEventReceiver(receiver) }
    }

    fun update(state: MediaState) = update(state.track, state.playing, state.positionMs)

    @Suppress("UNUSED_PARAMETER")
    fun update(track: TrackInfo, playing: Boolean, positionMs: Long) {
        latestPlaying = playing
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
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK
                -> service.togglePlayPause()
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        RadioButtonReceiver.detach(this)
        @Suppress("DEPRECATION")
        runCatching { audioManager.unregisterMediaButtonEventReceiver(receiver) }
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }
}
