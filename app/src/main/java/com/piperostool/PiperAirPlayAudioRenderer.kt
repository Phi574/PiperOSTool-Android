package com.piperostool

import io.github.jqssun.airplay.bridge.NativeBridge

/** Keeps the native Oboe output in low-latency mode for AirPlay audio. */
class PiperAirPlayAudioRenderer {
    private var handle = 0L

    @Synchronized
    fun attach(serverHandle: Long) {
        handle = serverHandle
        NativeBridge.nativeServerAudioConfigure(
            handle,
            0,
            95,
            0,
            false,
            true,
            true,
            false
        )
    }

    @Synchronized
    fun setFormat(contentType: Int, samplesPerFrame: Int) {
        if (handle == 0L) return
        NativeBridge.nativeServerAudioStart(handle)
        NativeBridge.nativeServerAudioFormat(handle, contentType, samplesPerFrame)
    }

    @Synchronized
    fun stop() {
        if (handle != 0L) NativeBridge.nativeServerAudioStop(handle)
    }

    @Synchronized
    fun detach() {
        stop()
        handle = 0L
    }
}

