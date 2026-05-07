package com.piperostool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import java.util.UUID

class IntegrityBackgroundWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("PiperOS", "Bắt đầu chạy ngầm kiểm tra Play Integrity...")
            val integrityManager = IntegrityManagerFactory.create(applicationContext)
            val nonce = UUID.randomUUID().toString()
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()

            // Ép luồng chạy ngầm đợi Google phản hồi
            val response = integrityManager.requestIntegrityToken(request).await()
            Log.d("PiperOS", "Hệ thống vẫn AN TOÀN!")

            Result.success()
        } catch (e: Exception) {
            Log.e("PiperOS", "CẢNH BÁO: Rớt Play Integrity! Lỗi: ${e.message}")

            // Bắn thông báo khẩn cấp lên màn hình người dùng
            pushNotification(
                "CẢNH BÁO BẢO MẬT HỆ THỐNG",
                "Thiết bị của bạn vừa không vượt qua được bài kiểm tra Play Integrity. Môi trường có thể đang gặp rủi ro!"
            )

            Result.success() // Trả về success để nó không chạy lại liên tục gây hao pin
        }
    }

    private fun pushNotification(title: String, message: String) {
        val channelId = "piper_os_alerts"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Yêu cầu của Android 8.0 trở lên: Phải có Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Security Alerts", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        // Bấm vào thông báo sẽ mở App lên
        val intent = Intent(applicationContext, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.warning) // Dùng icon warning có sẵn trong app của bạn
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(101, builder.build())
    }
}