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
        currentAction = action
        startAsForeground(notification("Đang chuẩn bị...", null))
        sendProgress(FileOperationProgress(0, 0, "Đang chuẩn bị"))
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
                    ACTION_DELETE -> {
                        delete(sources, onProgress)
                        message = "Đã xóa ${sources.size} mục"
                    }
                    ACTION_COPY -> {
                        copyOrMove(sources, target, false, onProgress)
                        message = "Đã sao chép ${sources.size} mục"
                    }
                    ACTION_MOVE -> {
                        copyOrMove(sources, target, true, onProgress)
                        message = "Đã di chuyển ${sources.size} mục"
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
                currentAction = null
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

    private fun delete(
        sources: List<File>,
        progress: (FileOperationProgress) -> Unit
    ) {
        val entries = sources
            .flatMap { source -> if (source.isDirectory) source.walkBottomUp().toList() else listOf(source) }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        entries.forEachIndexed { index, source ->
            check(!cancelled.get()) { "Cancelled" }
            check(!source.exists() || source.delete()) { "Không thể xóa ${source.name}" }
            progress(FileOperationProgress(index + 1, entries.size.coerceAtLeast(1), source.name))
        }
    }

    private fun copyOrMove(
        sources: List<File>,
        targetDirectory: File,
        move: Boolean,
        progress: (FileOperationProgress) -> Unit
    ) {
        targetDirectory.mkdirs()
        require(targetDirectory.isDirectory) { "Thư mục đích không hợp lệ" }
        sources.forEach { source ->
            val sourcePath = source.canonicalPath
            val targetPath = targetDirectory.canonicalPath
            require(targetPath != sourcePath && !targetPath.startsWith(sourcePath + File.separator)) {
                "Không thể chép thư mục vào bên trong chính nó"
            }
        }
        val files = sources.flatMap { source ->
            if (source.isDirectory) source.walkTopDown().filter(File::isFile).toList() else listOf(source)
        }
        var completed = 0
        sources.forEach { source ->
            check(!cancelled.get()) { "Cancelled" }
            val destinationRoot = uniqueTarget(targetDirectory, source.name)
            if (source.isDirectory && files.none { it.absolutePath.startsWith(source.absolutePath + File.separator) }) {
                destinationRoot.mkdirs()
            }
            val sourceFiles = if (source.isDirectory) {
                files.filter { it.absolutePath.startsWith(source.absolutePath + File.separator) }
            } else listOf(source)
            sourceFiles.forEach { input ->
                check(!cancelled.get()) { "Cancelled" }
                val output = if (source.isDirectory) {
                    File(destinationRoot, input.relativeTo(source).path)
                } else destinationRoot
                output.parentFile?.mkdirs()
                input.inputStream().use { sourceStream ->
                    output.outputStream().use { targetStream ->
                        sourceStream.copyTo(targetStream, DEFAULT_BUFFER_SIZE)
                    }
                }
                output.setLastModified(input.lastModified())
                completed++
                progress(FileOperationProgress(completed, files.size.coerceAtLeast(1), input.name))
            }
        }
        if (move) sources.forEach {
            check(!cancelled.get()) { "Cancelled" }
            check(it.deleteRecursively()) { "Không thể xóa bản gốc ${it.name}" }
        }
    }

    private fun uniqueTarget(parent: File, requestedName: String): File {
        val requested = File(parent, requestedName)
        if (!requested.exists()) return requested
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(parent, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun updateProgress(progress: FileOperationProgress) {
        sendProgress(progress)
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

    private fun sendProgress(progress: FileOperationProgress) {
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_ACTION, currentAction)
                .putExtra(EXTRA_COMPLETED, progress.completed)
                .putExtra(EXTRA_TOTAL, progress.total)
                .putExtra(EXTRA_PERCENT, progress.percent)
                .putExtra(EXTRA_CURRENT_NAME, progress.currentName)
        )
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
        @Volatile
        var currentAction: String? = null
            private set
        const val ACTION_COMPRESS = "com.piperostool.file.COMPRESS"
        const val ACTION_EXTRACT = "com.piperostool.file.EXTRACT"
        const val ACTION_BACKUP = "com.piperostool.file.BACKUP"
        const val ACTION_DELETE = "com.piperostool.file.DELETE"
        const val ACTION_COPY = "com.piperostool.file.COPY"
        const val ACTION_MOVE = "com.piperostool.file.MOVE"
        const val ACTION_CANCEL = "com.piperostool.file.CANCEL"
        const val ACTION_PROGRESS = "com.piperostool.file.PROGRESS"
        const val ACTION_FINISHED = "com.piperostool.file.FINISHED"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCES = "sources"
        const val EXTRA_TARGET = "target"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACTION = "action"
        const val EXTRA_COMPLETED = "completed"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_CURRENT_NAME = "current_name"
        private const val CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 4701
        private const val RESULT_NOTIFICATION_ID = 4702
        private const val CANCEL_REQUEST_CODE = 4703
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
