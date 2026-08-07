package com.piperostool

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class FileOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            return START_NOT_STICKY
        }
        if (job?.isActive == true || intent == null) return START_NOT_STICKY
        val sources = intent.getStringArrayListExtra(EXTRA_SOURCES)
            ?.map(::File)
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_SOURCE)?.let { listOf(File(it)) }
            ?: return START_NOT_STICKY
        val target = intent.getStringExtra(EXTRA_TARGET)?.let(::File) ?: return START_NOT_STICKY
        val action = intent.action ?: return START_NOT_STICKY
        cancelled.set(false)
        running = true
        startAsForeground(notification("Đang chuẩn bị...", null))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:file-operation")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        job = scope.launch {
            var success = false
            var message = ""
            try {
                val password = intent.getStringExtra(EXTRA_PASSWORD)
                val onProgress: (FileOperationProgress) -> Unit = { updateProgress(it) }
                when (action) {
                    ACTION_COMPRESS -> {
                        val format = FileArchiveFormat.valueOf(requireNotNull(intent.getStringExtra(EXTRA_FORMAT)))
                        val preset = ArchiveCompressionPreset.valueOf(requireNotNull(intent.getStringExtra(EXTRA_PRESET)))
                        FileArchiveEngine.compress(sources, target, format, preset, password, cancelled::get, onProgress)
                        message = "Đã tạo ${target.name}"
                    }
                    ACTION_EXTRACT -> {
                        FileArchiveEngine.extract(sources.first(), target, password, cancelled::get, onProgress)
                        message = "Đã giải nén vào ${target.name}"
                    }
                    ACTION_BACKUP -> {
                        backup(sources, target, onProgress)
                        message = "Backup complete: ${sources.size} selected item(s)"
                    }
                }
                success = !cancelled.get()
                if (!success) message = "Tác vụ đã được hủy"
            } catch (error: Throwable) {
                message = error.cause?.message ?: error.message ?: "Lỗi không xác định"
                if (action == ACTION_COMPRESS) target.delete()
            } finally {
                releaseWakeLock()
                running = false
                sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra(EXTRA_SUCCESS, success).putExtra(EXTRA_MESSAGE, message))
                showResult(success, message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled.set(true)
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun backup(
        sources: List<File>,
        target: File,
        progress: (FileOperationProgress) -> Unit
    ) {
        target.mkdirs()
        val files = sources.flatMap { source ->
            if (source.isDirectory) source.walkTopDown().filter(File::isFile).toList()
            else listOf(source)
        }
        files.forEachIndexed { index, file ->
            check(!cancelled.get()) { "Cancelled" }
            val root = sources.first { source ->
                file.absolutePath == source.absolutePath ||
                    file.absolutePath.startsWith(source.absolutePath + File.separator)
            }
            val relative = if (root.isDirectory) file.relativeTo(root).path else ""
            val output = if (relative.isEmpty()) {
                File(target, root.name)
            } else {
                File(File(target, root.name), relative)
            }
            output.parentFile?.mkdirs()
            file.copyTo(output, overwrite = true)
            progress(FileOperationProgress(index + 1, files.size, file.name))
        }
    }

    private fun updateProgress(progress: FileOperationProgress) {
        if (canPostNotifications()) {
            notifySafely(
                NOTIFICATION_ID,
                notification(
                    if (progress.total > 0) "${progress.completed}/${progress.total} • ${progress.currentName}"
                    else "${progress.completed} tệp • ${progress.currentName}",
                    progress.percent
                )
            )
        }
    }

    private fun notification(text: String, percent: Int?): Notification {
        val cancel = PendingIntent.getService(
            this, CANCEL_REQUEST_CODE,
            Intent(this, FileOperationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.packaget)
            .setContentTitle("PiperOS File Manager")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(R.drawable.ic_browser_close, "Hủy", cancel)
        if (Build.VERSION.SDK_INT >= 36) {
            val style = NotificationCompat.ProgressStyle()
            if (percent == null) style.setProgressIndeterminate(true) else style
                .setProgress(percent)
                .setStyledByProgress(true)
                .setProgressTrackerIcon(IconCompat.createWithResource(this, R.drawable.packaget))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100).setColor(ContextCompat.getColor(this, R.color.green_neon)))
            builder.setStyle(style).setRequestPromotedOngoing(true)
            if (percent != null) builder.setShortCriticalText("$percent%")
        }
        return builder.build()
    }

    private fun showResult(success: Boolean, message: String) {
        if (!canPostNotifications()) return
        notifySafely(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(if (success) R.drawable.check_circle else R.drawable.ic_browser_close)
                .setContentTitle(if (success) "Tác vụ tệp hoàn tất" else "Tác vụ tệp thất bại")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()
        )
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(id: Int, value: Notification) {
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(this).notify(id, value)
        } catch (_: SecurityException) {
            // Permission can be revoked while a background operation is running.
        }
    }

    private fun startAsForeground(value: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, value)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "File Manager", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tiến trình nén và giải nén tệp"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        @Volatile
        var running: Boolean = false
            private set
        const val ACTION_COMPRESS = "com.piperostool.file.COMPRESS"
        const val ACTION_EXTRACT = "com.piperostool.file.EXTRACT"
        const val ACTION_BACKUP = "com.piperostool.file.BACKUP"
        const val ACTION_CANCEL = "com.piperostool.file.CANCEL"
        const val ACTION_FINISHED = "com.piperostool.file.FINISHED"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCES = "sources"
        const val EXTRA_TARGET = "target"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 4701
        private const val RESULT_NOTIFICATION_ID = 4702
        private const val CANCEL_REQUEST_CODE = 4703
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
