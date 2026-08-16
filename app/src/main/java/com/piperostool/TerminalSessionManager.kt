package com.piperostool

import android.content.Context
import java.io.File
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
        val currentDirectory: String,
        val displayDirectory: String,
        val prompt: String,
        val busy: Boolean,
        val showTaskProgress: Boolean,
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
        if (runtime.installed) {
            runCatching { TerminalRuntime.repairPackageConfiguration(context) }
        }
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
                        "Linux runtime ${runtime.installedVersion ?: TerminalRuntime.RUNTIME_VERSION}"
                    } else {
                        "Android shell • no root"
                    }
                )
                if (linuxMode) {
                    appendLine("Workspace: ~/projects  •  PREFIX: \$PREFIX")
                } else {
                    appendLine("System tools only • Linux packages unavailable")
                }
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
        private var process: PtyProcess? = null
        private val parserBuffer = StringBuilder()

        @Volatile
        private var closed = false

        @Volatile
        private var busy = false

        @Volatile
        private var trackedTask = false

        @Volatile
        private var progressPercent: Int? = null

        @Volatile
        private var awaitingConfirmation = false

        @Volatile
        private var currentCommand: String? = null

        @Volatile
        private var lastExitCode: Int? = null

        @Volatile
        private var currentDirectory = homeDirectory.absolutePath

        fun start() {
            homeDirectory.mkdirs()
            if (mode == SessionMode.LINUX) {
                listOf("downloads", "projects", "scripts").forEach {
                    File(homeDirectory, it).mkdirs()
                }
            }
            runCatching {
                val command: List<String>
                val environment: Map<String, String>
                if (shellExecutable != null) {
                    command = TermuxProcessLauncher.buildCommand(
                        shellExecutable,
                        listOf("--noprofile", "--norc", "-i")
                    )
                    environment = TermuxProcessLauncher.buildEnvironment(
                        context,
                        prefixDirectory,
                        homeDirectory
                    )
                } else {
                    command = listOf("/system/bin/sh")
                    environment = mapOf(
                        "HOME" to homeDirectory.absolutePath,
                        "TERM" to "xterm-256color",
                        "COLORTERM" to "truecolor",
                        "TMPDIR" to (homeDirectory.parentFile?.resolve("tmp")?.apply {
                            mkdirs()
                        }?.absolutePath ?: homeDirectory.absolutePath),
                        "PATH" to "/system/bin:/system/xbin:/vendor/bin"
                    )
                }
                process = PtyProcess.start(command, environment, homeDirectory)
                append(welcomeText)
                appendPrompt()
                Thread({
                    try {
                        while (!closed) {
                            val text = process?.read() ?: break
                            appendProcessOutput(text)
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
            if (closed || busy || !isProcessRunning()) return false
            busy = true
            trackedTask = shouldTrackTask(command)
            progressPercent = null
            awaitingConfirmation = false
            currentCommand = command
            lastExitCode = null
            append("$command\n")
            return runCatching {
                process?.write(
                        "{ $command; }; __piper_exit=${'$'}?; " +
                            "__piper_pwd=\"${'$'}(pwd -P 2>/dev/null || pwd)\"; " +
                            "printf '\\035PIPER_DONE:%s:%s\\036' " +
                            "\"${'$'}__piper_exit\" \"${'$'}__piper_pwd\"\n"
                    ) == true
            }.getOrDefault(false)
        }

        fun sendRaw(value: String): Boolean {
            if (closed || !isProcessRunning()) return false
            if (value.trim().equals("y", true) || value.trim().equals("n", true)) {
                awaitingConfirmation = false
            }
            return runCatching {
                process?.write(value) == true
            }.getOrDefault(false)
        }

        fun snapshot(): String = synchronized(outputLock) { output.toString() }

        fun clearOutput() {
            synchronized(outputLock) {
                output.clear()
                output.append(welcomeText)
                if (!busy) output.append(promptText())
            }
        }

        fun close() {
            closed = true
            runCatching { process?.close() }
            process = null
        }

        fun toInfo(): SessionInfo = SessionInfo(
            id = id,
            title = title,
            running = isProcessRunning(),
            mode = mode,
            currentDirectory = currentDirectory,
            displayDirectory = displayDirectory(),
            prompt = promptText(),
            busy = busy,
            showTaskProgress = trackedTask && busy,
            progressPercent = progressPercent,
            awaitingConfirmation = awaitingConfirmation,
            currentCommand = currentCommand,
            lastExitCode = lastExitCode
        )

        private fun appendProcessOutput(text: String) {
            parserBuffer.append(text.replace("\r\n", "\n"))
            while (true) {
                val match = DONE_REGEX.find(parserBuffer)
                if (match == null) {
                    val markerStart = parserBuffer.lastIndexOf(DONE_START.toString())
                    val visibleEnd = if (markerStart >= 0) markerStart else parserBuffer.length
                    if (visibleEnd > 0) {
                        consumeVisibleOutput(parserBuffer.substring(0, visibleEnd))
                        parserBuffer.delete(0, visibleEnd)
                    }
                    return
                }

                if (match.range.first > 0) {
                    consumeVisibleOutput(parserBuffer.substring(0, match.range.first))
                }
                val exitCode = match.groupValues[1].toIntOrNull()
                val directory = match.groupValues[2].trim()
                parserBuffer.delete(0, match.range.last + 1)
                finishCommand(exitCode, directory)
            }
        }

        private fun consumeVisibleOutput(visibleText: String) {
            PERCENT_REGEX.findAll(visibleText).lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.coerceIn(0, 100)
                ?.let { progressPercent = it }

            if (CONFIRMATION_REGEX.containsMatchIn(visibleText)) {
                awaitingConfirmation = true
            }

            if (visibleText.isNotEmpty()) appendTerminalText(visibleText)
        }

        private fun finishCommand(exitCode: Int?, directory: String) {
            lastExitCode = exitCode
            if (directory.isNotEmpty()) currentDirectory = directory
            busy = false
            progressPercent = if (trackedTask && exitCode == 0) 100 else progressPercent
            trackedTask = false
            awaitingConfirmation = false
            currentCommand = null

            synchronized(outputLock) {
                if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
                if (exitCode != null && exitCode != 0) {
                    output.append("[exit $exitCode]\n")
                }
                output.append(promptText())
            }
            onOutput()
        }

        private fun appendPrompt() {
            append(promptText())
        }

        private fun promptText(): String =
            "${if (mode == SessionMode.LINUX) "piper" else "android"}:" +
                "${displayDirectory()} $ "

        private fun displayDirectory(): String {
            val path = currentDirectory.ifBlank { homeDirectory.absolutePath }
            pathRelativeTo(path, homeDirectory.absolutePath, "~")?.let { return it }
            pathRelativeTo(path, prefixDirectory.absolutePath, "\$PREFIX")?.let { return it }
            return path
        }

        private fun pathRelativeTo(path: String, root: String, label: String): String? {
            equivalentAndroidPaths(root).forEach { candidate ->
                when {
                    path == candidate -> return label
                    path.startsWith("$candidate/") ->
                        return "$label/" + path.removePrefix("$candidate/")
                }
            }
            return null
        }

        private fun equivalentAndroidPaths(path: String): Set<String> = buildSet {
            add(path)
            when {
                path.startsWith("/data/user/0/") ->
                    add(path.replaceFirst("/data/user/0/", "/data/data/"))
                path.startsWith("/data/data/") ->
                    add(path.replaceFirst("/data/data/", "/data/user/0/"))
            }
        }

        private fun shouldTrackTask(command: String): Boolean {
            val normalized = command.trim().lowercase()
            return LONG_TASK_REGEX.containsMatchIn(normalized)
        }

        private fun isProcessRunning(): Boolean {
            return process?.isAlive() == true
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

        private fun appendTerminalText(text: String) {
            synchronized(outputLock) {
                text.forEach { character ->
                    when (character) {
                        '\r' -> {
                            val lineStart = output.lastIndexOf('\n').let { it + 1 }
                            if (lineStart < output.length) output.delete(lineStart, output.length)
                        }
                        '\b' -> {
                            val lineStart = output.lastIndexOf('\n').let { it + 1 }
                            if (output.length > lineStart) output.deleteCharAt(output.lastIndex)
                        }
                        else -> output.append(character)
                    }
                }
                if (output.length > MAX_OUTPUT_CHARS) {
                    output.delete(0, output.length - TRIMMED_OUTPUT_CHARS)
                }
            }
            onOutput()
        }

        companion object {
            private const val MAX_OUTPUT_CHARS = 160_000
            private const val TRIMMED_OUTPUT_CHARS = 120_000
            private const val DONE_START = '\u001D'
            private const val DONE_END = '\u001E'
            private val DONE_REGEX = Regex(
                "$DONE_START" +
                    """PIPER_DONE:(-?\d+):([^$DONE_END]*)""" +
                    "$DONE_END"
            )
            private val PERCENT_REGEX = Regex("""(?<!\d)(\d{1,3})\s*%""")
            private val CONFIRMATION_REGEX = Regex(
                """(?i)(\[[Yy]/[Nn]\]|\[[Yy]/n\]|\[y/[Nn]\]|continue\?\s*\[[^]]+])"""
            )
            private val LONG_TASK_REGEX = Regex(
                """^(pkg|apt|apt-get|dpkg|pip|pip3|npm|pnpm|yarn|cargo|gem|""" +
                    """wget|curl|aria2c|make|cmake|ninja|meson|gradle|\./gradlew)\b|""" +
                    """^python(?:3(?:\.\d+)?)?\s+-m\s+pip\b|""" +
                    """^git\s+(clone|pull|fetch|submodule|lfs)\b"""
            )
        }
    }
}
