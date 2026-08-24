package com.piperostool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.security.SecureRandom
import kotlin.math.roundToInt

class PiperAppleMirrorService : Service(), RaopCallbackHandler, LogListener {
    private val binder = LocalBinder()
    private val audioRenderer = PiperAirPlayAudioRenderer()
    private lateinit var audioManager: AudioManager
    val videoRenderer by lazy { VideoRenderer(this) }

    private var nativeHandle = 0L
    private var nsd: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var clients = 0
    private var sourceWidth = 0
    private var sourceHeight = 0

    inner class LocalBinder : Binder() {
        val service: PiperAppleMirrorService get() = this@PiperAppleMirrorService
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopReceiver()
            ACTION_START -> startReceiver(
                intent.getStringExtra(EXTRA_NAME).orEmpty(),
                intent.getIntExtra(EXTRA_WIDTH, 1920),
                intent.getIntExtra(EXTRA_HEIGHT, 1080),
                intent.getIntExtra(EXTRA_FPS, 60)
            )
        }
        return START_NOT_STICKY
    }

    fun attachSurface(surface: Surface) = videoRenderer.setSurface(surface)

    fun detachSurface(surface: Surface) = videoRenderer.clearSurface(surface)

    private fun startReceiver(requestedName: String, width: Int, height: Int, fps: Int) {
        if (nativeHandle != 0L) {
            publishState(STATE_RUNNING)
            return
        }
        startForeground(NOTIFICATION_ID, notification(getString(R.string.apple_mirror_starting)))
        val name = requestedName.trim().ifBlank { getString(R.string.apple_mirror_default_name) }
        val safeWidth = width.coerceIn(640, 3840)
        val safeHeight = height.coerceIn(360, 2160)
        val safeFps = fps.coerceIn(24, 120)
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "piperos:airplay")
                .apply { acquire() }
            nsd = NsdServiceManager(this).apply { acquireMulticastLock() }
            NativeBridge.nativeSetDefaultStreamValues(
                audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
                audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
            )
            nativeHandle = NativeBridge.nativeInit(
                this,
                persistentHardwareAddress(),
                name,
                filesDir.resolve("piperos-airplay.pem").absolutePath,
                true,
                false
            )
            check(nativeHandle != 0L) { "nativeInit returned 0" }
            audioRenderer.attach(nativeHandle)
            val h265 = videoRenderer.selectDecoders(safeWidth, safeHeight, safeFps, true)
            videoRenderer.setResolution(safeWidth, safeHeight)
            NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
            NativeBridge.nativeSetCodecs(nativeHandle, true, true)
            NativeBridge.nativeSetHlsEnabled(nativeHandle, false)
            NativeBridge.nativeSetAudioEnabled(nativeHandle, true)
            NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", safeFps)
            NativeBridge.nativeSetPlist(nativeHandle, "overscanned", 0)
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", 0)
            NativeBridge.nativeSetDisplaySize(nativeHandle, safeWidth, safeHeight, safeFps)

            var port = -1
            for (candidate in 7000..7010) {
                port = NativeBridge.nativeStart(nativeHandle, candidate)
                if (port > 0) break
            }
            check(port > 0) { "No AirPlay port is available" }
            val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle).orEmpty()
            val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle).orEmpty()
            val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: name
            val serviceName = NativeBridge.nativeGetServerName(nativeHandle) ?: name
            nsd?.registerRaop(raopName, port, raopTxt)
            nsd?.registerAirplay(serviceName, port, airplayTxt)
            currentName = serviceName
            isRunning = true
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(getString(R.string.apple_mirror_waiting, serviceName)))
            publishState(STATE_RUNNING)
        } catch (error: Throwable) {
            publishState(STATE_ERROR, error.message ?: error.javaClass.simpleName)
            stopReceiver()
        }
    }

    private fun stopReceiver() {
        isMirroring = false
        isRunning = false
        clients = 0
        runCatching { audioRenderer.detach() }
        runCatching { videoRenderer.stopSession() }
        if (nativeHandle != 0L) {
            runCatching { NativeBridge.nativeStop(nativeHandle) }
            runCatching { NativeBridge.nativeDestroy(nativeHandle) }
            nativeHandle = 0L
        }
        nsd?.release()
        nsd = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        publishState(STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persistentHardwareAddress(): ByteArray {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val stored = prefs.getString(KEY_HARDWARE_ADDRESS, null)
        if (stored != null) return stored.split(':').map { it.toInt(16).toByte() }.toByteArray()
        val bytes = ByteArray(6).also(SecureRandom()::nextBytes)
        bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
        prefs.edit().putString(KEY_HARDWARE_ADDRESS, bytes.joinToString(":") { "%02X".format(it) }).apply()
        return bytes
    }

    private fun publishState(state: String, error: String? = null) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_ERROR, error)
            .putExtra(EXTRA_WIDTH, sourceWidth)
            .putExtra(EXTRA_HEIGHT, sourceHeight))
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_remote_view)
        .setContentTitle(getString(R.string.apple_mirror_title))
        .setContentText(message)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(
            this,
            0,
            Intent(this, PiperAppleMirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        .addAction(0, getString(R.string.remote_stop), PendingIntent.getService(
            this,
            1,
            Intent(this, PiperAppleMirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.apple_mirror_title), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) =
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)

    override fun onLog(msg: String) {
        Log.i(TAG, msg)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) = audioRenderer.setFormat(ct, spf)

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        if (w <= 0 || h <= 0) return
        sourceWidth = w.roundToInt()
        sourceHeight = h.roundToInt()
        videoRenderer.setResolution(sourceWidth, sourceHeight)
        publishState(STATE_MIRRORING)
    }

    override fun onVolumeChange(volume: Float) {
        val fraction = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * maximum).roundToInt(), 0)
    }

    override fun onClientVolume(): Float {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (current == 0) -144f else -30f + 30f * current / maximum
    }

    override fun onAudioTeardown() = audioRenderer.stop()
    override fun onConnectionInit() { clients++; publishState(STATE_CONNECTED) }
    override fun onConnectionDestroy() { clients = (clients - 1).coerceAtLeast(0); if (clients == 0) publishState(STATE_RUNNING) }
    override fun onConnectionReset(reason: Int) = publishState(STATE_RUNNING)
    override fun onDisplayPin(pin: String) = Unit
    override fun onMetadata(data: ByteArray) = Unit
    override fun onCoverArt(data: ByteArray) = Unit
    override fun onProgress(start: Long, curr: Long, end: Long) = Unit
    override fun onDacpId(dacpId: String, activeRemote: String) = Unit
    override fun onMirrorRunning(running: Boolean) {
        isMirroring = running
        if (running) {
            videoRenderer.startSession()
            publishState(STATE_MIRRORING)
        } else {
            videoRenderer.stopSession()
            publishState(if (clients > 0) STATE_CONNECTED else STATE_RUNNING)
        }
    }
    override fun onVideoPlay(location: String, startPositionSeconds: Float) = Unit
    override fun onVideoScrub(positionSeconds: Float) = Unit
    override fun onVideoRate(rate: Float) = Unit
    override fun onVideoStop() = Unit
    override fun onVideoSessionPoll() = Unit

    override fun onDestroy() {
        if (nativeHandle != 0L) stopReceiver()
        videoRenderer.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STATE = "com.piperostool.airplay.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val EXTRA_NAME = "name"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val STATE_STOPPED = "stopped"
        const val STATE_RUNNING = "running"
        const val STATE_CONNECTED = "connected"
        const val STATE_MIRRORING = "mirroring"
        const val STATE_ERROR = "error"
        private const val ACTION_START = "com.piperostool.airplay.START"
        private const val ACTION_STOP = "com.piperostool.airplay.STOP"
        private const val CHANNEL_ID = "piperos_airplay_receiver"
        private const val NOTIFICATION_ID = 3253
        private const val PREFS = "piperos_airplay"
        private const val KEY_HARDWARE_ADDRESS = "hardware_address"
        private const val TAG = "PiperAppleMirror"

        @Volatile var isRunning = false
            private set
        @Volatile var isMirroring = false
            private set
        @Volatile var currentName = ""
            private set

        fun start(context: Context, name: String, width: Int, height: Int, fps: Int) {
            ContextCompat.startForegroundService(context, Intent(context, PiperAppleMirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_FPS, fps))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PiperAppleMirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
