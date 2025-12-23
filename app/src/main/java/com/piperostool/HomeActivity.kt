package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

    // Biến theo dõi tab hiện tại để tô màu lại khi onResume
    private var currentTab = 0
    // Biến lưu ngôn ngữ hiện tại để kiểm tra thay đổi
    private var currentLangCode = "vi"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lưu lại ngôn ngữ lúc khởi tạo
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        currentLangCode = prefs.getString("app_language", "vi") ?: "vi"

        // 1. Áp dụng ngôn ngữ trước khi setContentView
        applyAppLanguage()

        setContentView(R.layout.activity_home)

        initViews()
        setupListeners()

        // Load Fragment mặc định
        replaceFragment(homeFragment())
        // Không gọi updateTabUI(0) ở đây nữa, để onResume lo
        currentTab = 0
    }

    // --- BỔ SUNG QUAN TRỌNG: Cập nhật giao diện khi quay lại từ Settings ---
    override fun onResume() {
        super.onResume()

        // 1. Kiểm tra xem ngôn ngữ có bị đổi trong Settings không
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("app_language", "vi") ?: "vi"

        if (savedLang != currentLangCode) {
            // Nếu ngôn ngữ đã đổi, reload lại toàn bộ HomeActivity
            recreate()
            return
        }

        // 2. Cập nhật lại màu sắc Theme (vì onCreate không chạy lại)
        updateTabUI(currentTab)
    }
    // ----------------------------------------------------------------------

    // Hàm áp dụng ngôn ngữ
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
            replaceFragment(homeFragment())
            currentTab = 0
            updateTabUI(0)
        }
        btnModul.setOnClickListener {
            startActivity(Intent(this, MaintenanceActivity::class.java))
            currentTab = 1
            updateTabUI(1)
        }
        btnApps.setOnClickListener {
            startActivity(Intent(this, MaintenanceActivity::class.java))
            currentTab = 2
            updateTabUI(2)
        }

        btnSettings.setOnClickListener {
            // Chuyển sang SettingFragment (nếu bạn vẫn dùng Fragment)
            // Hoặc nếu bạn muốn mở SettingGeneral Activity từ tab này thì dùng startActivity
            replaceFragment(SettingFragment())
            currentTab = 3
            updateTabUI(3)
        }

        btnDevices.setOnClickListener {
            startActivity(Intent(this, MaintenanceActivity::class.java))
            currentTab = 4
            updateTabUI(4)
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.fragment_container, fragment)
        transaction.commit()
    }

    private fun updateTabUI(selectedIndex: Int) {
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)

        val theme = prefs.getString("app_theme", "system")

        val activeColorCode = when (theme) {
            "purple" -> Color.parseColor("#E040FB") // Màu Tím
            "green" -> Color.parseColor("#00FF00")  // Màu Xanh Neon
            else -> Color.parseColor("#FF000000")
        }


        val unselectedColor = ContextCompat.getColor(this, R.color.nav_unselected)

        for (i in listIcons.indices) {
            if (i == selectedIndex) {
                // Áp dụng màu động (Active)
                listIcons[i].imageTintList = ColorStateList.valueOf(activeColorCode)
                listTexts[i].setTextColor(activeColorCode)
            } else {
                // Áp dụng màu tĩnh (Unselected)
                listIcons[i].imageTintList = ColorStateList.valueOf(unselectedColor)
                listTexts[i].setTextColor(unselectedColor)
            }
        }
    }
}
