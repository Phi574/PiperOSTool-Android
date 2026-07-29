package com.piperostool

import android.content.Context
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

object TerminalSessionManager {
    enum class SessionMode {
        LINUX,
        ANDROID_SHELL
    }

    interface Listener {
        fun onTerminalOutput(sessionId: Long)
        fun onTerminalSessionsChanged()
    }

    data class SessionInfo(
        val id: Long,
        val title: String,
        val running: Boolean,
        val mode: SessionMode,
        val busy: Boolean,
        val progressPercent: Int?,
        val awaitingConfirmation: Boolean,
        val currentCommand: String?,
        val lastExitCode: Int?
    )

    private val nextId = AtomicLong(1)
    private val sessions = mutableListOf<TerminalSession>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Synchronized
    fun ensureSession(context: Context): SessionInfo =
        sessions.firstOrNull()?.toInfo() ?: createSession(context, defaultMode(context))

    @Synchronized
    fun createSession(
        context: Context,
        mode: SessionMode = defaultMode(context)
    ): SessionInfo {
        val id = nextId.getAndIncrement()
        val session = buildSession(
            context = context,
            id = id,
            displayIndex = sessions.count { it.mode == mode } + 1,
            requestedMode = mode
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
        val modeIndex = sessions.take(index + 1).count { it.mode == old.mode }
        old.close()
        buildSession(
            context = context,
            id = old.id,
            displayIndex = modeIndex,
            requestedMode = old.mode
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
        renumberSessions()
        if (sessions.isEmpty()) nextId.set(1)
        notifySessionsChanged()
    }

    @Synchronized
    fun closeAll() {
        sessions.toList().forEach(TerminalSession::close)
        sessions.clear()
        nextId.set(1)
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

    private fun buildSession(
        context: Context,
        id: Long,
        displayIndex: Int,
        requestedMode: SessionMode
    ): TerminalSession {
        val runtime = TerminalRuntime.inspect(context)
        val mode = if (requestedMode == SessionMode.LINUX && runtime.installed) {
            SessionMode.LINUX
        } else {
            SessionMode.ANDROID_SHELL
        }
        val linuxMode = mode == SessionMode.LINUX
        val home = if (linuxMode) {
            runtime.homeDirectory
        } else {
            File(context.filesDir, "terminal/home")
        }

        return TerminalSession(
            context = context.applicationContext,
            id = id,
            mode = mode,
            title = if (linuxMode) "Linux $displayIndex" else "Shell $displayIndex",
            homeDirectory = home,
            shellExecutable = if (linuxMode) runtime.shellExecutable else null,
            prefixDirectory = runtime.prefixDirectory,
            welcomeText = buildString {
                appendLine("PiperOS Terminal ${AppVersion.name(context)}")
                appendLine(
                    if (linuxMode) {
                        "Mode: PiperOS Linux Runtime"
                    } else {
                        "Mode: Android Shell"
                    }
                )
                if (linuxMode) {
                    appendLine("PREFIX=${runtime.prefixDirectory.absolutePath}")
                } else {
                    appendLine("Shell Android dùng công cụ hệ thống, không dùng package Linux.")
                }
                appendLine("HOME=${home.absolutePath}")
                appendLine()
            },
            onOutput = { notifyOutput(id) }
        )
    }

    private fun defaultMode(context: Context): SessionMode =
        if (TerminalRuntime.inspect(context).installed) {
            SessionMode.LINUX
        } else {
            SessionMode.ANDROID_SHELL
        }

    private fun notifyOutput(sessionId: Long) {
        listeners.forEach { it.onTerminalOutput(sessionId) }
    }

    private fun notifySessionsChanged() {
        listeners.forEach(Listener::onTerminalSessionsChanged)
    }

    private fun renumberSessions() {
        SessionMode.entries.forEach { mode ->
            sessions.filter { it.mode == mode }.forEachIndexed { index, session ->
                session.title = if (mode == SessionMode.LINUX) {
                    "Linux ${index + 1}"
                } else {
                    "Shell ${index + 1}"
                }
            }
        }
    }

    private class TerminalSession(
        private val context: Context,
        val id: Long,
        val mode: SessionMode,
        var title: String,
        private val homeDirectory: File,
        private val shellExecutable: File?,
        private val prefixDirectory: File,
        private val welcomeText: String,
        private val onOutput: () -> Unit
    ) {
        private val output = StringBuilder()
        private val outputLock = Any()
        private var process: Process? = null
        private var writer: OutputStreamWriter? = null

        @Volatile
        private var closed = false

        @Volatile
        private var busy = false

        @Volatile
        private var progressPercent: Int? = null

        @Volatile
        private var awaitingConfirmation = false

        @Volatile
        private var currentCommand: String? = null

        @Volatile
        private var lastExitCode: Int? = null

        fun start() {
            homeDirectory.mkdirs()
            append(welcomeText)
            runCatching {
                val builder = if (shellExecutable != null) {
                    TermuxProcessLauncher.create(
                        context = context,
                        executable = shellExecutable,
                        arguments = emptyList(),
                        workingDirectory = homeDirectory,
                        prefixDirectory = prefixDirectory,
                        homeDirectory = homeDirectory
                    )
                } else {
                    ProcessBuilder("/system/bin/sh")
                        .directory(homeDirectory)
                        .redirectErrorStream(true)
                        .apply {
                            environment().apply {
                                put("HOME", homeDirectory.absolutePath)
                                put("TERM", "xterm-256color")
                                put(
                                    "TMPDIR",
                                    homeDirectory.parentFile?.resolve("tmp")?.apply {
                                        mkdirs()
                                    }?.absolutePath ?: homeDirectory.absolutePath
                                )
                                put("PATH", "/system/bin:/system/xbin:/vendor/bin")
                            }
                        }
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
                                appendProcessOutput(String(buffer, 0, count))
                            }
                        }
                    } catch (_: Exception) {
                        if (!closed) append("\nKhông thể đọc đầu ra của shell.\n")
                    } finally {
                        if (!closed) append("\n[Shell đã kết thúc]\n")
                    }
                }, "PiperTerminal-$id").apply {
                    isDaemon = true
                    start()
                }
            }.onFailure {
                append("Không thể khởi động shell: ${it.message}\n")
            }
        }

