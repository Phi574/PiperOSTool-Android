package com.piperostool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal object AppUpdateDownload {
    const val WORK_NAME = "piperos_update_download"
    const val KEY_TAG = "release_tag"
    const val KEY_BYTES = "download_bytes"
    const val KEY_TOTAL = "download_total"
    const val KEY_PERCENT = "download_percent"
    const val KEY_APK_PATH = "download_apk_path"
    const val KEY_ERROR = "download_error"
    const val TAG_PREFIX = "piperos_release_"
    private const val CHANNEL_ID = "piperos_update_download"
    internal const val NOTIFICATION_ID = 379

    fun enqueue(context: Context, release: AppRelease) {
        val input = Data.Builder()
            .putString(KEY_TAG, release.tag)
            .putString("title", release.title)
            .putString("description", release.description)
            .putString("published", release.published)
            .putString("asset_url", release.assetUrl)
            .putLong("asset_size", release.assetSize)
            .putString("asset_digest", release.assetDigest)
            .build()
        val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            .setInputData(input)
            .addTag(TAG_PREFIX + release.tag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    internal fun downloadNotification(context: Context, tag: String, percent: Int, completed: Boolean): android.app.Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ))
        }
        val intent = Intent(context, AppUpdateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle(if (completed) context.getString(R.string.update_download_ready_title, tag)
                else context.getString(R.string.update_download_running_title, tag))
            .setContentText(if (completed) context.getString(R.string.update_download_ready_text)
                else context.getString(R.string.update_download_running_text, percent))
            .setProgress(100, percent.coerceIn(0, 100), !completed)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(completed)
            .setOngoing(!completed)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
}

internal class AppUpdateDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    private companion object {
        const val MAX_AUTOMATIC_RETRIES = 2
    }

    override suspend fun doWork(): Result {
        val tag = inputData.getString(AppUpdateDownload.KEY_TAG)
            ?: return Result.failure(Data.Builder().putString(AppUpdateDownload.KEY_ERROR, "Thiếu mã phiên bản").build())
        val release = AppRelease(
            tag = tag,
            title = inputData.getString("title") ?: tag,
            description = inputData.getString("description").orEmpty(),
            published = inputData.getString("published").orEmpty(),
            assetUrl = inputData.getString("asset_url"),
            assetSize = inputData.getLong("asset_size", 0L),
            assetDigest = inputData.getString("asset_digest")
        )
        return try {
            publishDownloadState(tag, 0)
            val file = try {
                AppUpdateRepository.download(applicationContext, release) { bytes, total ->
                    val percent = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                    setProgress(Data.Builder()
                        .putString(AppUpdateDownload.KEY_TAG, tag)
                        .putLong(AppUpdateDownload.KEY_BYTES, bytes)
                        .putLong(AppUpdateDownload.KEY_TOTAL, total)
                        .putInt(AppUpdateDownload.KEY_PERCENT, percent)
                        .build())
                    publishDownloadState(tag, percent)
                }
            } catch (error: IOException) {
                return if (runAttemptCount < MAX_AUTOMATIC_RETRIES) {
                    Result.retry()
                } else {
                    Result.failure(Data.Builder()
                        .putString(AppUpdateDownload.KEY_TAG, tag)
                        .putString(AppUpdateDownload.KEY_ERROR, error.message ?: "Không thể tải APK sau nhiều lần thử")
                        .build())
                }
            }
            withContext(Dispatchers.IO) {
                AppUpdateRepository.verifyDownloadedApk(applicationContext, release, file)
            }
            publishDownloadState(tag, 100, completed = true)
            Result.success(Data.Builder()
                .putString(AppUpdateDownload.KEY_TAG, tag)
                .putString(AppUpdateDownload.KEY_APK_PATH, file.absolutePath)
                .build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(Data.Builder()
                .putString(AppUpdateDownload.KEY_TAG, tag)
                .putString(AppUpdateDownload.KEY_ERROR, error.message ?: "Không thể xác minh APK")
                .build())
        }
    }

    private suspend fun publishDownloadState(tag: String, percent: Int, completed: Boolean = false) {
        val notification = AppUpdateDownload.downloadNotification(applicationContext, tag, percent, completed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Android 15+ may reject dataSync FGS for apps targeting future SDKs.
            // Keep this user-requested transfer as ordinary persistent WorkManager work there.
            androidx.core.app.NotificationManagerCompat.from(applicationContext)
                .notify(AppUpdateDownload.NOTIFICATION_ID, notification)
        } else {
            setForeground(ForegroundInfo(
                AppUpdateDownload.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ))
        }
    }
}
