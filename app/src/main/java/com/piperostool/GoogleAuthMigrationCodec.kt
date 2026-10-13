package com.piperostool

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Reads and writes Google's otpauth-migration QR payload (protobuf, wire format). */
internal object GoogleAuthMigrationCodec {
    data class Entry(
        val label: String,
        val issuer: String,
        val secret: String,
        val algorithm: String,
        val digits: Int,
        val type: Int,
        val counter: Long = 0
    )

    data class Payload(
        val entries: List<Entry>,
        val batchSize: Int,
        val batchIndex: Int,
        val batchId: Long
    )

    fun encode(entries: List<Entry>, batchSize: Int, batchIndex: Int, batchId: Long): String {
        require(entries.isNotEmpty())
        val payload = ProtoWriter().apply {
            entries.forEach { entry ->
                require(entry.type in setOf(1, 2) && entry.digits in setOf(6, 8))
                val item = ProtoWriter().apply {
                    bytes(1, decodeBase32(entry.secret))
                    string(2, entry.label)
                    if (entry.issuer.isNotBlank()) string(3, entry.issuer)
                    int32(4, algorithmNumber(entry.algorithm).toLong())
                    int32(5, if (entry.digits == 8) 2L else 1L)
                    int32(6, entry.type.toLong())
                    if (entry.type == 1) int32(7, entry.counter)
                }.toByteArray()
                bytes(1, item)
            }
            int32(2, 1L)
            int32(3, batchSize.toLong())
            int32(4, batchIndex.toLong())
            int32(5, batchId)
        }.toByteArray()
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(payload)
        return "otpauth-migration://offline?data=" + URLEncoder.encode(encoded, StandardCharsets.UTF_8.name())
    }

    fun decode(uriText: String): Payload {
        val uri = URI(uriText)
        require(uri.scheme.equals("otpauth-migration", true)) { "Đây không phải QR chuyển tài khoản Google Authenticator" }
        val data = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            val split = part.split('=', limit = 2)
            if (split.size == 2 && split[0] == "data") URLDecoder.decode(split[1], StandardCharsets.UTF_8.name()) else null
        } ?: error("QR Google Authenticator không có dữ liệu")
        val root = ProtoReader(Base64.getDecoder().decode(data))
        val entries = mutableListOf<Entry>()
        var batchSize = 1
        var batchIndex = 0
        var batchId = 0L
        while (root.hasNext()) {
            val (field, wire) = root.tag()
            when (field) {
                1 -> {
                    require(wire == 2) { "Định dạng thông số OTP không hợp lệ" }
                    entries += decodeEntry(root.bytes())
                }
                3 -> batchSize = root.intValue(wire).toInt()
                4 -> batchIndex = root.intValue(wire).toInt()
                5 -> batchId = root.intValue(wire)
                else -> root.skip(wire)
            }
        }
        require(entries.isNotEmpty()) { "QR không chứa tài khoản OTP" }
        require(batchSize in 1..100 && batchIndex in 0 until batchSize) { "Thông tin lô QR không hợp lệ" }
        return Payload(entries, batchSize, batchIndex, batchId)
    }

    private fun decodeEntry(data: ByteArray): Entry {
        val input = ProtoReader(data)
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = "SHA1"
        var digits = 6
        var type = 2
        var counter = 0L
        while (input.hasNext()) {
            val (field, wire) = input.tag()
            when (field) {
                1 -> secret = input.bytes()
                2 -> name = input.string()
                3 -> issuer = input.string()
                4 -> algorithm = algorithmName(input.intValue(wire).toInt())
                5 -> digits = when (input.intValue(wire).toInt()) { 2 -> 8; else -> 6 }
                6 -> type = input.intValue(wire).toInt()
                7 -> counter = input.intValue(wire)
                else -> input.skip(wire)
            }
        }
        require(type in setOf(1, 2)) { "QR chứa loại OTP không được hỗ trợ" }
        val rawSecret = requireNotNull(secret) { "Tài khoản QR thiếu khóa bí mật" }
        if (issuer.isBlank() && ':' in name) {
            issuer = name.substringBefore(':')
            name = name.substringAfter(':')
        }
        require(name.isNotBlank()) { "Tài khoản QR thiếu tên" }
        return Entry(name, issuer, encodeBase32(rawSecret), algorithm, digits, type, counter)
    }

    private fun algorithmNumber(value: String): Int = when (value.uppercase(Locale.ROOT)) {
        "SHA1" -> 1
        "SHA256" -> 2
        "SHA512" -> 3
        else -> error("Google Authenticator không hỗ trợ thuật toán $value")
    }

    private fun algorithmName(value: Int): String = when (value) {
        1, 0 -> "SHA1"
        2 -> "SHA256"
        3 -> "SHA512"
        else -> error("Thuật toán OTP này không được hỗ trợ")
    }

    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>()
        value.uppercase(Locale.ROOT).filter { it != '=' && !it.isWhitespace() && it != '-' }.forEach { char ->
            val n = alphabet.indexOf(char)
            require(n >= 0) { "Khóa Base32 không hợp lệ" }
            buffer = (buffer shl 5) or n
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xff).toByte()
            }
        }
        require(out.isNotEmpty()) { "Khóa Base32 rỗng" }
        return out.toByteArray()
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = StringBuilder()
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private class ProtoWriter {
        private val output = java.io.ByteArrayOutputStream()
        fun string(field: Int, value: String) = bytes(field, value.toByteArray(StandardCharsets.UTF_8))
        fun bytes(field: Int, value: ByteArray) {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            output.write(value)
        }
        fun int32(field: Int, value: Long) {
            varint((field shl 3).toLong())
            varint(value)
        }
        private fun varint(value: Long) {
            var current = value
            while (current and -128L != 0L) {
                output.write(((current and 0x7f) or 0x80).toInt())
                current = current ushr 7
            }
            output.write(current.toInt())
        }
        fun toByteArray() = output.toByteArray()
    }

    private class ProtoReader(private val input: ByteArray) {
        private var position = 0
        fun hasNext() = position < input.size
        fun tag(): Pair<Int, Int> {
            val value = readVarint().toInt()
            require(value != 0) { "Dữ liệu protobuf không hợp lệ" }
            return (value ushr 3) to (value and 7)
        }
        fun bytes(): ByteArray {
            val size = readVarint().toInt()
            require(size >= 0 && position + size <= input.size) { "Dữ liệu protobuf bị cắt" }
            return input.copyOfRange(position, position + size).also { position += size }
        }
        fun string() = String(bytes(), StandardCharsets.UTF_8)
        fun intValue(wire: Int): Long {
            require(wire == 0) { "Kiểu trường protobuf không hợp lệ" }
            return readVarint()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> advance(8)
                2 -> advance(readVarint().toInt())
                5 -> advance(4)
                else -> error("Kiểu protobuf không được hỗ trợ")
            }
        }
        private fun advance(count: Int) {
            require(count >= 0 && position + count <= input.size) { "Dữ liệu protobuf bị cắt" }
            position += count
        }
        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < input.size) { "Dữ liệu protobuf bị cắt" }
                val byte = input[position++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            error("Varint protobuf không hợp lệ")
        }
    }
}
