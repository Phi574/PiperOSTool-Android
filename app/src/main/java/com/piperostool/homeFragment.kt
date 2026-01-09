package com.piperostool

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    //--- Khai báo biến UI ---
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var backgroundImageView: ImageView
    private lateinit var backgroundImageView2: ImageView // Dùng cho hiệu ứng crossfade
    private lateinit var appVersionTextView: TextView
    private lateinit var deviceInfoTextView: TextView
    private lateinit var menuIcon: ImageView
    private lateinit var btnCloseMenu: ImageView
    private lateinit var viewProfile: LinearLayout
    private lateinit var tvUserName: TextView
    private lateinit var imgAvatar: ImageView
    private lateinit var cardSecurityCheck: CardView
    private lateinit var btnTrustedApps: LinearLayout
    private lateinit var btnSettingGeneral: LinearLayout
    private lateinit var btnCheckUpdate: LinearLayout
    private lateinit var viewAbout: LinearLayout
    private lateinit var btnLogout: LinearLayout
    private lateinit var menuVersionText: TextView

    //--- Logic cho Slideshow ---
    private val slideshowHandler = Handler(Looper.getMainLooper())
    private var slideshowRunnable: Runnable? = null
    private var isSlideshowRunning = false
    private var currentGifIndex = 0
    private val GIF_SLIDESHOW_INTERVAL = 5000L // 5 giây

    //--- Dữ liệu GIF ---
    private val defaultGifsList = listOf(
        R.drawable.gif1, R.drawable.gif2fix, R.drawable.gif3, R.drawable.gif4,
        R.drawable.gif5, R.drawable.gif6, R.drawable.gif7, R.drawable.gif8
    )
    private val defaultGifsMap = defaultGifsList.mapIndexed { index, resourceId ->
        "gif${index + 1}" to resourceId
    }.toMap()


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickListeners()
        setupDrawerListener()
    }

    override fun onResume() {
        super.onResume()
        // Luôn cập nhật ảnh bìa và thông tin người dùng khi fragment được hiển thị lại
        updateHeaderGif()
        updateUserInfo()
    }

    override fun onPause() {
        super.onPause()
        // Dừng slideshow khi fragment không còn được hiển thị để tiết kiệm tài nguyên
        stopSlideshow()
    }

    private fun initViews(view: View) {
        drawerLayout = view.findViewById(R.id.drawer_layout)
        backgroundImageView = view.findViewById(R.id.gif_view1)
        backgroundImageView2 = view.findViewById(R.id.gif_view2)
        appVersionTextView = view.findViewById(R.id.app_version_info)
        deviceInfoTextView = view.findViewById(R.id.device_info)
        menuIcon = view.findViewById(R.id.menu_icon)
        cardSecurityCheck = view.findViewById(R.id.cardSecurityCheck)
        btnCloseMenu = view.findViewById(R.id.btn_close_menu)
        viewProfile = view.findViewById(R.id.view_Profile)
        tvUserName = view.findViewById(R.id.tv_user_name)
        imgAvatar = view.findViewById(R.id.img_avatar)

        // --- GÁN CÁC BIẾN ĐÃ THÊM LẠI ---
        btnTrustedApps = view.findViewById(R.id.btn_trusted_apps)
        btnSettingGeneral = view.findViewById(R.id.btn_setting_general)
        btnCheckUpdate = view.findViewById(R.id.btn_check_update)
        viewAbout = view.findViewById(R.id.view_About)
        btnLogout = view.findViewById(R.id.btn_logout)
        menuVersionText = view.findViewById(R.id.menu_version_text)

        // Cập nhật thông tin
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            appVersionTextView.text = "Phiên bản: ${pInfo.versionName}"
            menuVersionText.text = "v${pInfo.versionName}"
        } catch (e: PackageManager.NameNotFoundException) { e.printStackTrace() }
        deviceInfoTextView.text = "Thiết bị: ${Build.MODEL}"
    }

    private fun updateUserInfo() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            tvUserName.text = user.displayName ?: user.email ?: "PIPER USER"
            if (user.photoUrl != null && isAdded) {
                Glide.with(this).load(user.photoUrl).circleCrop().into(imgAvatar)
            }
        } else {
            tvUserName.text = "PIPER USER"
        }
    }

    private fun setupDrawerListener() {
        val bottomNavCard = activity?.findViewById<CardView>(R.id.bottomNavCard)
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                bottomNavCard?.translationY = slideOffset * 300f
            }
            override fun onDrawerOpened(drawerView: View) {
                bottomNavCard?.translationY = 300f
            }
            override fun onDrawerClosed(drawerView: View) {
                bottomNavCard?.translationY = 0f
            }
            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun setupClickListeners() {
        menuIcon.setOnClickListener { drawerLayout.openDrawer(GravityCompat.END) }
        btnCloseMenu.setOnClickListener { drawerLayout.closeDrawer(GravityCompat.END) }

        cardSecurityCheck.setOnClickListener {
            startActivity(Intent(requireContext(), SecurityScanActivity::class.java).apply {
                putExtra("IS_MANUAL_CHECK", true)
            })
        }

        viewProfile.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        // ================= THÊM LẠI LISTENER TẠI ĐÂY =================
        btnTrustedApps.setOnClickListener {
            startActivity(Intent(requireContext(), TrustedAppsActivity::class.java))
            Toast.makeText(context, "Mở màn hình Cấu hình & Tin cậy", Toast.LENGTH_SHORT).show()
        }
        // ============================================================

        btnSettingGeneral.setOnClickListener {
            startActivity(Intent(requireContext(), SettingGeneral::class.java))
        }

        btnCheckUpdate.setOnClickListener {
            Toast.makeText(context, "Đang kiểm tra cập nhật...", Toast.LENGTH_SHORT).show()
        }

        // ================= THÊM LẠI LISTENER TẠI ĐÂY =================
        viewAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            Toast.makeText(context, "Mở màn hình Về chúng tôi", Toast.LENGTH_SHORT).show()
        }
        // ============================================================

        btnLogout.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            Toast.makeText(requireContext(), "Đã đăng xuất", Toast.LENGTH_SHORT).show()
            val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }
    }


    private fun updateHeaderGif() {
        if (!isAdded || context == null) return
        stopSlideshow() // Luôn dừng slideshow cũ trước khi quyết định cái mới

        val prefs = requireContext().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val gifSelection = prefs.getString("gif_selection", "default")

        // Ảnh bìa luôn dùng kiểu CENTER_CROP
        backgroundImageView.scaleType = ImageView.ScaleType.CENTER_CROP

        when {
            // Nếu người dùng chọn slideshow, bắt đầu chạy
            gifSelection == "slideshow" -> startSlideshow()

            // Nếu người dùng chọn ảnh tùy chỉnh
            gifSelection == "custom" -> {
                val uriString = prefs.getString("custom_gif_uri", null)
                if (uriString != null) {
                    Glide.with(this).load(Uri.parse(uriString))
                        .placeholder(R.drawable.home_gif)
                        .error(R.drawable.home_gif)
                        .into(backgroundImageView)
                } else {
                    // Nếu URI bị rỗng, quay về mặc định
                    Glide.with(this).asGif().load(R.drawable.home_gif).into(backgroundImageView)
                }
            }

            // Nếu người dùng chọn một trong các GIF có sẵn
            gifSelection!!.startsWith("gif") -> {
                val resId = defaultGifsMap[gifSelection] ?: R.drawable.home_gif
                Glide.with(this).asGif().load(resId).into(backgroundImageView)
            }

            // Trường hợp mặc định ("default" hoặc các giá trị không xác định)
            else -> {
                Glide.with(this).asGif().load(R.drawable.home_gif).into(backgroundImageView)
            }
        }
    }

    private fun startSlideshow() {
        if (isSlideshowRunning || !isAdded || defaultGifsList.isEmpty()) return
        isSlideshowRunning = true
        currentGifIndex = 0
        // Load ảnh đầu tiên
        Glide.with(this).asGif().load(defaultGifsList[currentGifIndex]).into(backgroundImageView)

        // Tạo và chạy runnable
        slideshowRunnable = Runnable {
            currentGifIndex = (currentGifIndex + 1) % defaultGifsList.size
            crossfadeToNewGif(defaultGifsList[currentGifIndex])
            slideshowHandler.postDelayed(slideshowRunnable!!, GIF_SLIDESHOW_INTERVAL)
        }
        // Lên lịch cho lần chạy tiếp theo
        slideshowHandler.postDelayed(slideshowRunnable!!, GIF_SLIDESHOW_INTERVAL)
    }

    private fun stopSlideshow() {
        isSlideshowRunning = false
        slideshowHandler.removeCallbacksAndMessages(null) // Xóa tất cả các lịch trình
    }

    private fun crossfadeToNewGif(newGifResId: Int) {
        if (!isAdded) return // Đảm bảo fragment vẫn còn tồn tại

        // Load ảnh mới vào ImageView ẩn
        Glide.with(this).asGif().load(newGifResId).into(backgroundImageView2)
        backgroundImageView2.alpha = 0f
        backgroundImageView2.visibility = View.VISIBLE

        // Tạo hiệu ứng
        val fadeOut = ObjectAnimator.ofFloat(backgroundImageView, "alpha", 1f, 0f).setDuration(500)
        val fadeIn = ObjectAnimator.ofFloat(backgroundImageView2, "alpha", 0f, 1f).setDuration(500)

        // Bắt đầu hiệu ứng
        fadeOut.start()
        fadeIn.start()

        // Sau khi hiệu ứng kết thúc, cập nhật lại ImageView chính và ẩn ImageView phụ
        slideshowHandler.postDelayed({
            if (isAdded) {
                Glide.with(this).asGif().load(newGifResId).into(backgroundImageView)
                backgroundImageView.alpha = 1f
                backgroundImageView2.visibility = View.INVISIBLE
            }
        }, 500)
    }
}
