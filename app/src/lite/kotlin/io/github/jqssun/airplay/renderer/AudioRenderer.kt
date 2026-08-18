package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.bridge.NativeBridge

/** Small Android-5-compatible wrapper around the native Oboe audio engine. */
class AudioRenderer {
    private var serverHandle = 0L

    @Synchronized
    fun attachEngine(handle: Long) {
        serverHandle = handle
        if (handle != 0L) {
            NativeBridge.nativeServerAudioConfigure(
                handle,
                0,
                95,
                0,
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
    }

    @Synchronized
    fun start() {
        if (serverHandle != 0L) NativeBridge.nativeServerAudioStart(serverHandle)
    }

    @Synchronized
    fun stop() {
        if (serverHandle != 0L) NativeBridge.nativeServerAudioStop(serverHandle)
    }

    @Synchronized
    fun setFormat(codecType: Int, samplesPerFrame: Int) {
        if (serverHandle != 0L) {
            NativeBridge.nativeServerAudioFormat(serverHandle, codecType, samplesPerFrame)
        }
    }
}
