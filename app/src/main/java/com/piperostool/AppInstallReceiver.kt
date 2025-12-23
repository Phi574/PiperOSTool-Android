package com.piperostool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
            val packageName = intent.data?.schemeSpecificPart ?: return

            // Logic kiểm tra app đang chạy foreground hay background
            // Để đơn giản, ta sẽ luôn hiện thông báo, bấm vào sẽ mở Activity Quét

            showSecurityNotification(context, packageName)
        }
    }

    private fun showSecurityNotification(context: Context, pkgName: String) {
        val channelId = "PiperSecurityChannel"

        // Tạo Channel cho Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Security Scans", NotificationManager.IMPORTANCE_HIGH)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Intent mở màn hình quét
        val scanIntent = Intent(context, SecurityScanActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("IS_MANUAL_CHECK", false)
            putExtra("TARGET_PACKAGE", pkgName)
        }

        val pendingIntent = PendingIntent.getActivity(context, 0, scanIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Thay bằng icon bảo mật của bạn
            .setContentTitle("Phát hiện ứng dụng mới")
            .setContentText("Đang quét bảo mật cho $pkgName...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            // Cần quyền POST_NOTIFICATIONS trên Android 13+
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())

            // Nếu muốn mở thẳng Activity (như yêu cầu "tự động mở"), bỏ comment dòng dưới (Lưu ý: Android 10+ chặn start activity từ background)
            // context.startActivity(scanIntent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
