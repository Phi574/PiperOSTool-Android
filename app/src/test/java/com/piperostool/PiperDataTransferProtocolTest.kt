package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class PiperDataTransferProtocolTest {
    @Test
    fun qrPairingAuthenticatesAndEncryptsFramesInBothDirections() {
        val token = PiperDataTransferProtocol.createPairingToken()
        val listener = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val server = executor.submit<String> {
                listener.accept().use { socket ->
                    PiperDataTransferProtocol.server(socket, token).use { channel ->
                        val incoming = channel.receive()
                        assertEquals(42, incoming.type)
                        assertEquals("private payload", String(incoming.payload))
                        channel.send(43, "ack".toByteArray())
                        channel.pairingCode()
                    }
                }
            }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                PiperDataTransferProtocol.client(socket, token).use { channel ->
                    val code = channel.pairingCode()
                    assertTrue(code.matches(Regex("\\d{6}")))
                    channel.send(42, "private payload".toByteArray())
                    val reply = channel.receive()
                    assertEquals(43, reply.type)
                    assertEquals("ack", String(reply.payload))
                    assertEquals(code, server.get())
                }
            }
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun manualPairingUsesTheSameShortCodeOnBothPeers() {
        val listener = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val server = executor.submit<String> {
                listener.accept().use { PiperDataTransferProtocol.server(it, null).pairingCode() }
            }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                val client = PiperDataTransferProtocol.client(socket, null)
                assertEquals(server.get(), client.pairingCode())
                client.close()
            }
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun qrPairingRejectsAnIncorrectSecret() {
        val listener = ServerSocket(0)
        val correctToken = PiperDataTransferProtocol.createPairingToken()
        val wrongToken = PiperDataTransferProtocol.createPairingToken()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val server = executor.submit<Boolean> {
                listener.accept().use { socket ->
                    try {
                        PiperDataTransferProtocol.server(socket, correctToken).close()
                        false
                    } catch (_: Exception) {
                        true
                    }
                }
            }
            var rejected = false
            Socket("127.0.0.1", listener.localPort).use { socket ->
                try {
                    PiperDataTransferProtocol.client(socket, wrongToken).close()
                } catch (_: Exception) {
                    rejected = true
                }
            }
            assertTrue(rejected)
            assertTrue(server.get())
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun scannedLanPeerCanUseManualCodeAgainstQrReceiver() {
        val listener = ServerSocket(0)
        val token = PiperDataTransferProtocol.createPairingToken()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val server = executor.submit<String> {
                listener.accept().use { socket -> PiperDataTransferProtocol.server(socket, token).pairingCode() }
            }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                val client = PiperDataTransferProtocol.client(socket, null)
                assertEquals(server.get(), client.pairingCode())
                client.close()
            }
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }
}
