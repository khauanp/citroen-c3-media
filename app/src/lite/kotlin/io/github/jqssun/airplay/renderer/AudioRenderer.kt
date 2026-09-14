package io.github.jqssun.airplay.renderer

import android.util.Log
import io.github.jqssun.airplay.CrashDiagnostics
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
        CrashDiagnostics.event("AUDIO", "attach_engine handle_valid=${handle != 0L}")
        serverHandle = handle
        started = false
        if (handle == 0L) return
        safely("configure") {
            NativeBridge.nativeServerAudioConfigure(
                handle,
                NETWORK_CUSHION_MS,
                99,
                OUTPUT_BUFFER_FRAMES,
                true,
                false,
                false,
                false,
            )
        }
    }

    @Synchronized
    fun detachEngine() {
        CrashDiagnostics.event("AUDIO", "detach_engine started=$started")
        serverHandle = 0L
        started = false
    }

    @Synchronized
    fun start() {
        val handle = serverHandle
        if (handle == 0L || started) return
        CrashDiagnostics.event("AUDIO", "start codec_output")
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
        CrashDiagnostics.event("AUDIO", "stop confirmed_long_disconnect_or_shutdown")
        safely("stop") {
            NativeBridge.nativeServerAudioStop(handle)
            true
        }
        started = false
    }

    @Synchronized
    fun setFormat(codecType: Int, samplesPerFrame: Int) {
        val handle = serverHandle
        if (handle == 0L) return
        CrashDiagnostics.event("AUDIO", "format codec=$codecType samples_per_frame=$samplesPerFrame")
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
            CrashDiagnostics.event("AUDIO_ERROR", "$operation ${failure.javaClass.name}: ${failure.message}")
            false
        }

    companion object {
        private const val TAG = "C3MediaAudio"
        // Stability is more important than latency on the Android 5/x86 K00E.
        // These settings select the software codec path proven in 1.8.12 and
        // avoid realtime/vendor-codec paths that can abort the whole process.
        private const val NETWORK_CUSHION_MS = 2_000
        private const val OUTPUT_BUFFER_FRAMES = 8_192
    }
}
