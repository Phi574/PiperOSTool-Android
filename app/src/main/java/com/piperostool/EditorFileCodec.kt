package com.piperostool

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class EditorDocument(
    val text: String,
    val charsetName: String,
    val binary: Boolean,
    val truncated: Boolean,
    val originalSize: Long
)

object EditorFileCodec {
    const val MAX_EDIT_BYTES = 8 * 1024 * 1024
    val selectableCharsets = listOf(
        "UTF-8", "UTF-16LE", "UTF-16BE", "windows-1258", "windows-1252",
        "ISO-8859-1", "US-ASCII", "Shift_JIS", "GB18030", "Big5"
    )

    fun read(file: File): EditorDocument {
        val truncated = file.length() > MAX_EDIT_BYTES
        val limit = minOf(file.length(), MAX_EDIT_BYTES.toLong()).toInt()
        val bytes = ByteArray(limit)
        var offset = 0
        file.inputStream().use { input ->
            while (offset < limit) {
                val count = input.read(bytes, offset, limit - offset)
                if (count < 0) break
                offset += count
            }
        }
        val loaded = if (offset == bytes.size) bytes else bytes.copyOf(offset)
        val bom = detectBom(loaded)
        val payload = if (bom.second > 0) loaded.copyOfRange(bom.second, loaded.size) else loaded
        val binary = looksBinary(payload)
        val charset = bom.first ?: detectCharset(payload)
        val text = if (binary) toHex(loaded) else decode(payload, charset)
        return EditorDocument(text, charset.name(), binary, truncated, file.length())
    }

    fun write(file: File, value: String, charsetName: String, binary: Boolean) {
        val bytes = if (binary) parseHex(value) else value.toByteArray(Charset.forName(charsetName))
        file.outputStream().use { it.write(bytes) }
    }

    fun toHex(bytes: ByteArray): String = buildString {
        bytes.toList().chunked(16).forEachIndexed { row, values ->
            append("%08X: ".format(row * 16))
            append(values.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            repeat(16 - values.size) { append("   ") }
            append("  |")
            values.forEach { value ->
                val code = value.toInt() and 0xFF
                append(if (code in 32..126) code.toChar() else '.')
            }
            appendLine('|')
        }
    }

    fun parseHex(value: String): ByteArray {
        val formatted = Regex("(?m)^[0-9A-Fa-f]{8}:\\s*((?:[0-9A-Fa-f]{2}(?:\\s+|$)){1,16})")
            .findAll(value)
            .flatMap { it.groupValues[1].trim().split(Regex("\\s+")).asSequence() }
            .toList()
        val tokens = if (formatted.isNotEmpty()) formatted else Regex("(?i)\\b[0-9a-f]{2}\\b")
            .findAll(value).map { it.value }.toList()
        require(tokens.isNotEmpty() || value.isBlank()) { "Dữ liệu HEX không hợp lệ" }
        return tokens.map { it.toInt(16).toByte() }.toByteArray()
    }

    fun encodeBase64(value: String, charsetName: String): String =
        android.util.Base64.encodeToString(
            value.toByteArray(Charset.forName(charsetName)),
            android.util.Base64.NO_WRAP
        )

    fun decodeBase64(value: String, charsetName: String): String =
        String(android.util.Base64.decode(value.trim(), android.util.Base64.DEFAULT), Charset.forName(charsetName))

    private fun decode(bytes: ByteArray, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes)).toString()

    private fun detectCharset(bytes: ByteArray): Charset {
        val utf8 = Charset.forName("UTF-8")
        try {
            utf8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            return utf8
        } catch (_: CharacterCodingException) {
        }
        if (bytes.size >= 4) {
            val evenZeros = bytes.indices.count { it % 2 == 0 && bytes[it] == 0.toByte() }
            val oddZeros = bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() }
            if (oddZeros > bytes.size / 6) return Charset.forName("UTF-16LE")
            if (evenZeros > bytes.size / 6) return Charset.forName("UTF-16BE")
        }
        return Charset.forName("windows-1258")
    }

    private fun detectBom(bytes: ByteArray): Pair<Charset?, Int> = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charset.forName("UTF-8") to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charset.forName("UTF-16LE") to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charset.forName("UTF-16BE") to 2
        else -> null to 0
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = bytes.take(4096)
        val controls = sample.count {
            val code = it.toInt() and 0xFF
            code == 0 || (code < 9) || code in 14..31
        }
        return controls > sample.size / 20
    }
}

object EditorHistoryStore {
    fun capture(context: Context, file: File): File {
        val backup = backupFile(context, file)
        if (!backup.exists()) {
            backup.parentFile?.mkdirs()
            file.copyTo(backup, overwrite = false)
        }
        markApkChange(file)
        return backup
    }

    fun restore(context: Context, file: File): Boolean {
        val backup = backupFile(context, file)
        if (!backup.isFile) return false
        backup.copyTo(file, overwrite = true)
        backup.delete()
        clearApkChange(file)
        return true
    }

    fun hasBackup(context: Context, file: File): Boolean = backupFile(context, file).isFile

    fun isApkModified(file: File): Boolean = apkMarker(file)?.isFile == true

    fun markNewApkFile(file: File) {
        markApkChange(file)
    }

    private fun backupFile(context: Context, file: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(file.absolutePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(AccountDataScope.directory(context, "editor_history"), "$digest.bin")
    }

    private fun markApkChange(file: File) {
        apkMarker(file)?.apply {
            parentFile?.mkdirs()
            writeText(file.absolutePath)
        }
    }

    private fun clearApkChange(file: File) {
        apkMarker(file)?.delete()
    }

    private fun apkMarker(file: File): File? {
        var parent = file.parentFile
        while (parent != null) {
            if (
                parent.name in setOf("files", "decoded") &&
                File(parent.parentFile, "source.apk").isFile
            ) {
                val relative = file.relativeTo(parent).invariantSeparatorsPath
                val safe = MessageDigest.getInstance("SHA-256")
                    .digest(relative.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                return File(parent.parentFile, ".changes/$safe.changed")
            }
            parent = parent.parentFile
        }
        return null
    }
}
