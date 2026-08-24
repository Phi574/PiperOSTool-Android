package com.piperostool

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class PiperRemoteShareService : Service() {
    private val running = AtomicBoolean(false)
    private val frameBusy = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOutput: DataOutputStream? = null
    private var discovery: PiperRemoteDiscoveryResponder? = null
    private var screenWidth = 1
    private var screenHeight = 1
    private var nativeWidth = 1
    private var nativeHeight = 1
    private var densityDpi = DisplayMetrics.DENSITY_DEFAULT
    private var targetFps = 30
    private var targetWidth = 720
    private var lastFrameAt = 0L
    private var paddedBitmap: Bitmap? = null
    private var outputBitmap: Bitmap? = null
    private val jpegStream = ByteArrayOutputStream(400_000)
    private val frameBufferLock = Any()
    private val touchPoints = mutableListOf<Pair<Float, Float>>()
    private val requestLock = Any()
    private var pendingDecision: PendingDecision? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running.get()) return
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
                ?.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
            if (metrics.widthPixels == nativeWidth && metrics.heightPixels == nativeHeight) return
            nativeWidth = metrics.widthPixels
            nativeHeight = metrics.heightPixels
            configureCapture(targetWidth, targetFps)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPROVE, ACTION_DENY -> {
                resolveRequest(
                    intent.getStringExtra(EXTRA_REQUEST_ID),
                    intent.action == ACTION_APPROVE
                )
                return START_NOT_STICKY
            }
        }
        if (running.get()) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val projectionData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val method = runCatching {
            PiperRemoteMethod.valueOf(intent?.getStringExtra(EXTRA_METHOD).orEmpty())
        }.getOrDefault(PiperRemoteMethod.LAN)
        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        runCatching { startSession(resultCode, projectionData, method) }
            .onFailure {
                publishState(null, it.message ?: getString(R.string.remote_share_failed))
                stopSelf()
            }
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int, data: Intent, method: PiperRemoteMethod) {
        if (!running.compareAndSet(false, true)) return
        // USB is exposed only through `adb forward`, so it has a fixed local
        // device port and never participates in Wi-Fi discovery.
        val server = ServerSocket(if (method == PiperRemoteMethod.USB) PiperRemoteProtocol.USB_PORT else 0)
            .also { serverSocket = it }
        val session = PiperRemoteSession(
            method = method,
            port = server.localPort,
            token = PiperRemoteProtocol.randomToken(),
            code = PiperRemoteProtocol.randomCode(),
            sessionId = PiperRemoteProtocol.randomToken(12),
            host = PiperRemoteProtocol.localIpv4()
        )
        currentSession = session
        val metrics = resources.displayMetrics
        nativeWidth = metrics.widthPixels.coerceAtLeast(2)
        nativeHeight = metrics.heightPixels.coerceAtLeast(2)
        densityDpi = metrics.densityDpi
        configureDimensions(720)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(mainLooper))
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = (manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException(getString(R.string.remote_share_failed))).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(mainLooper))
        }
        imageThread = HandlerThread("PiperRemoteCapture").also { it.start() }
        val handler = Handler(imageThread!!.looper)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ source -> captureFrame(source) }, handler)
        }
        virtualDisplay = projection?.createVirtualDisplay(
            "PiperOS Remote",
            screenWidth,
            screenHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
        if (method != PiperRemoteMethod.USB) {
            discovery = PiperRemoteDiscoveryResponder({ currentSession }, PiperRemoteProtocol.deviceName(this)).also { it.start() }
        }
        Thread({ acceptClients(server, session) }, "PiperRemoteServer").start()
        publishState(session, null)
    }

    private fun acceptClients(server: ServerSocket, session: PiperRemoteSession) {
        while (running.get()) {
            try {
                val socket = server.accept()
                Thread({ handleClient(socket, session) }, "PiperRemotePeer").start()
            } catch (_: Exception) {
                if (!running.get()) return
            }
        }
    }

    private fun handleClient(socket: Socket, session: PiperRemoteSession) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 512 * 1024
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val magic = input.readUTF()
            val method = runCatching { PiperRemoteMethod.valueOf(input.readUTF()) }.getOrNull()
            val credential = input.readUTF()
            val requesterName = input.readUTF().take(80)
            val requestedWidth = input.readInt().let { if (it == 0) nativeWidth else it.coerceIn(480, 1080) }
            val requestedFps = input.readInt().let {
                when {
                    it == 0 -> maximumDeviceFps()
                    it in setOf(24, 30, 60) -> it
                    else -> 30
                }
            }
            val credentialsValid = magic == PiperRemoteProtocol.MAGIC && method == session.method && when (method) {
                PiperRemoteMethod.LAN -> credential == session.sessionId
                PiperRemoteMethod.QR -> credential == session.token
                PiperRemoteMethod.CODE -> credential == session.code
                PiperRemoteMethod.USB -> credential == PiperRemoteProtocol.USB_CREDENTIAL
            }
            val allowed = credentialsValid && awaitApproval(
                requesterName.ifBlank { getString(R.string.remote_unknown_device) },
                socket.inetAddress?.hostAddress.orEmpty(),
                requestedWidth,
                requestedFps
            )
            output.writeBoolean(allowed)
            if (!allowed) {
                output.writeUTF(
                    if (credentialsValid) getString(R.string.remote_request_denied)
                    else getString(R.string.remote_access_rejected)
                )
                output.flush()
                return
            }
            configureCapture(requestedWidth, requestedFps)
            output.writeInt(screenWidth)
            output.writeInt(screenHeight)
            output.flush()
            synchronized(this) {
                clientSocket?.close()
                clientSocket = socket
                clientOutput = output
            }
            startAudioCapture(output)
            while (running.get() && !socket.isClosed) {
                when (input.readByte()) {
                    PiperRemoteProtocol.PACKET_TOUCH -> {
                        val action = input.readInt()
                        val x = input.readFloat().coerceIn(0f, 1f)
                        val y = input.readFloat().coerceIn(0f, 1f)
                        synchronized(touchPoints) {
                            when (action) {
                                0 -> {
                                    touchPoints.clear()
                                    touchPoints += x to y
                                }
                                2 -> touchPoints += x to y
                                1 -> {
                                    touchPoints += x to y
                                    PiperRemoteAccessibilityService.gesture(
                                        touchPoints.toList(),
                                        nativeWidth,
                                        nativeHeight
                                    )
                                    touchPoints.clear()
                                }
                            }
                        }
                    }
                    PiperRemoteProtocol.PACKET_BACK -> PiperRemoteAccessibilityService.back()
                    PiperRemoteProtocol.PACKET_HOME -> PiperRemoteAccessibilityService.home()
                }
            }
        } catch (_: Exception) {
        } finally {
            if (clientSocket === socket) {
                stopAudioCapture()
                clientOutput = null
                clientSocket = null
            }
            runCatching { socket.close() }
        }
    }

    private fun captureFrame(reader: ImageReader) {
        if (reader !== imageReader) {
            reader.acquireLatestImage()?.close()
            return
        }
        val image = reader.acquireLatestImage() ?: return
        val now = System.currentTimeMillis()
        val interval = 1000L / targetFps.coerceIn(24, 60)
        if (now - lastFrameAt < interval || clientOutput == null || !frameBusy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now
        try {
            val frameWidth = reader.width
            val frameHeight = reader.height
            val jpeg = synchronized(frameBufferLock) {
                imageToJpeg(image, frameWidth, frameHeight)
            }
            val output = clientOutput ?: return
            synchronized(output) {
                output.writeByte(PiperRemoteProtocol.PACKET_FRAME.toInt())
                output.writeInt(frameWidth)
                output.writeInt(frameHeight)
                output.writeInt(jpeg.size)
                output.write(jpeg)
                output.flush()
            }
        } catch (_: Exception) {
            runCatching { clientSocket?.close() }
        } finally {
            frameBusy.set(false)
            image.close()
        }
    }

    private fun imageToJpeg(image: Image, frameWidth: Int, frameHeight: Int): ByteArray {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val bitmap = paddedBitmap?.takeIf {
            it.width == paddedWidth && it.height == frameHeight && !it.isRecycled
        } ?: Bitmap.createBitmap(paddedWidth, frameHeight, Bitmap.Config.ARGB_8888).also {
            paddedBitmap?.recycle()
            paddedBitmap = it
        }
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        val output = if (paddedWidth == frameWidth) bitmap else {
            outputBitmap?.takeIf {
                it.width == frameWidth && it.height == frameHeight && !it.isRecycled
            } ?: Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888).also {
                outputBitmap?.recycle()
                outputBitmap = it
            }
        }
        if (output !== bitmap) {
            Canvas(output).drawBitmap(
                bitmap,
                Rect(0, 0, frameWidth, frameHeight),
                Rect(0, 0, frameWidth, frameHeight),
                null
            )
        }
        jpegStream.reset()
        output.compress(Bitmap.CompressFormat.JPEG, if (targetFps >= 60) 50 else 58, jpegStream)
        return jpegStream.toByteArray()
    }

    private fun configureDimensions(targetWidth: Int) {
        val scale = minOf(1f, targetWidth.toFloat() / nativeWidth)
        screenWidth = (nativeWidth * scale).roundToInt().coerceAtLeast(2)
        screenHeight = (nativeHeight * scale).roundToInt().coerceAtLeast(2)
    }

    private fun maximumDeviceFps(): Int {
        val refreshRate = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.mode
            ?.refreshRate
            ?.roundToInt()
            ?: 60
        return refreshRate.coerceIn(24, 120)
    }

    private fun configureCapture(width: Int, fps: Int) {
        targetWidth = width
        targetFps = fps
        lastFrameAt = 0L
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        configureDimensions(width)
        synchronized(frameBufferLock) {
            paddedBitmap?.recycle()
            outputBitmap?.recycle()
            paddedBitmap = null
            outputBitmap = null
            jpegStream.reset()
        }
        if (screenWidth == oldWidth && screenHeight == oldHeight) {
            imageReader?.acquireLatestImage()?.close()
            return
        }
        val handler = imageThread?.looper?.let(::Handler) ?: return
        val replacement = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ source -> captureFrame(source) }, handler)
        }
        val previous = imageReader
        imageReader = replacement
        virtualDisplay?.surface = null
        virtualDisplay?.resize(screenWidth, screenHeight, densityDpi)
        virtualDisplay?.surface = replacement.surface
        previous?.setOnImageAvailableListener(null, null)
        previous?.close()
    }

    private fun startAudioCapture(output: DataOutputStream) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        stopAudioCapture()
        val mediaProjection = projection ?: return
        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val channelCount = 2
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val capture = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum * 2, 16_384))
                .build()
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return
        audioRecord = record
        synchronized(output) {
            output.writeByte(PiperRemoteProtocol.PACKET_AUDIO_CONFIG.toInt())
            output.writeInt(sampleRate)
            output.writeInt(channelCount)
            output.flush()
        }
        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(3_840)
            runCatching { record.startRecording() }
            while (running.get() && audioRecord === record && clientOutput === output) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                runCatching {
                    synchronized(output) {
                        output.writeByte(PiperRemoteProtocol.PACKET_AUDIO.toInt())
                        output.writeInt(read)
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                }.onFailure { return@Thread }
            }
        }, "PiperRemoteAudio").also { it.start() }
    }

    private fun stopAudioCapture() {
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        audioThread?.interrupt()
        audioThread = null
    }

    private fun awaitApproval(name: String, address: String, width: Int, fps: Int): Boolean {
        val decision = synchronized(requestLock) {
            if (pendingDecision != null) return false
            PendingDecision(
                request = ConnectionRequest(UUID.randomUUID().toString(), name, address, width, fps),
                latch = CountDownLatch(1)
            ).also {
                pendingDecision = it
                currentRequest = it.request
            }
        }
        publishState(currentSession, null)
        updateRequestNotification(decision.request)
        decision.latch.await(30, TimeUnit.SECONDS)
        val approved = decision.approved == true
        synchronized(requestLock) {
            if (pendingDecision === decision) pendingDecision = null
            if (currentRequest?.id == decision.request.id) currentRequest = null
        }
        publishState(currentSession, if (!approved) getString(R.string.remote_request_expired_or_denied) else null)
        startForegroundNow()
        return approved
    }

    private fun resolveRequest(id: String?, approved: Boolean) {
        synchronized(requestLock) {
            val decision = pendingDecision ?: return
            if (id != decision.request.id) return
            decision.approved = approved
            decision.latch.countDown()
        }
    }

    private fun updateRequestNotification(request: ConnectionRequest) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun action(action: String, requestCode: Int) = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PiperRemoteShareService::class.java)
                .setAction(action)
                .putExtra(EXTRA_REQUEST_ID, request.id),
            flags
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remote_view)
            .setContentTitle(getString(R.string.remote_connection_request))
            .setContentText(getString(R.string.remote_connection_request_from, request.deviceName))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.remote_deny), action(ACTION_DENY, 203))
            .addAction(0, getString(R.string.remote_allow), action(ACTION_APPROVE, 204))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundNow() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.piperos_remote), NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, PiperRemoteShareService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 201, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPending = PendingIntent.getActivity(
            this,
            202,
            Intent(this, PiperRemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remote_view)
            .setContentTitle(getString(R.string.remote_sharing_active))
            .setContentText(getString(R.string.remote_sharing_notification))
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.remote_stop), stopPending)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )
    }

    private fun publishState(session: PiperRemoteSession?, error: String?) {
        currentSession = session
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_ERROR, error))
    }

    override fun onDestroy() {
        running.set(false)
        stopAudioCapture()
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        synchronized(requestLock) {
            pendingDecision?.approved = false
            pendingDecision?.latch?.countDown()
            pendingDecision = null
            currentRequest = null
        }
        discovery?.stop()
        discovery = null
        runCatching { serverSocket?.close() }
        runCatching { clientSocket?.close() }
        clientOutput = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        paddedBitmap?.recycle()
        outputBitmap?.recycle()
        paddedBitmap = null
        outputBitmap = null
        runCatching { projection?.stop() }
        imageThread?.quitSafely()
        serverSocket = null
        clientSocket = null
        virtualDisplay = null
        imageReader = null
        projection = null
        publishState(null, null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STATE = "com.piperostool.remote.STATE"
        const val ACTION_STOP = "com.piperostool.remote.STOP"
        const val ACTION_APPROVE = "com.piperostool.remote.APPROVE"
        const val ACTION_DENY = "com.piperostool.remote.DENY"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_METHOD = "method"
        const val EXTRA_ERROR = "error"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val CHANNEL_ID = "piper_remote_share"
        private const val NOTIFICATION_ID = 8120
        @Volatile var currentSession: PiperRemoteSession? = null
            private set
        @Volatile var currentRequest: ConnectionRequest? = null
            private set

        fun stop(context: Context) {
            context.startService(Intent(context, PiperRemoteShareService::class.java).setAction(ACTION_STOP))
        }
    }

    data class ConnectionRequest(
        val id: String,
        val deviceName: String,
        val address: String,
        val targetWidth: Int,
        val targetFps: Int
    )

    private data class PendingDecision(
        val request: ConnectionRequest,
        val latch: CountDownLatch,
        @Volatile var approved: Boolean? = null
    )
}
