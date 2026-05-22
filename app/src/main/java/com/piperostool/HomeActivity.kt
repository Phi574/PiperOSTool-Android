package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    // Khai báo các nút
    private lateinit var btnHome: LinearLayout
    private lateinit var btnModul: LinearLayout
    private lateinit var btnApps: LinearLayout
    private lateinit var btnSettings: LinearLayout
    private lateinit var btnDevices: LinearLayout

    private lateinit var listIcons: List<ImageView>
    private lateinit var listTexts: List<TextView>

    private var currentTab = 0
    private var currentLangCode = "vi"
    private var backPressedTime: Long = 0
    private lateinit var backToast: Toast

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Áp dụng ngôn ngữ trước khi setContentView
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        currentLangCode = prefs.getString("app_language", "vi") ?: "vi"
        applyAppLanguage()

        setContentView(R.layout.activity_home)

        // 2. Gọi hàm áp dụng hình nền tùy chỉnh
        applyCustomBackground()

        initViews()
        setupListeners()
        setupBackPressHandler()

        // Load Fragment mặc định
        replaceFragment(homeFragment())
        currentTab = 0
    }

    override fun onResume() {
        super.onResume()

        // Kiểm tra xem ngôn ngữ có bị đổi trong Settings không
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("app_language", "vi") ?: "vi"

        if (savedLang != currentLangCode) {
            recreate()
            return
        }

        updateTabUI(currentTab)
    }

    // ==========================================================
    // HÀM ÁP DỤNG HÌNH NỀN TÙY CHỈNH (MỚI)
    // ==========================================================
    private fun applyCustomBackground() {
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val hasCustomBg = prefs.getBoolean("has_custom_bg", false)

        // TRỎ ĐÚNG VÀO VIEW ĐANG HIỂN THỊ HÌNH NỀN (thay vì rootLayout)
        val bgView = findViewById<android.widget.FrameLayout>(R.id.fragment_container)

        if (hasCustomBg) {
            try {
                val file = java.io.File(filesDir, "custom_bg.jpg")
                if (file.exists()) {
                    val drawable = android.graphics.drawable.Drawable.createFromPath(file.absolutePath)
                    bgView.background = drawable
                } else {
                    bgView.setBackgroundResource(R.drawable.backgroud) // File nền mặc định
                }
            } catch (e: Exception) {
                bgView.setBackgroundResource(R.drawable.backgroud)
            }
        } else {
            bgView.setBackgroundResource(R.drawable.backgroud)
        }
    }

    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != 0) {
                    replaceFragment(homeFragment())
                    currentTab = 0
                    updateTabUI(0)
                    return
                }

                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    backToast.cancel()
                    finish()
                } else {
                    backToast = Toast.makeText(baseContext, "Bấm lần nữa để thoát", Toast.LENGTH_SHORT)
                    backToast.show()
                }
                backPressedTime = System.currentTimeMillis()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun applyAppLanguage() {
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("app_language", "vi") ?: "vi"

        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun initViews() {
        btnHome = findViewById(R.id.navHome)
        btnModul = findViewById(R.id.navModul)
        btnApps = findViewById(R.id.navApps)
        btnSettings = findViewById(R.id.navSettings)
        btnDevices = findViewById(R.id.navDevices)

        listIcons = listOf(
            findViewById(R.id.iconHome),
            findViewById(R.id.iconNews),
            findViewById(R.id.iconApps),
            findViewById(R.id.iconSettings),
            findViewById(R.id.iconDevices)
        )

        listTexts = listOf(
            findViewById(R.id.txtHome),
            findViewById(R.id.txtNews),
            findViewById(R.id.txtApps),
            findViewById(R.id.txtSettings),
            findViewById(R.id.txtDevices)
        )
    }

    private fun setupListeners() {
        btnHome.setOnClickListener {
            if (currentTab != 0) {
                replaceFragment(homeFragment())
                currentTab = 0
                updateTabUI(0)
            }
        }
        btnModul.setOnClickListener {
            if (currentTab != 1) {
                replaceFragment(ModuleFragment())
                currentTab = 1
                updateTabUI(1)
            }
        }
        btnApps.setOnClickListener {
            if (currentTab != 2) {
                replaceFragment(AppsFragment())
                currentTab = 2
                updateTabUI(2)
            }
        }
        btnSettings.setOnClickListener {
            if (currentTab != 3) {
                replaceFragment(SettingFragment())
                currentTab = 3
                updateTabUI(3)
            }
        }
        btnDevices.setOnClickListener {
            if (currentTab != 4) {
                replaceFragment(DevicesFragment())
                currentTab = 4
                updateTabUI(4)
            }
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        transaction.replace(R.id.fragment_container, fragment)
        transaction.commit()
    }

    private fun updateTabUI(selectedIndex: Int) {
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val theme = prefs.getString("app_theme", "system")

        val activeColorCode = when (theme) {
            "purple" -> Color.parseColor("#E040FB")
            "green" -> Color.parseColor("#00FF00")
            else -> ContextCompat.getColor(this, R.color.green_neon)
        }

        val unselectedColor = ContextCompat.getColor(this, R.color.nav_unselected)

        for (i in listIcons.indices) {
            if (i == selectedIndex) {
                listIcons[i].imageTintList = ColorStateList.valueOf(activeColorCode)
                listTexts[i].setTextColor(activeColorCode)
            } else {
                listIcons[i].imageTintList = ColorStateList.valueOf(unselectedColor)
                listTexts[i].setTextColor(unselectedColor)
            }
        }
    }
}