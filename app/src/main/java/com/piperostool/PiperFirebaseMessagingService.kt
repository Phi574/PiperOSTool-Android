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
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

internal object FirebaseMessagingTokenStore {
    private const val TAG = "PiperFCM"
    private const val PREFERENCES = "firebase_messaging_state"
    private const val KEY_INSTALLATION_ID = "installation_id"

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                context.getString(R.string.firebase_messaging_channel_id),
                context.getString(R.string.firebase_messaging_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.firebase_messaging_channel_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        FirebaseMessaging.getInstance().register()
            .addOnFailureListener {
                android.util.Log.w(TAG, "Firebase Messaging registration failed")
            }
    }

    fun save(context: Context, installationId: String) {
        if (installationId.isBlank()) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_INSTALLATION_ID, installationId)
            .apply()
    }

    fun remove(context: Context, installationId: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_INSTALLATION_ID, null) == installationId) {
            preferences.edit().remove(KEY_INSTALLATION_ID).apply()
        }
    }
}

class PiperFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        FirebaseMessagingTokenStore.save(this, installationId)
    }

    override fun onUnregistered(installationId: String) {
        super.onUnregistered(installationId)
        FirebaseMessagingTokenStore.remove(this, installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.firebase_messaging_notification_title)
        val body = message.notification?.body ?: message.data["body"] ?: return
        showNotification(title, body, message.data["route"])
    }

    private fun showNotification(title: String, body: String, route: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureNotificationChannel()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, SplashScreenActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!route.isNullOrBlank()) launchIntent.putExtra("firebase_message_route", route)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, getString(R.string.firebase_messaging_channel_id))
            .setSmallIcon(R.drawable.ic_fcm_notification)
            .setColor(ContextCompat.getColor(this, R.color.remote_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            getString(R.string.firebase_messaging_channel_id),
            getString(R.string.firebase_messaging_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.firebase_messaging_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

}
