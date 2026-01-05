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
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth

class homeFragment : Fragment() {

    // --- Khai báo biến UI ---
    private lateinit var backgroundImageView: ImageView
    private lateinit var appVersionTextView: TextView
    private lateinit var deviceInfoTextView: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuIcon: ImageView
    private lateinit var cardSecurityCheck: CardView

    // Menu Drawer
    private lateinit var btnCloseMenu: ImageView
    private lateinit var viewProfile: LinearLayout
    private lateinit var tvUserName: TextView
    private lateinit var imgAvatar: ImageView

    private lateinit var btnTrustedApps: LinearLayout
    private lateinit var btnSettingGeneral: LinearLayout
    private lateinit var btnCheckUpdate: LinearLayout
    private lateinit var viewAbout: LinearLayout
    private lateinit var btnLogout: LinearLayout
    private lateinit var menuVersionText: TextView

    // Biến tham chiếu đến Bottom Navigation ở Activity
    private var bottomNavCard: CardView? = null

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
        updateUserInfo()
        // Đảm bảo Bottom Bar hiện khi quay lại
        bottomNavCard?.animate()?.translationY(0f)?.setDuration(300)?.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Lấy tham chiếu Bottom Bar từ Activity cha
        bottomNavCard = requireActivity().findViewById(R.id.bottomNavCard)

        initViews(view)
        setupData()
        setupClickListeners()
        setupDrawerListener() // Thêm hàm lắng nghe sự kiện đóng mở Drawer
    }

    private fun initViews(view: View) {
        // ... (Giữ nguyên như cũ)
        drawerLayout = view.findViewById(R.id.drawer_layout)
        backgroundImageView = view.findViewById(R.id.background_image_view)
        appVersionTextView = view.findViewById(R.id.app_version_info)
        deviceInfoTextView = view.findViewById(R.id.device_info)
        menuIcon = view.findViewById(R.id.menu_icon)
        cardSecurityCheck = view.findViewById(R.id.cardSecurityCheck)

        btnCloseMenu = view.findViewById(R.id.btn_close_menu)
        viewProfile = view.findViewById(R.id.view_Profile)
        tvUserName = view.findViewById(R.id.tv_user_name)
        imgAvatar = view.findViewById(R.id.img_avatar)

        btnTrustedApps = view.findViewById(R.id.btn_trusted_apps)
        btnSettingGeneral = view.findViewById(R.id.btn_setting_general)
        btnCheckUpdate = view.findViewById(R.id.btn_check_update)
        viewAbout = view.findViewById(R.id.view_About)

        btnLogout = view.findViewById(R.id.btn_logout)
        menuVersionText = view.findViewById(R.id.menu_version_text)
    }

    private fun setupData() {
        // ... (Giữ nguyên logic cũ)
        val appVersion = try {
            val context = requireContext()
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) { "N/A" }

        appVersionTextView.text = "Phiên bản: $appVersion"
        menuVersionText.text = "v$appVersion"
        deviceInfoTextView.text = "Thiết bị: " + Build.MODEL

        updateHeaderGif()
        updateUserInfo()
    }

    private fun updateUserInfo() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            tvUserName.text = user.displayName ?: user.email ?: "PIPER USER"
            if (user.photoUrl != null) {
                Glide.with(this).load(user.photoUrl).circleCrop().into(imgAvatar)
            }
        } else {
            tvUserName.text = "PIPER USER"
        }
    }

    // --- LOGIC MỚI: ẨN/HIỆN BOTTOM BAR KHI TRƯỢT MENU ---
    private fun setupDrawerListener() {
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Khi trượt, đẩy Bottom Bar xuống dưới
                // slideOffset chạy từ 0.0 (đóng) -> 1.0 (mở)
                // Nhân với 300f (hoặc height của bottom bar) để đẩy nó ra khỏi màn hình
                bottomNavCard?.translationY = slideOffset * 300f
            }

            override fun onDrawerOpened(drawerView: View) {
                // Khi mở hẳn, đảm bảo nó ẩn hoàn toàn
                bottomNavCard?.translationY = 300f
            }

            override fun onDrawerClosed(drawerView: View) {
                // Khi đóng, đảm bảo nó hiện lại
                bottomNavCard?.translationY = 0f
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun setupClickListeners() {
        // ... (Giữ nguyên các sự kiện click cũ)
        menuIcon.setOnClickListener {
            if (!drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }

        btnCloseMenu.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
        }

        cardSecurityCheck.setOnClickListener {
            val intent = Intent(requireContext(), SecurityScanActivity::class.java)
            intent.putExtra("IS_MANUAL_CHECK", true)
            startActivity(intent)
        }

        viewProfile.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), ProfileActivity::class.java)
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

        btnCheckUpdate.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            Toast.makeText(requireContext(), "Đang kiểm tra cập nhật...", Toast.LENGTH_SHORT).show()
        }

        viewAbout.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(requireContext(), AboutActivity::class.java)
            startActivity(intent)
        }

        btnLogout.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            FirebaseAuth.getInstance().signOut()
            Toast.makeText(requireContext(), "Đã đăng xuất", Toast.LENGTH_SHORT).show()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    private fun updateHeaderGif() {
        // ... (Giữ nguyên logic GIF cũ)
        if (context == null) return
        val prefs = requireContext().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val isCustom = prefs.getBoolean("is_using_custom_gif", false)
        val scaleTypeString = prefs.getString("gif_scale_type", "CENTER_CROP")

        backgroundImageView.scaleType = if (scaleTypeString == "FIT_CENTER")
            ImageView.ScaleType.FIT_CENTER
        else
            ImageView.ScaleType.CENTER_CROP

        if (isCustom) {
            val uriString = prefs.getString("custom_gif_uri", "")
            if (!uriString.isNullOrEmpty()) {
                try {
                    Glide.with(this).load(Uri.parse(uriString)).placeholder(R.drawable.home_gif).into(backgroundImageView)
                } catch (e: Exception) {
                    Glide.with(this).asGif().load(R.drawable.home_gif).into(backgroundImageView)
                }
            }
        } else {
            Glide.with(this).asGif().load(R.drawable.home_gif).into(backgroundImageView)
        }
    }
}
