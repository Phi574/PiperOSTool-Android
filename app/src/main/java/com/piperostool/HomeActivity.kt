package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth

class HomeActivity : AppCompatActivity() {

    private lateinit var btnHome: LinearLayout
    private lateinit var btnBeta: LinearLayout
    private lateinit var btnApps: LinearLayout
    private lateinit var btnSettings: LinearLayout
    private lateinit var btnDevices: LinearLayout

    private lateinit var listIcons: List<ImageView>
    private lateinit var listTexts: List<TextView>

    private var currentTab = 0
    private var backPressedTime: Long = 0
    private var isNavHidden = false
    private var isNavHiddenByKeyboard = false

    private lateinit var backToast: Toast
    private val sessionHandler = Handler(Looper.getMainLooper())
    private var checkingSession = false
    private val sessionCheck = object : Runnable {
        override fun run() {
            verifyAccountSession()
            sessionHandler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_home)

        applyCustomBackground()
        initViews()
        setupListeners()
        setupBackPressHandler()
        setupKeyboardAwareBottomNav()

        replaceFragment(homeFragment())
        currentTab = 0
    }

    override fun onResume() {
        super.onResume()

        updateTabUI(currentTab)
    }

    override fun onStart() {
        super.onStart()
        sessionHandler.removeCallbacks(sessionCheck)
        sessionHandler.post(sessionCheck)
    }

    override fun onStop() {
        sessionHandler.removeCallbacks(sessionCheck)
        super.onStop()
    }

    private fun verifyAccountSession() {
        if (checkingSession) return
        checkingSession = true
        AccountSessionGuard.verify(this) { state ->
            checkingSession = false
            when (state) {
                AccountSessionState.Valid, AccountSessionState.Offline -> Unit
                is AccountSessionState.Disabled -> {
                    sessionHandler.removeCallbacks(sessionCheck)
                    startActivity(DisabledAccountActivity.createIntent(this, state))
                }
                is AccountSessionState.Expired -> {
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        putExtra(SplashScreenActivity.EXTRA_SESSION_EXPIRED, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                }
            }
        }
    }

    private fun applyCustomBackground() {
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val hasCustomBg = prefs.getBoolean("has_custom_bg", false)
        val bgView = findViewById<ImageView>(R.id.homeBackground)

        if (PiperUiPreferences.isModern(this)) {
            bgView.visibility = View.GONE
            return
        }

        bgView.scaleType = ImageView.ScaleType.CENTER_CROP

        if (hasCustomBg) {
            try {
                val file = java.io.File(filesDir, "custom_bg.jpg")
                if (file.exists()) {
                    val drawable = android.graphics.drawable.Drawable.createFromPath(file.absolutePath)
                    bgView.setImageDrawable(drawable)
                } else {
                    bgView.setImageResource(R.drawable.backgroud)
                }
            } catch (e: Exception) {
                bgView.setImageResource(R.drawable.backgroud)
            }
        } else {
            bgView.setImageResource(R.drawable.backgroud)
        }
    }

    private fun setupKeyboardAwareBottomNav() {
        val rootView = findViewById<View>(R.id.homeRoot)
        val visibleFrame = Rect()

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = rootView.rootView.height
            val keyboardHeight = screenHeight - visibleFrame.bottom
            val isKeyboardVisible = keyboardHeight > screenHeight * 0.15f

            if (isKeyboardVisible && !isNavHiddenByKeyboard) {
                hideBottomNav(force = true)
                isNavHiddenByKeyboard = true
            } else if (!isKeyboardVisible && isNavHiddenByKeyboard) {
                showBottomNav(force = true)
                isNavHiddenByKeyboard = false
            }
        }
    }

    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != 0) {
                    replaceFragment(homeFragment())
                    currentTab = 0
                    updateTabUI(0)
                    showBottomNav(force = true)
                    return
                }

                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    backToast.cancel()
                    finish()
                } else {
                    backToast = Toast.makeText(baseContext, R.string.home_press_again_to_exit, Toast.LENGTH_SHORT)
                    backToast.show()
                }
                backPressedTime = System.currentTimeMillis()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun initViews() {
        btnHome = findViewById(R.id.navHome)
        btnBeta = findViewById(R.id.navBeta)
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
                showBottomNav(force = true)
            }
        }
        btnBeta.setOnClickListener {
            if (currentTab != 1) {
                replaceFragment(BetaFragment())
                currentTab = 1
                updateTabUI(1)
                showBottomNav(force = true)
            }
        }
        btnApps.setOnClickListener {
            if (currentTab != 2) {
                replaceFragment(AppsFragment())
                currentTab = 2
                updateTabUI(2)
                showBottomNav(force = true)
            }
        }
        btnSettings.setOnClickListener {
            if (currentTab != 3) {
                replaceFragment(SettingFragment())
                currentTab = 3
                updateTabUI(3)
                showBottomNav(force = true)
            }
        }
        btnDevices.setOnClickListener {
            if (currentTab != 4) {
                replaceFragment(InfoFragment())
                currentTab = 4
                updateTabUI(4)
                showBottomNav(force = true)
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

        val activeColorCode = if (PiperUiPreferences.isModern(this)) {
            if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ) Color.parseColor("#63DCA5") else Color.parseColor("#127C56")
        } else {
            when (theme) {
                "purple" -> Color.parseColor("#E040FB")
                "green" -> Color.parseColor("#00FF00")
                else -> ContextCompat.getColor(this, R.color.green_neon)
            }
        }

        val unselectedColor = if (PiperUiPreferences.isModern(this)) {
            if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ) Color.parseColor("#AEB2B6") else Color.parseColor("#696C70")
        } else ContextCompat.getColor(this, R.color.nav_unselected)

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

    fun hideBottomNav(force: Boolean = false) {
        if (!force && currentTab != 2) return
        if (isNavHidden) return

        val bottomBar = findViewById<LinearLayout>(R.id.bottomNavCard)

        bottomBar?.let {
            it.animate().cancel()
            it.animate()
                .translationY(it.height.toFloat() + 50f)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .setDuration(250)
                .start()
            isNavHidden = true
        }
    }

    fun showBottomNav(force: Boolean = false) {
        if (!force && isNavHiddenByKeyboard) return
        if (!isNavHidden) return

        val bottomBar = findViewById<LinearLayout>(R.id.bottomNavCard)

        bottomBar?.let {
            it.animate().cancel()
            it.animate()
                .translationY(0f)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setDuration(250)
                .start()
            isNavHidden = false
        }
    }

    companion object {
        private const val SESSION_CHECK_INTERVAL_MS = 45_000L
    }
}
