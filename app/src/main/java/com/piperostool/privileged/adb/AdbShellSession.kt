package com.piperostool.privileged.adb

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class AdbShellResult(val output: String, val exitCode: Int)

class AdbShellSession(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = PiperAdbConnectionManager.getInstance(appContext)

    fun connect(timeoutMs: Long = 8_000L): Boolean = synchronized(manager) {
        manager.isConnected || manager.autoConnect(appContext, timeoutMs)
    }

    fun execute(command: String): AdbShellResult = synchronized(manager) {
        check(manager.isConnected) { "PiperOS ADB is not connected" }
        val marker = "__PIPER_EXIT_${System.nanoTime()}__"
        val wrapped = "$command; printf '\\n$marker%d\\n' $?"
        val output = read(manager.openStream("shell:$wrapped"))
        val markerIndex = output.lastIndexOf(marker)
        if (markerIndex < 0) return@synchronized AdbShellResult(output.trimEnd(), -1)
        val code = output.substring(markerIndex + marker.length).trim().lineSequence().firstOrNull()?.toIntOrNull() ?: -1
        AdbShellResult(output.substring(0, markerIndex).trimEnd(), code)
    }

    fun open(command: String): AdbStream = synchronized(manager) {
        check(manager.isConnected) { "PiperOS ADB is not connected" }
        manager.openStream("shell:$command")
    }

    override fun close() {
        synchronized(manager) { runCatching { manager.disconnect() } }
    }

    private fun read(stream: AdbStream): String {
        stream.use { adbStream ->
            val output = ByteArrayOutputStream()
            val input = adbStream.openInputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = runCatching { input.read(buffer) }.getOrElse {
                    if (adbStream.isClosed) -1 else throw it
                }
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }
}
