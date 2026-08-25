package com.piperostool

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class PiperRemoteVideoDecoder {
    private data class Frame(val data: ByteArray, val presentationTimeUs: Long, val keyFrame: Boolean)

    private val running = AtomicBoolean(false)
    private val frames = ArrayBlockingQueue<Frame>(6)
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    private var codecData = ByteArray(0)

    fun start(stream: PiperRemoteStream, width: Int, height: Int, config: ByteArray, surface: Surface) {
        stop()
        require(stream != PiperRemoteStream.JPEG)
        codecData = config.copyOf()
        val mime = if (stream == PiperRemoteStream.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val decoder = MediaCodec.createDecoderByType(mime)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 512 * 1024))
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
        running.set(true)
        worker = Thread({ decodeLoop(decoder) }, "PiperRemoteVideoDecoder").also { it.start() }
    }

    fun queue(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        if (!running.get()) return
        val keyFrame = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val frameData = if (keyFrame && codecData.isNotEmpty()) codecData + data else data
        val frame = Frame(frameData, presentationTimeUs, keyFrame)
        if (frames.offer(frame)) return
        frames.poll()
        frames.offer(frame)
    }

    private fun decodeLoop(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val frame = frames.poll(25, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val inputIndex = decoder.dequeueInputBuffer(if (frame.keyFrame) 20_000 else 0)
                    if (inputIndex >= 0) {
                        decoder.getInputBuffer(inputIndex)?.apply {
                            clear()
                            put(frame.data)
                        }
                        decoder.queueInputBuffer(inputIndex, 0, frame.data.size, frame.presentationTimeUs, 0)
                    }
                }
                var outputIndex = decoder.dequeueOutputBuffer(info, 0)
                while (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, true)
                    outputIndex = decoder.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (_: Exception) {
            // Closing a codec unblocks dequeue calls and is expected during disconnect.
        }
    }

    fun stop() {
        running.set(false)
        frames.clear()
        worker?.interrupt()
        worker = null
        val activeCodec = codec
        codec = null
        runCatching { activeCodec?.stop() }
        runCatching { activeCodec?.release() }
    }
}
