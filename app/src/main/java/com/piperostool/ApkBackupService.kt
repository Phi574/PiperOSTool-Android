package com.piperostool

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ApkBackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backupJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            backupJob?.cancel()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || backupJob?.isActive == true) return START_NOT_STICKY
        val workspace = intent.getStringExtra(EXTRA_WORKSPACE_ROOT)?.let(ApkWorkspace::restore)
        val destination = intent.getStringExtra(EXTRA_DESTINATION_URI)?.let(android.net.Uri::parse)
            ?.let { DocumentFile.fromTreeUri(this, it) }
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        if (workspace == null || destination == null || paths.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        cancelled.set(false)
        startAsForeground(buildProgressNotification(null))
        acquireWakeLock()
        backupJob = scope.launch {
            try {
                val result = ApkBackupEngine(this@ApkBackupService, workspace, destination).export(
                    paths,
                    isCancelled = { cancelled.get() }
                ) { progress ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS || progress.completedFiles == progress.totalFiles) {
                        lastNotificationAt = now
                        if (canPostNotifications()) {
                            NotificationManagerCompat.from(this@ApkBackupService)
                                .notify(NOTIFICATION_ID, buildProgressNotification(progress))
                        }
                    }
                }
                showTerminal("Backup APK hoàn tất", "${result.fileCount} tệp • ${Formatter.formatShortFileSize(this@ApkBackupService, result.byteCount)}", false)
            } catch (_: CancellationException) {
                showTerminal("Đã hủy backup APK", "Các tệp đã chép vẫn được giữ lại", false)
            } catch (error: Throwable) {
                if (cancelled.get()) {
                    showTerminal("Đã hủy backup APK", "Các tệp đã chép vẫn được giữ lại", false)
                } else {
                    showTerminal("Backup APK thất bại", error.cause?.message ?: error.message ?: "Lỗi không xác định", true)
                }
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        backupJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildProgressNotification(progress: ApkBackupProgress?): Notification {
        val percent = progress?.percent
        val content = if (progress == null) "Đang lập danh sách tệp..." else {
            val speed = if (progress.elapsedMillis > 0) progress.copiedBytes * 1000 / progress.elapsedMillis else 0L
            "${progress.completedFiles}/${progress.totalFiles} tệp • ${Formatter.formatShortFileSize(this, speed)}/s"
        }
        val cancelIntent = PendingIntent.getService(
            this, CANCEL_REQUEST_CODE,
            Intent(this, ApkBackupService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.backup)
            .setContentTitle("PiperOS đang backup APK")
            .setContentText(content)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(R.drawable.ic_browser_close, "Hủy", cancelIntent)
        if (Build.VERSION.SDK_INT >= 36) {
            val style = NotificationCompat.ProgressStyle()
            if (percent == null) style.setProgressIndeterminate(true) else style
                .setProgress(percent)
                .setStyledByProgress(true)
                .setProgressTrackerIcon(IconCompat.createWithResource(this, R.drawable.backup))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100).setColor(ContextCompat.getColor(this, R.color.green_neon)))
            builder.setStyle(style).setRequestPromotedOngoing(true)
            if (percent != null) builder.setShortCriticalText("$percent%")
        }
        return builder.build()
    }

    private fun showTerminal(title: String, content: String, isError: Boolean) {
        if (!canPostNotifications()) return
        NotificationManagerCompat.from(this).notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(if (isError) R.drawable.ic_browser_close else R.drawable.check_circle)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:apk-backup")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "APK backup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tiến trình sao lưu tệp từ APK"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val ACTION_START = "com.piperostool.apkbackup.START"
        const val ACTION_CANCEL = "com.piperostool.apkbackup.CANCEL"
        const val EXTRA_WORKSPACE_ROOT = "workspace_root"
        const val EXTRA_DESTINATION_URI = "destination_uri"
        const val EXTRA_PATHS = "paths"
        private const val CHANNEL_ID = "apk_backup"
        private const val NOTIFICATION_ID = 4601
        private const val RESULT_NOTIFICATION_ID = 4602
        private const val CANCEL_REQUEST_CODE = 4603
        private const val NOTIFICATION_INTERVAL_MS = 400L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
