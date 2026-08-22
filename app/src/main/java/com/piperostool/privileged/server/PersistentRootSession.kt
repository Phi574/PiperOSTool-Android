package com.piperostool.privileged.server

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class ShellResult(val exitCode: Int, val output: String)

internal class PersistentRootSession private constructor(
    private val process: Process,
    private val input: BufferedReader,
    private val output: OutputStreamWriter
) : Closeable {
    private val lock = Any()

    fun execute(command: String, timeoutMillis: Long = 15_000L): ShellResult = synchronized(lock) {
        check(process.isAlive) { "Root session is not running" }
        val marker = "__PPS_${UUID.randomUUID().toString().replace("-", "")}__"
        output.write("$command\n")
        output.write("printf '\\n$marker:%s\\n' \"\$?\"\n")
        output.flush()

        val text = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (!input.ready()) {
                Thread.sleep(10)
                continue
            }
            val line = input.readLine() ?: error("Root shell closed")
            if (line.startsWith("$marker:")) {
                return@synchronized ShellResult(
                    line.substringAfter(':').trim().toIntOrNull() ?: -1,
                    text.toString().trimEnd()
                )
            }
            text.appendLine(line)
        }
        throw java.util.concurrent.TimeoutException("Root command timed out")
    }

    override fun close() = synchronized(lock) {
        runCatching {
            output.write("exit\n")
            output.flush()
        }
        process.destroy()
    }

    companion object {
        fun open(): PersistentRootSession {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            val session = PersistentRootSession(
                process,
                BufferedReader(InputStreamReader(process.inputStream)),
                OutputStreamWriter(process.outputStream)
            )
            val identity = session.execute("id -u", 20_000L)
            if (identity.exitCode != 0 || identity.output.lineSequence().lastOrNull()?.trim() != "0") {
                session.close()
                error("Root authorization was denied")
            }
            return session
        }
    }
}
