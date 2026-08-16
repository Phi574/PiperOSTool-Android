package com.piperostool

import java.io.File
import java.nio.charset.StandardCharsets

class PtyProcess private constructor(
    private var descriptor: Int,
    private val processId: Int
) {
    private val writeLock = Any()

    fun read(): String? = nativeRead(descriptor)
        ?.toString(StandardCharsets.UTF_8)

    fun write(value: String): Boolean = synchronized(writeLock) {
        descriptor >= 0 && nativeWrite(
            descriptor,
            value.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun isAlive(): Boolean = descriptor >= 0 && nativeIsAlive(processId)

    fun resize(rows: Int, columns: Int) {
        if (descriptor >= 0) nativeResize(descriptor, rows, columns)
    }

    fun close() = synchronized(writeLock) {
        if (descriptor >= 0) {
            nativeClose(descriptor, processId)
            descriptor = -1
        }
    }

    private external fun nativeRead(fd: Int): ByteArray?
    private external fun nativeWrite(fd: Int, bytes: ByteArray): Boolean
    private external fun nativeIsAlive(pid: Int): Boolean
    private external fun nativeResize(fd: Int, rows: Int, columns: Int)
    private external fun nativeClose(fd: Int, pid: Int)

    companion object {
        init {
            System.loadLibrary("piperos-pty")
        }

        fun start(
            command: List<String>,
            environment: Map<String, String>,
            workingDirectory: File,
            rows: Int = 36,
            columns: Int = 100
        ): PtyProcess {
            require(command.isNotEmpty()) { "PTY command is empty" }
            val result = nativeCreate(
                command.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                workingDirectory.absolutePath,
                rows,
                columns
            ) ?: error("Cannot allocate PTY")
            return PtyProcess(result[0].toInt(), result[1].toInt())
        }

        @JvmStatic
        private external fun nativeCreate(
            command: Array<String>,
            environment: Array<String>,
            workingDirectory: String,
            rows: Int,
            columns: Int
        ): LongArray?
    }
}
