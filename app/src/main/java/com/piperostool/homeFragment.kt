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
import androidx.cardview.widget.CardView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide

class homeFragment : Fragment() {

    // ... (Giữ nguyên các biến cũ)
    private lateinit var backgroundImageView: ImageView
    private lateinit var appVersionTextView: TextView
    private lateinit var deviceInfoTextView: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuIcon: ImageView
    private lateinit var menuVersionText: TextView
    private lateinit var btnCheckUpdate: LinearLayout
    private lateinit var viewProfile: LinearLayout
    private lateinit var viewAbout: LinearLayout
    private lateinit var btnTrustedApps: LinearLayout
    private lateinit var btnSettingGeneral: LinearLayout

    // THÊM MỚI
    private lateinit var cardSecurityCheck: CardView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onResume() {
        super.onResume()
        updateHeaderGif()
    }

    // ... (Giữ nguyên hàm updateHeaderGif) ...
    private fun updateHeaderGif() {
        /* Code cũ của bạn */
        if (context == null) return
        val prefs = requireContext().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val isCustom = prefs.getBoolean("is_using_custom_gif", false)
        val scaleTypeString = prefs.getString("gif_scale_type", "CENTER_CROP")
        backgroundImageView.scaleType =
            if (scaleTypeString == "FIT_CENTER") ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (isCustom) {
            val uriString = prefs.getString("custom_gif_uri", "")
            if (!uriString.isNullOrEmpty()) {
                try {
                    Glide.with(this).load(Uri.parse(uriString)).placeholder(R.drawable.home_gif)
                        .into(backgroundImageView)
                } catch (e: Exception) {
                    Glide.with(this).asGif().load(R.drawable.home_gif).into(backgroundImageView)
                }
            }
        } else {
            Glide.with(this).asGif().load(R.drawable.home_gif).into(backgroundImageView)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Ánh xạ View cũ
        btnTrustedApps = view.findViewById(R.id.btn_trusted_apps)
        backgroundImageView = view.findViewById(R.id.background_image_view)
        appVersionTextView = view.findViewById(R.id.app_version_info)
        deviceInfoTextView = view.findViewById(R.id.device_info)
        drawerLayout = view.findViewById(R.id.drawer_layout)
        menuIcon = view.findViewById(R.id.menu_icon)
        btnSettingGeneral = view.findViewById(R.id.btn_setting_general)
        viewProfile = view.findViewById(R.id.view_Profile)
        viewAbout = view.findViewById(R.id.view_About)
        // menuVersionText = view.findViewById(R.id.menu_version_text) // Hãy chắc chắn ID này đúng trong Drawer của bạn
        // btnCheckUpdate = view.findViewById(R.id.btn_check_update)

        // THÊM MỚI: Ánh xạ Card Security
        cardSecurityCheck = view.findViewById(R.id.cardSecurityCheck)

        updateHeaderGif()

        // ... (Code lấy version và setup text giữ nguyên) ...
        val appVersion = try {
            val context = requireContext()
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "N/A"
        }

        appVersionTextView.text = "Phiên bản: $appVersion"
        deviceInfoTextView.text = "Thiết bị: " + Build.MODEL

        // Menu Click Listeners (Giữ nguyên)
        menuIcon.setOnClickListener {
            if (!drawerLayout.isDrawerOpen(GravityCompat.END)) drawerLayout.openDrawer(GravityCompat.END)
        }


        // THÊM MỚI: Sự kiện bấm nút Security Check
        cardSecurityCheck.setOnClickListener {
            val intent = Intent(requireContext(), SecurityScanActivity::class.java)
            // Gửi cờ để biết là người dùng tự bấm (Manual Check)
            intent.putExtra("IS_MANUAL_CHECK", true)
            startActivity(intent)
        }

        btnTrustedApps.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), TrustedAppsActivity::class.java)
            startActivity(intent)
        }

        btnSettingGeneral.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), SettingGeneral::class.java)
            startActivity(intent)
        }

        viewProfile.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), ProfileActivity::class.java)
            startActivity(intent)
        }
        viewAbout.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), AboutActivity::class.java)
            startActivity(intent)
        }
    }
}
