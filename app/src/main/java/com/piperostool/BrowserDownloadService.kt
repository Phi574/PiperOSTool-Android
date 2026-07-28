package com.piperostool

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.URLUtil
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

class BrowserDownloadService : Service() {
    private data class TrackedDownload(
        val id: Long,
        val fileName: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private val trackedDownloads = mutableListOf<TrackedDownload>()
    private lateinit var downloadManager: DownloadManager
    private var foregroundStarted = false

    private val progressPoller = object : Runnable {
        override fun run() {
            if (trackedDownloads.isEmpty()) {
                stopTracking()
                return
            }

            var totalBytes = 0L
            var downloadedBytes = 0L
            var waiting = false
            val completed = mutableListOf<TrackedDownload>()

            trackedDownloads.toList().forEach { tracked ->
                downloadManager.query(DownloadManager.Query().setFilterById(tracked.id))?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        completed += tracked
                        return@use
                    }

                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val itemDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                        )
                    ).coerceAtLeast(0L)
                    val itemTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL,
                        DownloadManager.STATUS_FAILED -> completed += tracked

                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_PAUSED -> waiting = true
                    }

                    downloadedBytes += itemDownloaded
                    if (itemTotal > 0L) {
                        totalBytes += itemTotal
                    }
                }
            }

            trackedDownloads.removeAll(completed.toSet())
            if (trackedDownloads.isEmpty()) {
                stopTracking()
                return
            }

            val progress = if (totalBytes > 0L) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                null
            }
            notifyProgress(progress, waiting)
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        downloadManager = getSystemService(DownloadManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelTrackedDownloads()
                return START_NOT_STICKY
            }

            ACTION_DOWNLOAD -> enqueueDownload(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressPoller)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enqueueDownload(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!URLUtil.isNetworkUrl(url)) return

        if (!foregroundStarted) {
            startAsForeground(buildProgressNotification(null, false))
            foregroundStarted = true
        }

        val contentDisposition = intent.getStringExtra(EXTRA_CONTENT_DISPOSITION)
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val cookies = intent.getStringExtra(EXTRA_COOKIES)
        val suggestedFileName = intent.getStringExtra(EXTRA_SUGGESTED_FILE_NAME)
        val referrer = intent.getStringExtra(EXTRA_REFERRER)
        val fileName = resolveFileName(
            url = url,
            contentDisposition = contentDisposition,
            declaredMimeType = mimeType,
            suggestedFileName = suggestedFileName
        )
        val resolvedMimeType = resolveMimeType(fileName, mimeType)

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription(getString(R.string.browser_download_in_progress))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "PiperOS/$fileName"
            )

        if (!resolvedMimeType.isNullOrBlank()) request.setMimeType(resolvedMimeType)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        if (!cookies.isNullOrBlank()) request.addRequestHeader("Cookie", cookies)
        if (!referrer.isNullOrBlank() && URLUtil.isNetworkUrl(referrer)) {
            request.addRequestHeader("Referer", referrer)
        }

        runCatching {
            trackedDownloads += TrackedDownload(downloadManager.enqueue(request), fileName)
            handler.removeCallbacks(progressPoller)
            notifyProgress(null, false)
            handler.post(progressPoller)
        }.onFailure {
            if (trackedDownloads.isEmpty()) stopTracking()
        }
    }

    private fun notifyProgress(progress: Int?, waiting: Boolean) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (canNotify) {
            try {
                NotificationManagerCompat.from(this)
                    .notify(NOTIFICATION_ID, buildProgressNotification(progress, waiting))
            } catch (_: SecurityException) {
                // Some vendor ROMs may revoke notification access while a download is active.
            }
        }
    }

    private fun buildProgressNotification(progress: Int?, waiting: Boolean): Notification {
        val cancelIntent = Intent(this, BrowserDownloadService::class.java)
            .setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        val downloadsPendingIntent = PendingIntent.getActivity(
            this,
            DOWNLOADS_REQUEST_CODE,
            downloadsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val count = trackedDownloads.size.coerceAtLeast(1)
        val title = if (count == 1) {
            trackedDownloads.firstOrNull()?.fileName
                ?: getString(R.string.browser_download_title)
        } else {
            getString(R.string.browser_downloading_files, count)
        }
        val content = when {
            waiting -> getString(R.string.browser_download_waiting)
            progress != null -> getString(R.string.progress_notification_text, progress)
            else -> getString(R.string.browser_download_preparing)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_browser_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(downloadsPendingIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress ?: 0, progress == null)
            .addAction(
                R.drawable.ic_browser_close,
                getString(R.string.cancel),
                cancelPendingIntent
            )

        if (Build.VERSION.SDK_INT >= 36) {
            val style = NotificationCompat.ProgressStyle()
            if (progress == null) {
                style.setProgressIndeterminate(true)
            } else {
                style
                    .setProgress(progress)
                    .setStyledByProgress(true)
                    .setProgressTrackerIcon(
                        IconCompat.createWithResource(this, R.drawable.ic_browser_download)
                    )
                    .addProgressSegment(
                        NotificationCompat.ProgressStyle.Segment(100)
                            .setColor(ContextCompat.getColor(this, R.color.green_neon))
                    )
            }
            builder
                .setStyle(style)
                .setRequestPromotedOngoing(true)
            if (progress != null) builder.setShortCriticalText("$progress%")
        }

        return builder.build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun cancelTrackedDownloads() {
        handler.removeCallbacks(progressPoller)
        trackedDownloads.forEach { downloadManager.remove(it.id) }
        trackedDownloads.clear()
        stopTracking()
    }

    private fun stopTracking() {
        handler.removeCallbacks(progressPoller)
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.browser_download_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.browser_download_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun resolveFileName(
        url: String,
        contentDisposition: String?,
        declaredMimeType: String?,
        suggestedFileName: String?
    ): String {
        val suggested = suggestedFileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val guessed = URLUtil.guessFileName(url, contentDisposition, declaredMimeType)
        var fileName = (suggested ?: guessed)
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.')
            .take(180)
            .ifBlank { "PiperOS-download" }

        val currentExtension = fileName.substringAfterLast('.', "").lowercase()
        val resolvedMime = resolveMimeType(fileName, declaredMimeType)
        val expectedExtension = extensionForMimeType(resolvedMime)
        if (
            !expectedExtension.isNullOrBlank() &&
            (currentExtension.isBlank() || currentExtension in GENERIC_EXTENSIONS)
        ) {
            if (currentExtension in GENERIC_EXTENSIONS) {
                fileName = fileName.substringBeforeLast('.')
            }
            fileName = "$fileName.$expectedExtension"
        }
        return fileName
    }

    private fun resolveMimeType(fileName: String, declaredMimeType: String?): String? {
        val normalized = declaredMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeUnless { it in GENERIC_MIME_TYPES }
        if (normalized != null) return normalized

        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }

    private fun extensionForMimeType(mimeType: String?): String? {
        return when (mimeType) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "video/mp4" -> "mp4"
            "video/webm", "audio/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg", "application/ogg" -> "ogg"
            else -> mimeType?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.piperostool.browser.DOWNLOAD"
        const val ACTION_CANCEL = "com.piperostool.browser.CANCEL_DOWNLOADS"
        const val EXTRA_URL = "url"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_CONTENT_DISPOSITION = "content_disposition"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_SUGGESTED_FILE_NAME = "suggested_file_name"
        const val EXTRA_REFERRER = "referrer"

        private const val CHANNEL_ID = "browser_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val CANCEL_REQUEST_CODE = 4202
        private const val DOWNLOADS_REQUEST_CODE = 4203
        private const val POLL_INTERVAL_MS = 750L
        private val GENERIC_MIME_TYPES = setOf(
            "application/octet-stream",
            "binary/octet-stream",
            "application/download",
            "application/x-download"
        )
        private val GENERIC_EXTENSIONS = setOf("bin", "dat", "download", "tmp")
    }
}
