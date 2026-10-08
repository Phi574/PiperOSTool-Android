package com.piperostool.privileged.adb

import android.content.Context
import com.piperostool.privileged.PiperPathPolicy
import io.github.muntashirakon.adb.AdbStream
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AdbShellResult(val output: String, val exitCode: Int)

class AdbShellSession(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = PiperAdbConnectionManager.getInstance(appContext)
    private val requestIds = AtomicInteger()
    @Volatile private var serverStream: AdbStream? = null
    @Volatile private var serverInput: BufferedReader? = null
    @Volatile private var serverOutput: BufferedWriter? = null
    @Volatile private var serverPid: Int = -1

    fun connect(timeoutMs: Long = 8_000L): Boolean = synchronized(manager) {
        if (!manager.isConnected && !manager.autoConnect(appContext, timeoutMs)) return@synchronized false
        if (serverStream != null) return@synchronized true
        startServer(timeoutMs)
    }

    fun isConnected(): Boolean = synchronized(manager) { manager.isConnected && serverStream != null }
    fun privilegedServerPid(): Int = serverPid

    fun execute(
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        stdin: String = ""
    ): AdbShellResult = synchronized(manager) {
        check(manager.isConnected) { "PiperOS ADB is not connected" }
        ensureServer()
        val id = requestIds.incrementAndGet()
        val request = JSONObject()
            .put("id", id)
            .put("operation", "newProcess")
            .put("command", command)
            .put("timeoutMs", timeoutMs.coerceIn(1_000L, MAX_COMMAND_TIMEOUT_MS))
            .put("stdin", stdin)
        workingDirectory?.let { request.put("workingDirectory", it) }
        if (environment.isNotEmpty()) request.put("environment", JSONObject(environment))
        val writer = checkNotNull(serverOutput) { "PiperOS shell server input is closed" }
        val reader = checkNotNull(serverInput) { "PiperOS shell server output is closed" }
        writer.write(request.toString())
        writer.newLine()
        writer.flush()
        val response = reader.readLine()?.let(::JSONObject)
            ?: throw IllegalStateException("PiperOS shell server closed its response stream")
        check(response.optInt("id", -1) == id) { "PiperOS shell server returned an unexpected response" }
        AdbShellResult(response.optString("output"), response.optInt("exitCode", -1))
    }

    fun newProcess(
        command: String,
        workingDirectory: String?,
        stdin: String,
        environment: Map<String, String>,
        timeoutMs: Long
    ): JSONObject {
        val result = execute(command, timeoutMs, workingDirectory, environment, stdin)
        return JSONObject().put("exitCode", result.exitCode).put("output", result.output)
    }

    fun copyRemoteFile(path: String, destination: OutputStream) = synchronized(manager) {
        check(manager.isConnected) { "PiperOS ADB is not connected" }
        ensureServer()
        val id = requestIds.incrementAndGet()
        val writer = checkNotNull(serverOutput)
        val reader = checkNotNull(serverInput)
        writer.write(JSONObject().put("id", id).put("operation", "readFile").put("path", path).toString())
        writer.newLine()
        writer.flush()
        var completed = false
        try {
            while (true) {
                val response = reader.readLine()?.let(::JSONObject)
                    ?: throw IllegalStateException("PiperOS shell server closed during file transfer")
                check(response.optInt("id", -1) == id) { "PiperOS shell server returned an unexpected file response" }
                response.optString("chunk").takeIf(String::isNotEmpty)?.let {
                    destination.write(android.util.Base64.decode(it, android.util.Base64.NO_WRAP))
                }
                if (response.optBoolean("done")) {
                    completed = true
                    val exitCode = response.optInt("exitCode", -1)
                    check(exitCode == 0) { response.optString("output").ifBlank { "File read failed (exit $exitCode)" } }
                    return@synchronized
                }
            }
        } catch (error: Throwable) {
            if (!completed) closeServer()
            throw error
        }
    }

    fun transactSystemService(serviceName: String, transactionCode: Int, data: ByteArray, flags: Int): JSONObject =
        synchronized(manager) {
            check(manager.isConnected) { "PiperOS ADB is not connected" }
            ensureServer()
            val id = requestIds.incrementAndGet()
            val writer = checkNotNull(serverOutput)
            val reader = checkNotNull(serverInput)
            writer.write(
                JSONObject().put("id", id).put("operation", "transact")
                    .put("service", serviceName).put("transactionCode", transactionCode)
                    .put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
                    .put("flags", flags).toString()
            )
            writer.newLine()
            writer.flush()
            val response = reader.readLine()?.let(::JSONObject)
                ?: throw IllegalStateException("PiperOS shell server closed during Binder transaction")
            check(response.optInt("id", -1) == id) { "PiperOS shell server returned an unexpected Binder response" }
            response
        }

    fun listDirectory(path: String, showHidden: Boolean): List<JSONObject> = synchronized(manager) {
        check(manager.isConnected) { "PiperOS ADB is not connected" }
        ensureServer()
        val id = requestIds.incrementAndGet()
        val writer = checkNotNull(serverOutput)
        val reader = checkNotNull(serverInput)
        writer.write(JSONObject().put("id", id).put("operation", "listDirectory")
            .put("path", path).put("showHidden", showHidden).toString())
        writer.newLine()
        writer.flush()
        val entries = mutableListOf<JSONObject>()
        while (true) {
            val response = reader.readLine()?.let(::JSONObject)
                ?: throw IllegalStateException("PiperOS shell server closed during directory listing")
            check(response.optInt("id", -1) == id) { "PiperOS shell server returned an unexpected listing response" }
            response.optJSONObject("entry")?.let(entries::add)
            if (response.optBoolean("done")) {
                check(response.optBoolean("success")) {
                    response.optString("error").ifBlank { "Directory listing failed" }
                }
                break
            }
        }
        entries.toList()
    }

    fun fileOperation(
        action: String,
        path: String,
        destination: String = "",
        recursive: Boolean = false,
        mode: Int = 0
    ): JSONObject = synchronized(manager) {
        check(manager.isConnected) { "PiperOS ADB is not connected" }
        ensureServer()
        val id = requestIds.incrementAndGet()
        val writer = checkNotNull(serverOutput)
        val reader = checkNotNull(serverInput)
        val request = JSONObject().put("id", id).put("operation", "file")
            .put("action", action).put("path", path).put("recursive", recursive).put("mode", mode)
        if (destination.isNotBlank()) request.put("destination", destination)
        writer.write(request.toString())
        writer.newLine()
        writer.flush()
        val response = reader.readLine()?.let(::JSONObject)
            ?: throw IllegalStateException("PiperOS shell server closed during file operation")
        check(response.optInt("id", -1) == id) { "PiperOS shell server returned an unexpected file response" }
        response
    }

    override fun close() {
        synchronized(manager) {
            closeServer()
            runCatching { manager.disconnect() }
        }
    }

    private fun startServer(timeoutMs: Long): Boolean {
        closeServer()
        val apkPath = appContext.applicationInfo.sourceDir
        val command = "CLASSPATH=${PiperPathPolicy.shellQuote(apkPath)} app_process /system/bin com.piperostool.privileged.server.PiperShellServer"
        val stream = manager.openStream("shell:$command")
        try {
            val reader = BufferedReader(InputStreamReader(stream.openInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(stream.openOutputStream(), StandardCharsets.UTF_8))
            val startupReader = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PiperOS-shell-startup").apply { isDaemon = true }
            }
            val ready = try {
                startupReader.submit<String?> { reader.readLine() }.get(timeoutMs.coerceIn(1_000L, 30_000L), TimeUnit.MILLISECONDS)
                    ?.let(::JSONObject)
            } finally {
                startupReader.shutdownNow()
            }
                ?: error("app_process closed before the PiperOS shell server was ready")
            check(ready.optBoolean("ready")) { ready.optString("error", "PiperOS shell server did not start") }
            check(ready.optInt("uid", -1) == 2000) { "PiperOS shell server did not start with shell UID 2000" }
            serverPid = ready.optInt("pid", -1)
            serverStream = stream
            serverInput = reader
            serverOutput = writer
            return true
        } catch (error: Throwable) {
            runCatching { stream.close() }
            closeServer()
            throw error
        }
    }

    private fun closeServer() {
        runCatching { serverOutput?.close() }
        runCatching { serverInput?.close() }
        runCatching { serverStream?.close() }
        serverOutput = null
        serverInput = null
        serverStream = null
        serverPid = -1
    }

    private fun ensureServer() {
        if (serverStream == null || serverInput == null || serverOutput == null) {
            check(startServer(DEFAULT_SERVER_START_TIMEOUT_MS)) { "PiperOS shell server did not start" }
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 30_000L
        const val MAX_COMMAND_TIMEOUT_MS = 120_000L
        const val DEFAULT_SERVER_START_TIMEOUT_MS = 8_000L
    }

}
