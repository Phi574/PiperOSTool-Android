package com.piperostool.privileged

import java.io.File

object PiperPathPolicy {
    private val protectedRoots = listOf(
        "/system",
        "/system_ext",
        "/vendor",
        "/product",
        "/odm",
        "/apex",
        "/metadata",
        "/data/system",
        "/data/misc"
    )

    fun canonical(path: String): String {
        require(path.startsWith('/')) { "Path must be absolute" }
        require('\u0000' !in path) { "Path contains NUL" }
        if (File.separatorChar == '/') return File(path).canonicalPath

        // Local JVM tests may run on Windows, while PPS paths always use Android syntax.
        val segments = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun isProtected(path: String): Boolean {
        val value = canonical(path)
        return protectedRoots.any { value == it || value.startsWith("$it/") }
    }

    fun requireWriteAllowed(path: String, systemWriteEnabled: Boolean): String {
        val value = canonical(path)
        if (isProtected(value) && !systemWriteEnabled) {
            throw SecurityException("System write is disabled")
        }
        return value
    }

    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
