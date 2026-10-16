package com.piperostool

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object AppUpdateMonitor {
    private const val WORK_NAME = "piperos_github_update_check"
    private const val NOTIFICATION_CHANNEL = "piperos_update_available"
    private const val NOTIFICATION_ID = 378
    private const val NOTIFICATION_PREFS = "update_notifications"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    internal fun notifyIfAvailable(context: Context, release: AppRelease, channel: AppUpdateRepository.Channel) {
        if (release.assetUrl == null || !notificationsAllowed(context)) return
        val preferences = context.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
        val key = "last_notified_${channel.name}"
        if (preferences.getString(key, null) == release.tag) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    context.getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.update_notification_channel_description)
                }
            )
        }
        val openUpdate = Intent(context, AppUpdateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openUpdate,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notes = release.description
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
            .replace("**", "")
            .replace("`", "")
            .trim()
            .take(2400)
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle(context.getString(R.string.update_notification_title, release.tag))
            .setContentText(context.getString(R.string.update_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifBlank {
                context.getString(R.string.update_notification_text)
            }))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        preferences.edit().putString(key, release.tag).apply()
    }

    private fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)
}

internal class AppUpdateCheckWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val settings = applicationContext.getSharedPreferences("update_program", Context.MODE_PRIVATE)
        val channel = AppUpdateRepository.selectedChannel(settings)
        val releases = AppUpdateRepository.releases()
        val current = AppVersion.name(applicationContext)
        val next = if (AppUpdateRepository.needsRequiredMigration(current)) {
            AppUpdateRepository.requiredMigrationRelease(releases, current)
        } else {
            AppUpdateRepository.nextRelease(releases, current, channel)
        }
        if (next != null) AppUpdateMonitor.notifyIfAvailable(applicationContext, next, channel)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        Result.retry()
    } catch (error: Exception) {
        Result.retry()
    }
}