        fun sendCommand(command: String): Boolean {
            if (closed || !isProcessRunning()) return false
            busy = true
            progressPercent = null
            awaitingConfirmation = false
            currentCommand = command
            lastExitCode = null
            append("$ $command\n")
            return runCatching {
                writer?.apply {
                    write(
                        "{ $command; }; __piper_exit=${'$'}?; " +
                            "printf '\\n$DONE_MARKER:%s\\n' \"${'$'}__piper_exit\"\n"
                    )
                    flush()
                }
                true
            }.getOrDefault(false)
        }

        fun sendRaw(value: String): Boolean {
            if (closed || !isProcessRunning()) return false
            if (value.trim().equals("y", true) || value.trim().equals("n", true)) {
                awaitingConfirmation = false
            }
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
                output.append(welcomeText)
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
            running = isProcessRunning(),
            mode = mode,
            busy = busy,
            progressPercent = progressPercent,
            awaitingConfirmation = awaitingConfirmation,
            currentCommand = currentCommand,
            lastExitCode = lastExitCode
        )

        private fun appendProcessOutput(text: String) {
            var visibleText = text
            DONE_REGEX.findAll(text).forEach { match ->
                lastExitCode = match.groupValues[1].toIntOrNull()
                busy = false
                progressPercent = if (lastExitCode == 0) 100 else progressPercent
                awaitingConfirmation = false
                currentCommand = null
            }
            visibleText = DONE_REGEX.replace(visibleText, "")

            PERCENT_REGEX.findAll(visibleText).lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.coerceIn(0, 100)
                ?.let { progressPercent = it }

            if (CONFIRMATION_REGEX.containsMatchIn(visibleText)) {
                awaitingConfirmation = true
            }

            if (visibleText.isNotEmpty()) append(visibleText.replace('\r', '\n'))
            if (DONE_REGEX.containsMatchIn(text)) {
                append(
                    if (lastExitCode == 0) {
                        "\n[Hoàn tất]\n"
                    } else {
                        "\n[Kết thúc với mã ${lastExitCode ?: "?"}]\n"
                    }
                )
            }
        }

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
            private const val DONE_MARKER = "__PIPER_DONE__"
            private val DONE_REGEX = Regex("""__PIPER_DONE__:(-?\d+)""")
            private val PERCENT_REGEX = Regex("""(?<!\d)(\d{1,3})\s*%""")
            private val CONFIRMATION_REGEX = Regex(
                """(?i)(\[[Yy]/[Nn]\]|\[[Yy]/n\]|\[y/[Nn]\]|continue\?\s*\[[^]]+])"""
            )
        }
    }
}
