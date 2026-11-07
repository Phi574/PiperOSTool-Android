package com.piperostool

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import android.net.Uri
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private var sessionChecksActive = false
    private var sessionCheckGeneration = 0
    private var leavingForAccountState = false

    private var windowConfigJob: Job? = null
    private var currentWindowConfig: WindowConfig? = null
    private val windowConfigRefreshHandler = Handler(Looper.getMainLooper())

    private val windowConfigRefresh = object : Runnable {
        override fun run() {
            loadWindowConfig(silent = true)
            windowConfigRefreshHandler.postDelayed(this, 3_000L)
        }
    }
    private val sessionCheck = object : Runnable {
        override fun run() {
            verifyAccountSession()
            sessionHandler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_home)

        PiperModernUi.watch(this)
        applyCustomBackground()
        initViews()
        setupListeners()
        setupWindowDrawer()
        setupBackPressHandler()
        setupKeyboardAwareBottomNav()
        setupNavigationBarAwareBottomNav()

        currentTab = savedInstanceState?.getInt(STATE_CURRENT_TAB, 0)?.coerceIn(0, 4) ?: 0
        val restoredPage = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (savedInstanceState == null || restoredPage == null) {
            replaceFragment(fragmentForTab(currentTab))
        }
        updateTabUI(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_TAB, currentTab)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        updateTabUI(currentTab)
    }

    override fun onStart() {
        super.onStart()
        sessionChecksActive = true
        sessionCheckGeneration++
        leavingForAccountState = false
        sessionHandler.removeCallbacks(sessionCheck)
        sessionHandler.post(sessionCheck)
        windowConfigRefreshHandler.removeCallbacks(windowConfigRefresh)
        loadWindowConfig(silent = true)
        windowConfigRefreshHandler.postDelayed(windowConfigRefresh, 3_000L)
    }

    override fun onStop() {
        sessionChecksActive = false
        sessionCheckGeneration++
        sessionHandler.removeCallbacks(sessionCheck)
        windowConfigRefreshHandler.removeCallbacks(windowConfigRefresh)
        super.onStop()
    }

    private fun verifyAccountSession() {
        if (checkingSession || !sessionChecksActive) return
        val generation = sessionCheckGeneration
        checkingSession = true
        AccountSessionGuard.verify(this) { state ->
            checkingSession = false
            if (!sessionChecksActive || generation != sessionCheckGeneration) {
                return@verify
            }
            handleAccountState(state)
        }
    }

    private fun updateScreenHeader(selectedIndex: Int) {
        val title = findViewById<TextView>(R.id.homeScreenTitle)
        val summary = findViewById<TextView>(R.id.homeScreenSummary)

        when (selectedIndex) {
            1 -> {
                title.setText(R.string.beta_lab_title)
                summary.setText(R.string.beta_lab_subtitle)
            }
            2 -> {
                title.setText(R.string.apps_screen_title)
                summary.setText(R.string.apps_screen_summary)
            }
            3 -> {
                title.setText(R.string.settings_screen_title)
                summary.setText(R.string.settings_screen_summary)
            }
            4 -> {
                title.setText(R.string.info_screen_title)
                summary.text = getString(
                    R.string.info_shell_summary,
                    AppVersion.name(this)
                )
            }
            else -> {
                title.setText(R.string.home_screen_title)
                summary.setText(R.string.home_screen_summary)
            }
        }
    }
    private fun handleAccountState(state: AccountSessionState) {
        if (leavingForAccountState) return
        when (state) {
            AccountSessionState.Valid, AccountSessionState.Offline -> Unit
            is AccountSessionState.Expired -> {
                leavingForAccountState = true
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
        }
    }

    private fun applyCustomBackground() {
        val prefs = AccountDataScope.preferences(this, "PiperPrefs")
        val hasCustomBg = prefs.getBoolean("has_custom_bg", false)
        val bgView = findViewById<ImageView>(R.id.homeBackground)

        if (!hasCustomBg) {
            bgView.visibility = View.GONE
            return
        }

        bgView.scaleType = ImageView.ScaleType.CENTER_CROP

        if (hasCustomBg) {
            try {
                val file = AccountDataScope.file(this, "appearance", "custom_bg.jpg")
                if (file.exists()) {
                    val drawable = android.graphics.drawable.Drawable.createFromPath(file.absolutePath)
                    bgView.setImageDrawable(drawable)
                    bgView.visibility = View.VISIBLE
                } else {
                    bgView.visibility = View.GONE
                }
            } catch (e: Exception) {
                bgView.visibility = View.GONE
            }
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

    private fun setupNavigationBarAwareBottomNav() {
        val root = findViewById<View>(R.id.homeRoot)
        val bottomBar = findViewById<View>(R.id.bottomNavCard)
        val header = findViewById<View>(R.id.homeHeader)
        val drawerPanel = findViewById<View>(R.id.homeWindowPanel)

        val baseBottomMargin = dp(16)
        val baseHeaderTopPadding = header.paddingTop
        val baseDrawerMargin = dp(12)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.displayCutout()
            ).top
            val navigationInset =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            header.setPadding(
                header.paddingLeft,
                baseHeaderTopPadding + topInset,
                header.paddingRight,
                header.paddingBottom
            )

            bottomBar.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = baseBottomMargin + navigationInset
            }

            (drawerPanel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                params.topMargin = baseDrawerMargin + topInset
                params.bottomMargin = baseDrawerMargin + navigationInset
                drawerPanel.layoutParams = params
            }

            insets
        }

        ViewCompat.requestApplyInsets(root)
    }

    private fun setupWindowDrawer() {
        findViewById<View>(R.id.btnHomeWindow).setOnClickListener {
            openWindowDrawer()
        }
        findViewById<View>(R.id.btnCloseHomeWindow).setOnClickListener {
            closeWindowDrawer()
        }
        findViewById<View>(R.id.homeWindowScrim).setOnClickListener {
            closeWindowDrawer()
        }
        findViewById<View>(R.id.homeWindowSupport).setOnClickListener {
            closeWindowDrawer { openSupport() }
        }
        findViewById<View>(R.id.btnHomeWindowLogout).setOnClickListener {
            closeWindowDrawer { confirmLogout() }
        }
    }

    private fun openWindowDrawer() {
        val overlay = findViewById<View>(R.id.homeWindowOverlay)
        if (overlay.visibility == View.VISIBLE) return

        val panel = findViewById<View>(R.id.homeWindowPanel)
        val scrim = findViewById<View>(R.id.homeWindowScrim)

        overlay.visibility = View.VISIBLE
        val distance = panel.width.takeIf { it > 0 } ?: dp(320)
        panel.translationX = distance.toFloat()
        scrim.alpha = 0f

        panel.animate().cancel()
        scrim.animate().cancel()
        panel.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        scrim.animate().alpha(1f).setDuration(220L).start()

        hideBottomNav(force = true)
        loadWindowConfig()
    }
    private fun loadWindowConfig(silent: Boolean = false) {
        if (windowConfigJob?.isActive == true) return

        if (!silent) {
            hideRemoteWindowRows()
            showWindowStatus(
                loading = true,
                message = "Đang tải cấu hình..."
            )
        }

        windowConfigJob = lifecycleScope.launch {
            try {
                val config = WindowConfigRepository.load(this@HomeActivity)
                val changed = config.rawJson != currentWindowConfig?.rawJson
                currentWindowConfig = config

                if (changed || !silent) {
                    renderWindowConfig(config)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentWindowConfig = null
                if (findViewById<View>(R.id.homeWindowOverlay).visibility == View.VISIBLE) {
                    hideRemoteWindowRows()
                    showWindowStatus(
                        loading = false,
                        message = "Các tính năng hiện tại không khả dụng.\nVui lòng kiểm tra mạng hoặc thử lại sau."
                    )
                }
            }
        }
    }

    private fun renderWindowConfig(config: WindowConfig) {
        val profileItem = config.homeWindow.firstOrNull {
            it.id == "personal_info" &&
                    it.action == "personal_info" &&
                    it.enabled
        }
        val securityItem = config.homeWindow.firstOrNull {
            it.id == "security" &&
                    it.action == "password_security" &&
                    it.enabled
        }
        val whatsNewItem = config.homeWindow.firstOrNull {
            it.id == "whats_new" &&
                    it.action == "whats_new" &&
                    it.enabled
        }
        if (listOf(profileItem, securityItem, whatsNewItem).none { it?.enabled == true }) {
            hideRemoteWindowRows()
            showWindowStatus(
                loading = false,
                message = "Hiện tại chưa khả dụng tính năng nào."
            )
            return
        }

        val profileVisible = bindHomeWindowRow(
            item = profileItem,
            rowId = R.id.homeWindowProfile,
            titleId = R.id.titleHomeWindowProfile,
            summaryId = R.id.summaryHomeWindowProfile,
            iconId = R.id.iconHomeWindowProfile,
            config = config
        )
        val securityVisible = bindHomeWindowRow(
            item = securityItem,
            rowId = R.id.homeWindowSecurity,
            titleId = R.id.titleHomeWindowSecurity,
            summaryId = R.id.summaryHomeWindowSecurity,
            iconId = R.id.iconHomeWindowSecurity,
            config = config
        )
        val whatsNewVisible = bindHomeWindowRow(
            item = whatsNewItem,
            rowId = R.id.homeWindowWhatsNew,
            titleId = R.id.titleHomeWindowWhatsNew,
            summaryId = R.id.summaryHomeWindowWhatsNew,
            iconId = R.id.iconHomeWindowWhatsNew,
            config = config
        )

        findViewById<View>(R.id.dividerHomeProfileSecurity).visibility =
            if (profileVisible && securityVisible) View.VISIBLE else View.GONE

        findViewById<View>(R.id.dividerHomeSecuritySupport).visibility =
            if (profileVisible || securityVisible) View.VISIBLE else View.GONE

        findViewById<View>(R.id.dividerHomeWhatsNewLogout).visibility =
            if (whatsNewVisible) View.VISIBLE else View.GONE

        findViewById<View>(R.id.homeWindowSupportSpacer).visibility =
            if (whatsNewVisible) View.VISIBLE else View.GONE

        if (profileVisible || securityVisible || whatsNewVisible) {
            findViewById<View>(R.id.homeWindowRemoteStatus).visibility = View.GONE
        } else {
            showWindowStatus(
                loading = false,
                message = "Hiện tại chưa khả dụng tính năng nào."
            )
        }
    }

    private fun bindHomeWindowRow(
        item: WindowConfigItem?,
        rowId: Int,
        titleId: Int,
        summaryId: Int,
        iconId: Int,
        config: WindowConfig
    ): Boolean {
        val row = findViewById<View>(rowId)

        if (item == null || !item.enabled) {
            row.visibility = View.GONE
            return false
        }

        val iconResource = when (item.icon) {
            "user" -> R.drawable.user
            "security" -> R.drawable.security
            "speaker" -> R.drawable.speaker
            else -> 0
        }

        if (iconResource == 0) {
            row.visibility = View.GONE
            return false
        }

        findViewById<TextView>(titleId).text = item.title
        findViewById<TextView>(summaryId).text = item.summary
        findViewById<ImageView>(iconId).setImageResource(iconResource)
        row.alpha = if (item.enabled) 1f else 0.5f
        row.isClickable = true
        row.isFocusable = true

        row.setOnClickListener {
            if (!item.enabled) {
                PiperDialog.showMessage(
                    context = this,
                    title = "Tính năng hiện không khả dụng",
                    message = "Tính năng này hiện không khả dụng.",
                    icon = R.drawable.error_bug,
                    positiveLabel = "Đóng"
                )
                return@setOnClickListener
            }
            val destination = when (item.action) {
                "personal_info" ->
                    Intent(this, AccountProfileActivity::class.java)

                "password_security" ->
                    Intent(this, PasswordSecurityActivity::class.java)
                        .putExtra("window_config_json", config.rawJson)

                "whats_new" ->
                    Intent(this, WhatsNewActivity::class.java)

                else -> null
            }

            if (destination != null) {
                closeWindowDrawer { startActivity(destination) }
            }
        }

        row.visibility = View.VISIBLE
        return true
    }

    private fun hideRemoteWindowRows() {
        findViewById<View>(R.id.homeWindowProfile).visibility = View.GONE
        findViewById<View>(R.id.homeWindowSecurity).visibility = View.GONE
        findViewById<View>(R.id.homeWindowWhatsNew).visibility = View.GONE
        findViewById<View>(R.id.dividerHomeProfileSecurity).visibility = View.GONE
        findViewById<View>(R.id.dividerHomeSecuritySupport).visibility = View.GONE
        findViewById<View>(R.id.dividerHomeWhatsNewLogout).visibility = View.GONE
        findViewById<View>(R.id.homeWindowSupportSpacer).visibility = View.GONE
    }

    private fun showWindowStatus(
        loading: Boolean,
        message: String
    ) {
        findViewById<View>(R.id.homeWindowRemoteStatus).visibility = View.VISIBLE
        findViewById<View>(R.id.homeWindowLoading).visibility =
            if (loading) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeWindowStatusIcon).visibility =
            if (loading) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.homeWindowStatusMessage).text = message
    }
    private fun closeWindowDrawer(afterClosed: (() -> Unit)? = null): Boolean {
        val overlay = findViewById<View>(R.id.homeWindowOverlay)
        if (overlay.visibility != View.VISIBLE) {
            afterClosed?.invoke()
            return false
        }

        val panel = findViewById<View>(R.id.homeWindowPanel)
        val scrim = findViewById<View>(R.id.homeWindowScrim)
        val distance = panel.width.takeIf { it > 0 } ?: dp(320)

        panel.animate().cancel()
        scrim.animate().cancel()
        panel.animate()
            .translationX(distance.toFloat())
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                overlay.visibility = View.GONE
                panel.translationX = 0f
                afterClosed?.invoke()
            }
            .start()
        scrim.animate().alpha(0f).setDuration(180L).start()

        showBottomNav(force = true)
        return true
    }

    private fun openSupport() {
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:gayivt@gmail.com")
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.home_support_email_subject))
        }

        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(
                this,
                R.string.home_support_no_email,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmLogout() {
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.auth_logout),
            message = getString(R.string.logout_confirmation),
            positiveLabel = getString(R.string.auth_logout),
            destructive = true
        ) {
            DeviceSessionManager.endCurrentSession(applicationContext) {
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }
    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (closeWindowDrawer()) return

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
                    backToast = Toast.makeText(
                        baseContext,
                        R.string.home_press_again_to_exit,
                        Toast.LENGTH_SHORT
                    )
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
        // Commit synchronously so the new page is ready before the next frame.
        transaction.setReorderingAllowed(true)
        transaction.replace(R.id.fragment_container, fragment)
        transaction.commitNow()
        val page = fragment.view ?: return
        page.alpha = 0f
        page.translationY = dp(6).toFloat()
        page.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(170L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withLayer()
            .start()
    }

    private fun fragmentForTab(index: Int): Fragment = when (index) {
        1 -> BetaFragment()
        2 -> AppsFragment()
        3 -> SettingFragment()
        4 -> InfoFragment()
        else -> homeFragment()
    }

    private fun updateTabUI(selectedIndex: Int) {
        updateScreenHeader(selectedIndex)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val activeColorCode = Color.parseColor(if (dark) "#8DD7FF" else "#156A9A")
        val unselectedColor = Color.parseColor(if (dark) "#A2B0BC" else "#8D98A1")

        for (i in listIcons.indices) {
            if (i == selectedIndex) {
                listIcons[i].imageTintList = ColorStateList.valueOf(activeColorCode)
                listTexts[i].setTextColor(activeColorCode)
                listTexts[i].setTypeface(listTexts[i].typeface, Typeface.BOLD)
            } else {
                listIcons[i].imageTintList = ColorStateList.valueOf(unselectedColor)
                listTexts[i].setTextColor(unselectedColor)
                listTexts[i].setTypeface(listTexts[i].typeface, Typeface.NORMAL)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun hideBottomNav(force: Boolean = false) {
        if (!force && currentTab != 2) return
        if (isNavHidden) return

        val bottomBar = findViewById<View>(R.id.bottomNavCard)

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

        val bottomBar = findViewById<View>(R.id.bottomNavCard)

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
        private const val SESSION_CHECK_INTERVAL_MS = 10_000L
        private const val STATE_CURRENT_TAB = "home.current_tab"
    }
}
