package com.piperostool.privileged.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.piperostool.R
import com.piperostool.privileged.ui.AdvancedAccessActivity

object PiperAdbPairingNotifications {
    const val ACTION_SUBMIT_CODE = "com.piperostool.action.PAIR_PIPER_ADB"
    const val REMOTE_INPUT_CODE = "piper_adb_pairing_code"
    private const val CHANNEL_ID = "piper_adb_pairing"
    private const val NOTIFICATION_ID = 3111

    fun showWaiting(context: Context) {
        ensureChannel(context)
        val input = RemoteInput.Builder(REMOTE_INPUT_CODE)
            .setLabel(context.getString(R.string.pps_pairing_code_hint))
            .build()
        val submitIntent = Intent(context, PiperAdbPairingReceiver::class.java)
            .setAction(ACTION_SUBMIT_CODE)
        val submitPending = PendingIntent.getBroadcast(
            context,
            3111,
            submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val submitAction = NotificationCompat.Action.Builder(
            R.drawable.ic_terminal,
            context.getString(R.string.pps_notification_enter_code),
            submitPending
        ).addRemoteInput(input).build()
        val settingsPending = PendingIntent.getActivity(
            context,
            3112,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(context.getString(R.string.pps_notification_waiting_title))
                .setContentText(context.getString(R.string.pps_notification_waiting_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.pps_notification_waiting_detail)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_terminal, context.getString(R.string.pps_open_wireless_debugging), settingsPending)
                .addAction(submitAction)
        )
    }

    fun showPairing(context: Context) = showState(
        context,
        context.getString(R.string.pps_pairing_in_progress),
        context.getString(R.string.pps_pairing_discovering),
        true
    )

    fun showSuccess(context: Context) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            3113,
            Intent(context, AdvancedAccessActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(context.getString(R.string.pps_pairing_success))
                .setContentText(context.getString(R.string.pps_notification_connected_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
        )
    }

    fun showError(context: Context, detail: String) {
        ensureChannel(context)
        val retry = PendingIntent.getBroadcast(
            context,
            3114,
            Intent(context, PiperAdbPairingReceiver::class.java).setAction(ACTION_SUBMIT_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val input = RemoteInput.Builder(REMOTE_INPUT_CODE)
            .setLabel(context.getString(R.string.pps_pairing_code_hint))
            .build()
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(context.getString(R.string.pps_pairing_failed))
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .addAction(NotificationCompat.Action.Builder(
                    R.drawable.ic_terminal,
                    context.getString(R.string.pps_notification_retry_code),
                    retry
                ).addRemoteInput(input).build())
        )
    }

    private fun showState(context: Context, title: String, text: String, ongoing: Boolean) {
        ensureChannel(context)
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
        )
    }

    private fun notify(context: Context, builder: NotificationCompat.Builder) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pps_notification_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.pps_notification_channel_description)
        })
    }
}
