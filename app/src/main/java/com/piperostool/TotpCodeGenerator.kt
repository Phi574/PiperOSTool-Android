package com.piperostool

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object TotpCodeGenerator {
    fun generate(secretBase32: String, algorithm: String, digits: Int, periodSeconds: Int, epochSeconds: Long): String {
        require(algorithm in setOf("SHA1", "SHA256", "SHA512"))
        require(digits in 6..8 && periodSeconds > 0)
        return generateCounter(secretBase32, algorithm, epochSeconds / periodSeconds, digits)
    }

    fun generateHotp(secretBase32: String, algorithm: String, digits: Int, counter: Long): String {
        require(algorithm in setOf("SHA1", "SHA256", "SHA512"))
        require(digits in 6..8 && counter >= 0)
        return generateCounter(secretBase32, algorithm, counter, digits)
    }

    fun generateSteam(secretBase32: String, epochSeconds: Long, periodSeconds: Int = 30): String {
        require(periodSeconds > 0)
        val value = generateCounter(secretBase32, "SHA1", epochSeconds / periodSeconds, 10).toLong()
        val alphabet = "23456789BCDFGHJKMNPQRTVWXY"
        var remaining = value
        return buildString(5) {
            repeat(5) {
                append(alphabet[(remaining % alphabet.length).toInt()])
                remaining /= alphabet.length
            }
        }
    }

    private fun generateCounter(secretBase32: String, algorithm: String, counter: Long, digits: Int): String {
        val secret = decodeBase32(secretBase32)
        val message = ByteArray(8) { index -> (counter ushr (56 - 8 * index)).toByte() }
        val hmacName = "Hmac$algorithm"
        val mac = Mac.getInstance(hmacName)
        mac.init(SecretKeySpec(secret, hmacName))
        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val modulo = Math.pow(10.0, digits.toDouble()).toLong()
        return (binary.toLong() % modulo).toString().padStart(digits, '0')
    }

    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val output = ArrayList<Byte>()
        value.uppercase().filter { it != '=' && !it.isWhitespace() }.forEach { character ->
            val value5 = alphabet.indexOf(character)
            require(value5 >= 0) { "Khóa Base32 không hợp lệ" }
            buffer = (buffer shl 5) or value5
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output += ((buffer shr bits) and 0xff).toByte()
            }
        }
        require(output.isNotEmpty()) { "Khóa Base32 rỗng" }
        return output.toByteArray()
    }
}
