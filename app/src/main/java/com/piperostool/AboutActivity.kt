package com.piperostool

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // 1. Ánh xạ các view
        val btnBack: ImageView = findViewById(R.id.btnBackAbout)
        val tvAppVersion: TextView = findViewById(R.id.tvAppVersion)

        // 2. Xử lý sự kiện nút Back
        btnBack.setOnClickListener {
            finish() // Đóng màn hình này
        }


        try {

            val pInfo = packageManager.getPackageInfo(packageName, 0)


            val versionName = pInfo.versionName

            // Gán vào TextView
            tvAppVersion.text = "Version $versionName"

        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            tvAppVersion.text = "Version Unknown"
        }
    }
}
