package com.piperostool

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PiperRemoteClient(private val listener: Listener) {
    interface Listener {
        fun onConnected(width: Int, height: Int, stream: PiperRemoteStream)
        fun onFrame(bitmap: Bitmap)
        fun onVideoConfig(stream: PiperRemoteStream, width: Int, height: Int, fps: Int, codecData: ByteArray)
        fun onVideoFrame(flags: Int, presentationTimeUs: Long, sentAtMs: Long, data: ByteArray)
        fun onError(message: String)
        fun onDisconnected()
    }

    private val running = AtomicBoolean(false)
    private val connectionGeneration = AtomicInteger(0)
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var audioTrack: AudioTrack? = null

    fun connect(
        endpoint: PiperRemoteEndpoint,
        requesterName: String,
        targetWidth: Int,
        targetFps: Int,
        stream: PiperRemoteStream
    ) {
        close(false)
        val generation = connectionGeneration.incrementAndGet()
        running.set(true)
        Thread({
            var localSocket: Socket? = null
            try {
                val client = Socket().also {
                    localSocket = it
                    if (connectionGeneration.get() == generation) socket = it
                }
                client.tcpNoDelay = true
                client.receiveBufferSize = 512 * 1024
                client.connect(InetSocketAddress(endpoint.host, endpoint.port), 8000)
                client.soTimeout = 35_000
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                val writer = DataOutputStream(BufferedOutputStream(client.getOutputStream())).also {
                    if (connectionGeneration.get() == generation) output = it
                }
                synchronized(writer) {
                    writer.writeUTF(PiperRemoteProtocol.MAGIC_V4)
                    writer.writeUTF(endpoint.method.name)
                    writer.writeUTF(endpoint.credential)
                    writer.writeUTF(requesterName)
                    writer.writeInt(targetWidth)
                    writer.writeInt(targetFps)
                    writer.writeInt(stream.wireValue)
                    writer.flush()
                }
                if (!input.readBoolean()) throw IllegalStateException(input.readUTF())
                client.soTimeout = 0
                val remoteWidth = input.readInt()
                val remoteHeight = input.readInt()
                val selectedStream = PiperRemoteStream.fromWire(input.readInt())
                listener.onConnected(remoteWidth, remoteHeight, selectedStream)
                while (running.get() && connectionGeneration.get() == generation) {
                    when (input.readByte()) {
                        PiperRemoteProtocol.PACKET_FRAME -> {
                            input.readInt()
                            input.readInt()
                            input.readLong()
                            val size = input.readInt()
                            if (size <= 0 || size > 8_000_000) throw IllegalStateException("Invalid frame")
                            val bytes = ByteArray(size)
                            input.readFully(bytes)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(listener::onFrame)
                        }
                        PiperRemoteProtocol.PACKET_AUDIO_CONFIG -> {
                            prepareAudio(input.readInt(), input.readInt())
                        }
                        PiperRemoteProtocol.PACKET_AUDIO -> {
                            val size = input.readInt()
                            if (size <= 0 || size > 262_144) throw IllegalStateException("Invalid audio packet")
                            val bytes = ByteArray(size)
                            input.readFully(bytes)
                            audioTrack?.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
                        }
                        PiperRemoteProtocol.PACKET_VIDEO_CONFIG -> {
                            val configuredStream = PiperRemoteStream.fromWire(input.readInt())
                            val width = input.readInt()
                            val height = input.readInt()
                            val fps = input.readInt()
                            val size = input.readInt()
                            if (size < 0 || size > 1_048_576) throw IllegalStateException("Invalid video config")
                            val bytes = ByteArray(size)
                            input.readFully(bytes)
                            listener.onVideoConfig(configuredStream, width, height, fps, bytes)
                        }
                        PiperRemoteProtocol.PACKET_VIDEO_FRAME -> {
                            val flags = input.readInt()
                            val presentationTimeUs = input.readLong()
                            val sentAtMs = input.readLong()
                            val size = input.readInt()
                            if (size <= 0 || size > 8_000_000) throw IllegalStateException("Invalid video frame")
                            val bytes = ByteArray(size)
                            input.readFully(bytes)
                            listener.onVideoFrame(flags, presentationTimeUs, sentAtMs, bytes)
                        }
                    }
                }
            } catch (error: Exception) {
                if (running.get() && connectionGeneration.get() == generation) {
                    listener.onError(error.message ?: "Connection lost")
                }
            } finally {
                runCatching { localSocket?.close() }
                if (connectionGeneration.get() == generation) {
                    releaseAudio()
                    running.set(false)
                    socket = null
                    output = null
                    listener.onDisconnected()
                }
            }
        }, "PiperRemoteClient").start()
    }

    fun sendTouch(action: Int, x: Float, y: Float) = send {
        writeByte(PiperRemoteProtocol.PACKET_TOUCH.toInt())
        writeInt(action)
        writeFloat(x)
        writeFloat(y)
    }

    fun sendBack() = send { writeByte(PiperRemoteProtocol.PACKET_BACK.toInt()) }
    fun sendHome() = send { writeByte(PiperRemoteProtocol.PACKET_HOME.toInt()) }

    private fun prepareAudio(sampleRate: Int, channels: Int) {
        releaseAudio()
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate * channels / 10 * 2))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
            .also { it.play() }
    }

    private fun releaseAudio() {
        val track = audioTrack
        audioTrack = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
    }

    private fun send(block: DataOutputStream.() -> Unit) {
        val writer = output ?: return
        runCatching {
            synchronized(writer) {
                writer.block()
                writer.flush()
            }
        }
    }

    fun close(notify: Boolean = true) {
        connectionGeneration.incrementAndGet()
        val wasRunning = running.getAndSet(false)
        runCatching { socket?.close() }
        releaseAudio()
        socket = null
        output = null
        if (notify && wasRunning) listener.onDisconnected()
    }
}
