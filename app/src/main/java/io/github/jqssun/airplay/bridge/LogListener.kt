package io.github.jqssun.airplay.bridge

/** Receives lightweight diagnostics emitted by the native AirPlay engine. */
interface LogListener {
    fun onLog(msg: String)
}
