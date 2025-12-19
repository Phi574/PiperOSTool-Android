package com.piperostool

import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.piperostool.R

class HomeActivity : AppCompatActivity() {

    private lateinit var backgroundImageView: ImageView
    private lateinit var appVersionTextView: TextView
    private lateinit var deviceInfoTextView: TextView
    // private lateinit var btnSetupPiperOs: FrameLayout // Tạm thời comment vì chưa dùng

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        backgroundImageView = findViewById(R.id.background_image_view)
        appVersionTextView = findViewById(R.id.app_version_info)
        deviceInfoTextView = findViewById(R.id.device_info)

        // Load GIF using Glide
        Glide.with(this)
            .asGif()
            .load(R.drawable.home_gif) // Đảm bảo home_gif tồn tại trong res/drawable
            .into(backgroundImageView)

        // Get App Version
        val appVersion = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "N/A"
        }
        appVersionTextView.text = "Phiên bản: $appVersion"

        // Get Device Info
        val deviceInfo = "Thiết bị: " + Build.MODEL
        deviceInfoTextView.text = deviceInfo
    }
}
