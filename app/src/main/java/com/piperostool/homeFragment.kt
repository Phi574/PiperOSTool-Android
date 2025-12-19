package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide

class homeFragment : Fragment() {

    private lateinit var backgroundImageView: ImageView
    private lateinit var appVersionTextView: TextView
    private lateinit var deviceInfoTextView: TextView

    // Các biến cho Menu
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuIcon: ImageView
    private lateinit var menuVersionText: TextView
    private lateinit var btnCheckUpdate: LinearLayout
    private lateinit var viewProfile: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onResume() {
        super.onResume()
        updateHeaderGif() // Cập nhật lại ảnh mỗi khi vào màn hình
    }

    private fun updateHeaderGif() {
        // Đọc cài đặt từ SharedPreferences
        // Kiểm tra xem context có null không để tránh crash
        if (context == null) return

        val prefs = requireContext().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)

        val isCustom = prefs.getBoolean("is_using_custom_gif", false)
        val scaleTypeString = prefs.getString("gif_scale_type", "CENTER_CROP")

        // 1. Chỉnh Scale Type (Căn chỉnh)
        backgroundImageView.scaleType = if (scaleTypeString == "FIT_CENTER")
            ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP

        // 2. Load Ảnh
        if (isCustom) {
            val uriString = prefs.getString("custom_gif_uri", "")
            if (!uriString.isNullOrEmpty()) {
                // Load ảnh người dùng chọn
                try {
                    Glide.with(this)
                        .load(Uri.parse(uriString))
                        .placeholder(R.drawable.home_gif) // Ảnh chờ
                        .into(backgroundImageView)
                } catch (e: Exception) {
                    // Nếu lỗi (ví dụ file bị xóa), load mặc định
                    Glide.with(this).asGif().load(R.drawable.home_gif).into(backgroundImageView)
                }
            }
        } else {
            // Load ảnh mặc định hệ thống
            Glide.with(this)
                .asGif()
                .load(R.drawable.home_gif)
                .into(backgroundImageView)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Ánh xạ các view cũ
        backgroundImageView = view.findViewById(R.id.background_image_view)
        appVersionTextView = view.findViewById(R.id.app_version_info)
        deviceInfoTextView = view.findViewById(R.id.device_info)

        // Ánh xạ các view mới (Menu Drawer)
        drawerLayout = view.findViewById(R.id.drawer_layout)
        menuIcon = view.findViewById(R.id.menu_icon)
        menuVersionText = view.findViewById(R.id.menu_version_text)
        btnCheckUpdate = view.findViewById(R.id.btn_check_update)
        viewProfile = view.findViewById(R.id.view_Profile)

        // Load ảnh lần đầu
        updateHeaderGif()

        // Lấy thông tin version
        val appVersion = try {
            val context = requireContext()
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "N/A"
        }

        appVersionTextView.text = "Phiên bản: $appVersion"
        val deviceInfo = "Thiết bị: " + Build.MODEL
        deviceInfoTextView.text = deviceInfo

        menuVersionText.text = "v$appVersion"

        menuIcon.setOnClickListener {
            if (!drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }

        btnCheckUpdate.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), MaintenanceActivity::class.java)
            startActivity(intent)
        }

        viewProfile.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), ProfileActivity::class.java)
            startActivity(intent)
        }
    }
}
