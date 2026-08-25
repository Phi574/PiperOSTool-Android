package com.piperostool

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PiperRemoteVideoEncoder(
    val stream: PiperRemoteStream,
    val width: Int,
    val height: Int,
    val fps: Int,
    private val bitrate: Int,
    private val listener: Listener
) : AutoCloseable {
    interface Listener {
        fun onVideoConfig(stream: PiperRemoteStream, width: Int, height: Int, fps: Int, codecData: ByteArray)
        fun onVideoFrame(flags: Int, presentationTimeUs: Long, data: ByteArray)
        fun onVideoError(error: Throwable)
    }

    private val mime = when (stream) {
        PiperRemoteStream.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        PiperRemoteStream.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        PiperRemoteStream.JPEG -> error("JPEG does not use MediaCodec")
    }
    private val stopped = AtomicBoolean(false)
    private val callbackThread = HandlerThread("PiperRemote${stream.name}Encoder").also { it.start() }
    private val codec: MediaCodec
    val inputSurface: Surface

    init {
        val codecName = findEncoder(mime, width, height, fps)
            ?: throw IllegalStateException("No encoder for $mime ${width}x$height@$fps")
        codec = MediaCodec.createByCodecName(codecName)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val encoderCapabilities = codec.codecInfo.getCapabilitiesForType(mime).encoderCapabilities
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            codec.codecInfo.getCapabilitiesForType(mime).profileLevels
                .filter { profile ->
                    profile.profile == when (stream) {
                        PiperRemoteStream.H264 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                        PiperRemoteStream.HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                        PiperRemoteStream.JPEG -> -1
                    }
                }
                .maxByOrNull { it.level }
                ?.let { profile ->
                    setInteger(MediaFormat.KEY_PROFILE, profile.profile)
                    setInteger(MediaFormat.KEY_LEVEL, profile.level)
                }
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (stopped.get()) return
                try {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        listener.onVideoFrame(info.flags, info.presentationTimeUs, buffer.toByteArray())
                    }
                } catch (error: Throwable) {
                    listener.onVideoError(error)
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                if (!stopped.get()) listener.onVideoError(error)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val config = listOf("csd-0", "csd-1", "csd-2")
                    .mapNotNull { key -> format.getByteBuffer(key)?.duplicate()?.toByteArray() }
                    .fold(ByteArray(0)) { current, bytes -> current + ensureAnnexB(bytes) }
                listener.onVideoConfig(stream, width, height, fps, config)
            }
        }, Handler(callbackThread.looper))
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun requestKeyFrame() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !stopped.get()) {
            runCatching {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { codec.signalEndOfInputStream() }
        runCatching { codec.stop() }
        runCatching { inputSurface.release() }
        runCatching { codec.release() }
        callbackThread.quitSafely()
    }

    companion object {
        fun isHardwareSupported(stream: PiperRemoteStream, width: Int, height: Int, fps: Int): Boolean {
            val mime = when (stream) {
                PiperRemoteStream.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
                PiperRemoteStream.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
                PiperRemoteStream.JPEG -> return true
            }
            return findEncoder(mime, width, height, fps) != null
        }

        fun recommendedBitrate(stream: PiperRemoteStream, width: Int, height: Int, fps: Int): Int {
            val pixelsPerSecond = width.toLong() * height * fps
            // Screen content has sharp text and UI edges; it needs more bitrate than camera video.
            val bitsPerPixel = if (stream == PiperRemoteStream.HEVC) 0.18 else 0.28
            return (pixelsPerSecond * bitsPerPixel).toInt().coerceIn(6_000_000, 50_000_000)
        }

        private fun findEncoder(mime: String, width: Int, height: Int, fps: Int): String? {
            val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
            }
            fun supports(info: MediaCodecInfo): Boolean = runCatching {
                val capabilities = info.getCapabilitiesForType(mime)
                capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                    capabilities.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
            }.getOrDefault(false)
            return candidates.firstOrNull { info ->
                supports(info) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated)
            }?.name ?: candidates.firstOrNull(::supports)?.name
        }

        fun ensureAnnexB(data: ByteArray): ByteArray {
            if (data.size < 4) return data
            if ((data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) ||
                (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte())
            ) return data
            val output = ArrayList<Byte>(data.size + 16)
            var offset = 0
            while (offset + 4 <= data.size) {
                val size = ((data[offset].toInt() and 0xff) shl 24) or
                    ((data[offset + 1].toInt() and 0xff) shl 16) or
                    ((data[offset + 2].toInt() and 0xff) shl 8) or
                    (data[offset + 3].toInt() and 0xff)
                if (size <= 0 || offset + 4 + size > data.size) return data
                output.add(0); output.add(0); output.add(0); output.add(1)
                repeat(size) { output.add(data[offset + 4 + it]) }
                offset += 4 + size
            }
            return if (offset == data.size) output.toByteArray() else data
        }

        private fun ByteBuffer.toByteArray(): ByteArray {
            val result = ByteArray(remaining())
            get(result)
            return result
        }
    }
}
