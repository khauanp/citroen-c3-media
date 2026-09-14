package io.github.jqssun.airplay.renderer

import android.util.Log
import io.github.jqssun.airplay.bridge.NativeBridge

/**
 * Fault-contained wrapper around the native AirPlay audio engine.
 *
 * Track transitions may repeat format/start/teardown callbacks. Every operation
 * is idempotent and no Java/Kotlin exception is allowed to cross the JNI boundary.
 */
class AudioRenderer {
    private var serverHandle = 0L
    private var started = false

    @Synchronized
    fun attachEngine(handle: Long) {
        serverHandle = handle
        started = false
        if (handle == 0L) return
        safely("configure") {
            NativeBridge.nativeServerAudioConfigure(
                handle,
                NETWORK_CUSHION_MS,
                99,
                OUTPUT_BUFFER_FRAMES,
                false,
                true,
                false,
                false,
            )
        }
    }

    @Synchronized
    fun detachEngine() {
        serverHandle = 0L
        started = false
    }

    @Synchronized
    fun start() {
        val handle = serverHandle
        if (handle == 0L || started) return
        started = safely("start") { NativeBridge.nativeServerAudioStart(handle) }
    }

    /**
     * Used only after a confirmed long disconnection or service shutdown.
     * A track-change teardown never calls this method.
     */
    @Synchronized
    fun stop() {
        val handle = serverHandle
        if (handle == 0L || !started) return
        safely("stop") { NativeBridge.nativeServerAudioStop(handle) }
        started = false
    }

    @Synchronized
    fun setFormat(codecType: Int, samplesPerFrame: Int) {
        val handle = serverHandle
        if (handle == 0L) return
        safely("format") {
            NativeBridge.nativeServerAudioFormat(handle, codecType, samplesPerFrame)
            true
        }
    }

    private inline fun safely(operation: String, block: () -> Boolean): Boolean =
        try {
            block()
        } catch (failure: Throwable) {
            Log.e(TAG, "Native audio $operation contained", failure)
            false
        }

    companion object {
        private const val TAG = "C3MediaAudio"
        private const val NETWORK_CUSHION_MS = 1_500
        private const val OUTPUT_BUFFER_FRAMES = 8_192
    }
}
