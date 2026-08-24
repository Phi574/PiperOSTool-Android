package com.piperostool

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

object PiperRemoteDiscovery {
    fun scan(timeoutMs: Int = 2200): List<PiperRemoteEndpoint> {
        val found = linkedMapOf<String, PiperRemoteEndpoint>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val payload = PiperRemoteProtocol.DISCOVER.toByteArray(StandardCharsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), PiperRemoteProtocol.DISCOVERY_PORT))
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                try {
                    receiveEndpoint(socket)?.let { found["${it.host}:${it.port}"] = it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return found.values.toList()
    }

    fun resolveCode(code: String, timeoutMs: Int = 3000): PiperRemoteEndpoint? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 350
            val payload = (PiperRemoteProtocol.CODE_LOOKUP + code).toByteArray(StandardCharsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), PiperRemoteProtocol.DISCOVERY_PORT))
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                try {
                    receiveEndpoint(socket)?.let { return it.copy(method = PiperRemoteMethod.CODE, credential = code) }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }

    private fun receiveEndpoint(socket: DatagramSocket): PiperRemoteEndpoint? {
        val buffer = ByteArray(1024)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        val value = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
        if (!value.startsWith(PiperRemoteProtocol.HOST_REPLY)) return null
        val parts = value.split('|')
        if (parts.size < 5) return null
        return PiperRemoteEndpoint(
            name = parts[1],
            host = packet.address.hostAddress ?: return null,
            port = parts[2].toIntOrNull() ?: return null,
            method = PiperRemoteMethod.LAN,
            credential = parts[3]
        )
    }
}

class PiperRemoteDiscoveryResponder(
    private val sessionProvider: () -> PiperRemoteSession?,
    private val deviceName: String
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ loop() }, "PiperRemoteDiscovery").start()
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
    }

    private fun loop() {
        try {
            DatagramSocket(null).use { udp ->
                socket = udp
                udp.reuseAddress = true
                udp.bind(java.net.InetSocketAddress(PiperRemoteProtocol.DISCOVERY_PORT))
                while (running.get()) {
                    val buffer = ByteArray(1024)
                    val request = DatagramPacket(buffer, buffer.size)
                    udp.receive(request)
                    val message = String(request.data, 0, request.length, StandardCharsets.UTF_8)
                    val session = sessionProvider() ?: continue
                    val accepted = (message == PiperRemoteProtocol.DISCOVER && session.method == PiperRemoteMethod.LAN) ||
                        (message.startsWith(PiperRemoteProtocol.CODE_LOOKUP) && session.method == PiperRemoteMethod.CODE && message.substringAfter('|') == session.code)
                    if (!accepted) continue
                    val response = "${PiperRemoteProtocol.HOST_REPLY}$deviceName|${session.port}|${session.sessionId}|${session.method.name}"
                        .toByteArray(StandardCharsets.UTF_8)
                    udp.send(DatagramPacket(response, response.size, request.address, request.port))
                }
            }
        } catch (_: Exception) {
        } finally {
            socket = null
            running.set(false)
        }
    }
}
