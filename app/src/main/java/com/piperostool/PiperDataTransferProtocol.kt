package com.piperostool

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PiperOS Data Transfer v1 transport security.
 * P-256 ephemeral ECDH + HKDF-SHA256 + AES-256-GCM, with a QR secret or an
 * on-device six-digit comparison code to authenticate the peer before data flows.
 */
internal object PiperDataTransferProtocol {
    private const val MAGIC = "PIPEROS-DATA-TRANSFER/1"
    private const val MAX_PUBLIC_KEY_BYTES = 1024
    private const val MAX_FRAME_BYTES = 1024 * 1024
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 32
    private val random = SecureRandom()

    data class Frame(val type: Int, val payload: ByteArray)

    class SecureChannel internal constructor(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: DataOutputStream,
        private val outboundKey: ByteArray,
        private val inboundKey: ByteArray,
        private val sasCode: String,
        private val outboundDirection: Int,
        private val inboundDirection: Int
    ) : AutoCloseable {
        private var sentSequence = 0L
        private var receivedSequence = 0L

        fun send(type: Int, payload: ByteArray = byteArrayOf()) {
            require(type in 0..255) { "Invalid frame type" }
            require(payload.size <= MAX_FRAME_BYTES - 1) { "Transfer message is too large" }
            val sequence = ++sentSequence
            val clear = ByteArray(payload.size + 1)
            clear[0] = type.toByte()
            payload.copyInto(clear, destinationOffset = 1)
            val cipherText = crypt(Cipher.ENCRYPT_MODE, outboundKey, outboundDirection, sequence, clear)
            output.writeLong(sequence)
            output.writeInt(cipherText.size)
            output.write(cipherText)
            output.flush()
        }

        fun receive(): Frame {
            val sequence = input.readLong()
            if (sequence != receivedSequence + 1L) throw SecurityException("Invalid transfer sequence")
            val encryptedLength = input.readInt()
            if (encryptedLength !in 17..(MAX_FRAME_BYTES + 16)) {
                throw SecurityException("Invalid transfer frame length")
            }
            val encrypted = ByteArray(encryptedLength)
            input.readFully(encrypted)
            val clear = crypt(Cipher.DECRYPT_MODE, inboundKey, inboundDirection, sequence, encrypted)
            if (clear.isEmpty()) throw SecurityException("Empty transfer frame")
            receivedSequence = sequence
            return Frame(clear[0].toInt() and 0xff, clear.copyOfRange(1, clear.size))
        }

        fun pairingCode(): String = sasCode

        override fun close() {
            runCatching { socket.close() }
        }

        private fun crypt(mode: Int, key: ByteArray, direction: Int, sequence: Long, value: ByteArray): ByteArray {
            val nonce = ByteBuffer.allocate(12)
                .putInt(if (direction == 0) 0x50444331 else 0x50445331)
                .putLong(sequence)
                .array()
            val aad = ByteBuffer.allocate(17)
                .put("PDT-FRAME-V1".toByteArray(StandardCharsets.US_ASCII))
                .put(direction.toByte())
                .putInt(sequence.toInt())
                .array()
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(value)
            }
        }
    }

    fun createPairingToken(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun server(socket: Socket, pairingToken: ByteArray?): SecureChannel = handshake(socket, pairingToken, server = true)

    fun client(socket: Socket, pairingToken: ByteArray?): SecureChannel = handshake(socket, pairingToken, server = false)

    private fun handshake(socket: Socket, token: ByteArray?, server: Boolean): SecureChannel {
        require(token == null || token.size == KEY_BYTES) { "Invalid pairing token" }
        socket.tcpNoDelay = true
        socket.soTimeout = 180_000
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        val pair = newEphemeralKeyPair()
        val localPublic = pair.public.encoded
        val localNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val peerPublic: ByteArray
        val peerNonce: ByteArray
        val transcript: ByteArray
        var tokenAuthenticated = false

        if (!server) {
            output.writeUTF(MAGIC)
            output.writeBoolean(token != null)
            writeBoundedBytes(output, localPublic, MAX_PUBLIC_KEY_BYTES)
            output.write(localNonce)
            output.write(proof(token, "client", localPublic, localNonce))
            output.flush()

            if (input.readUTF() != MAGIC) throw SecurityException("Unsupported PiperOS transfer protocol")
            tokenAuthenticated = input.readBoolean()
            if (tokenAuthenticated != (token != null)) throw SecurityException("Pairing method mismatch")
            peerPublic = readBoundedBytes(input, MAX_PUBLIC_KEY_BYTES)
            peerNonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val peerProof = ByteArray(32).also(input::readFully)
            transcript = transcript(localPublic, localNonce, peerPublic, peerNonce)
            if (tokenAuthenticated && !MessageDigest.isEqual(peerProof, hmac(token!!, "server".toByteArray(), transcript))) {
                throw SecurityException("QR pairing authentication failed")
            }
            if (!tokenAuthenticated && !MessageDigest.isEqual(peerProof, ByteArray(32))) {
                throw SecurityException("Unexpected pairing proof")
            }
        } else {
            if (input.readUTF() != MAGIC) throw SecurityException("Unsupported PiperOS transfer protocol")
            tokenAuthenticated = input.readBoolean()
            if (tokenAuthenticated && token == null) throw SecurityException("QR pairing secret is required")
            peerPublic = readBoundedBytes(input, MAX_PUBLIC_KEY_BYTES)
            peerNonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val peerProof = ByteArray(32).also(input::readFully)
            if (tokenAuthenticated && !MessageDigest.isEqual(peerProof, hmac(token!!, "client".toByteArray(), peerPublic, peerNonce))) {
                throw SecurityException("QR pairing authentication failed")
            }
            if (!tokenAuthenticated && !MessageDigest.isEqual(peerProof, ByteArray(32))) {
                throw SecurityException("Unexpected pairing proof")
            }
            transcript = transcript(peerPublic, peerNonce, localPublic, localNonce)
            output.writeUTF(MAGIC)
            output.writeBoolean(tokenAuthenticated)
            writeBoundedBytes(output, localPublic, MAX_PUBLIC_KEY_BYTES)
            output.write(localNonce)
            output.write(if (!tokenAuthenticated) ByteArray(32) else hmac(token!!, "server".toByteArray(), transcript))
            output.flush()
        }

        val peerKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(peerKey, true)
        val shared = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(
            (if (tokenAuthenticated) token!! else byteArrayOf()) + peerFirstNonce(server, localNonce, peerNonce) + peerSecondNonce(server, localNonce, peerNonce)
        )
        val material = hkdf(shared, salt, "PiperOS Data Transfer v1".toByteArray(StandardCharsets.UTF_8), 64)
        val sasHash = hmac(shared, "PDT-SAS-V1".toByteArray(StandardCharsets.US_ASCII), transcript)
        val sasValue = ((sasHash[0].toLong() and 0xff) shl 24) or
            ((sasHash[1].toLong() and 0xff) shl 16) or
            ((sasHash[2].toLong() and 0xff) shl 8) or
            (sasHash[3].toLong() and 0xff)
        val sas = String.format(java.util.Locale.ROOT, "%06d", (sasValue % 1_000_000L))
        return if (server) {
            SecureChannel(socket, input, output, material.copyOfRange(32, 64), material.copyOfRange(0, 32), sas, 1, 0)
        } else {
            SecureChannel(socket, input, output, material.copyOfRange(0, 32), material.copyOfRange(32, 64), sas, 0, 1)
        }
    }

    private fun newEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    private fun writeBoundedBytes(output: DataOutputStream, bytes: ByteArray, max: Int) {
        require(bytes.size in 1..max)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBoundedBytes(input: DataInputStream, max: Int): ByteArray {
        val length = input.readInt()
        if (length !in 1..max) throw SecurityException("Invalid key length")
        return ByteArray(length).also(input::readFully)
    }

    private fun proof(token: ByteArray?, role: String, publicKey: ByteArray, nonce: ByteArray): ByteArray =
        if (token == null) ByteArray(32) else hmac(token, role.toByteArray(StandardCharsets.US_ASCII), publicKey, nonce)

    private fun transcript(clientPublic: ByteArray, clientNonce: ByteArray, serverPublic: ByteArray, serverNonce: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(
            "PDT-TRANSCRIPT-V1".toByteArray(StandardCharsets.US_ASCII) + clientPublic + clientNonce + serverPublic + serverNonce
        )

    private fun peerFirstNonce(server: Boolean, local: ByteArray, peer: ByteArray) = if (server) peer else local
    private fun peerSecondNonce(server: Boolean, local: ByteArray, peer: ByteArray) = if (server) local else peer

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val pseudoRandomKey = hmac(salt, ikm)
        val result = ByteArray(length)
        var previous = byteArrayOf()
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(pseudoRandomKey, previous, info, byteArrayOf(counter.toByte()))
            val count = minOf(previous.size, length - offset)
            previous.copyInto(result, offset, 0, count)
            offset += count
            counter++
        }
        return result
    }

    private fun hmac(key: ByteArray, vararg pieces: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        pieces.forEach(mac::update)
        return mac.doFinal()
    }
}
