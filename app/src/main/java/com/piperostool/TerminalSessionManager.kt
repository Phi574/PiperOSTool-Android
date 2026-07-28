package com.piperostool

import android.content.Context
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

object TerminalSessionManager {
    interface Listener {
        fun onTerminalOutput(sessionId: Long)
        fun onTerminalSessionsChanged()
    }

    data class SessionInfo(
        val id: Long,
        val title: String,
        val running: Boolean
    )

    private val nextId = AtomicLong(1)
    private val sessions = mutableListOf<TerminalSession>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Synchronized
    fun ensureSession(context: Context): SessionInfo {
        return sessions.firstOrNull()?.toInfo() ?: createSession(context)
    }

    @Synchronized
    fun createSession(context: Context): SessionInfo {
        val id = nextId.getAndIncrement()
        val session = TerminalSession(
            id = id,
            title = "Shell $id",
            homeDirectory = File(context.filesDir, "terminal/home"),
            onOutput = { notifyOutput(id) }
        )
        sessions += session
        session.start()
        notifySessionsChanged()
        return session.toInfo()
    }

    @Synchronized
    fun listSessions(): List<SessionInfo> = sessions.map(TerminalSession::toInfo)

    @Synchronized
    fun output(sessionId: Long): String =
        sessions.firstOrNull { it.id == sessionId }?.snapshot().orEmpty()

    @Synchronized
    fun sendCommand(sessionId: Long, command: String): Boolean =
        sessions.firstOrNull { it.id == sessionId }?.sendCommand(command) == true

    @Synchronized
    fun sendRaw(sessionId: Long, value: String): Boolean =
        sessions.firstOrNull { it.id == sessionId }?.sendRaw(value) == true

    @Synchronized
    fun clearOutput(sessionId: Long) {
        sessions.firstOrNull { it.id == sessionId }?.clearOutput()
        notifyOutput(sessionId)
    }

    @Synchronized
    fun restartSession(context: Context, sessionId: Long) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        val old = sessions[index]
        old.close()
        TerminalSession(
            id = old.id,
            title = old.title,
            homeDirectory = File(context.filesDir, "terminal/home"),
            onOutput = { notifyOutput(sessionId) }
        ).also {
            sessions[index] = it
            it.start()
        }
        notifySessionsChanged()
    }

    @Synchronized
    fun closeSession(sessionId: Long) {
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        sessions.remove(session)
        session.close()
        notifySessionsChanged()
    }

    @Synchronized
    fun closeAll() {
        sessions.toList().forEach(TerminalSession::close)
        sessions.clear()
        notifySessionsChanged()
    }

    @Synchronized
    fun sessionCount(): Int = sessions.size

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun notifyOutput(sessionId: Long) {
        listeners.forEach { it.onTerminalOutput(sessionId) }
    }

    private fun notifySessionsChanged() {
        listeners.forEach(Listener::onTerminalSessionsChanged)
    }

    private class TerminalSession(
        val id: Long,
        val title: String,
        private val homeDirectory: File,
        private val onOutput: () -> Unit
    ) {
        private val output = StringBuilder()
        private val outputLock = Any()
        private var process: Process? = null
        private var writer: OutputStreamWriter? = null

        @Volatile
        private var closed = false

        fun start() {
            homeDirectory.mkdirs()
            append(
                "PiperOS Terminal\n" +
                    "Shell Android chạy trong vùng riêng của ứng dụng.\n" +
                    "HOME=${homeDirectory.absolutePath}\n\n"
            )
            runCatching {
                val builder = ProcessBuilder("/system/bin/sh")
                    .directory(homeDirectory)
                    .redirectErrorStream(true)
                builder.environment().apply {
                    put("HOME", homeDirectory.absolutePath)
                    put("TMPDIR", homeDirectory.parentFile?.resolve("tmp")?.apply {
                        mkdirs()
                    }?.absolutePath ?: homeDirectory.absolutePath)
                    put("TERM", "xterm-256color")
                    put("PATH", "/system/bin:/system/xbin:/vendor/bin")
                }
                process = builder.start()
                writer = OutputStreamWriter(process!!.outputStream)
                Thread({
                    try {
                        InputStreamReader(process!!.inputStream).use { reader ->
                            val buffer = CharArray(2048)
                            while (!closed) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                append(String(buffer, 0, count))
                            }
                        }
                    } catch (_: Exception) {
                        if (!closed) append("\nKhông thể đọc đầu ra của shell.\n")
                    } finally {
                        if (!closed) {
                            append("\n[Shell đã kết thúc]\n")
                        }
                    }
                }, "PiperTerminal-$id").apply {
                    isDaemon = true
                    start()
                }
            }.onFailure {
                append("Không thể khởi động /system/bin/sh: ${it.message}\n")
            }
        }

        fun sendCommand(command: String): Boolean {
            if (closed || !isProcessRunning()) return false
            append("$ $command\n")
            return runCatching {
                writer?.apply {
                    write(command)
                    write("\n")
                    flush()
                }
                true
            }.getOrDefault(false)
        }

        fun sendRaw(value: String): Boolean {
            if (closed || !isProcessRunning()) return false
            return runCatching {
                writer?.apply {
                    write(value)
                    flush()
                }
                true
            }.getOrDefault(false)
        }

        fun snapshot(): String = synchronized(outputLock) { output.toString() }

        fun clearOutput() {
            synchronized(outputLock) {
                output.clear()
                output.append("PiperOS Terminal\n\n")
            }
        }

        fun close() {
            closed = true
            runCatching { writer?.close() }
            process?.destroy()
            process = null
            writer = null
        }

        fun toInfo(): SessionInfo = SessionInfo(
            id = id,
            title = title,
            running = isProcessRunning()
        )

        private fun isProcessRunning(): Boolean {
            val activeProcess = process ?: return false
            return try {
                activeProcess.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }

        private fun append(text: String) {
            synchronized(outputLock) {
                output.append(text)
                if (output.length > MAX_OUTPUT_CHARS) {
                    output.delete(0, output.length - TRIMMED_OUTPUT_CHARS)
                }
            }
            onOutput()
        }

        companion object {
            private const val MAX_OUTPUT_CHARS = 160_000
            private const val TRIMMED_OUTPUT_CHARS = 120_000
        }
    }
}
