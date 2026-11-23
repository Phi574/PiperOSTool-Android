package com.piperostool

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor

class SplashScreenActivity : AppCompatActivity() {

    private lateinit var appNameTextView: TextView
    private lateinit var developerTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var navigating = false
    private var sessionResolved = false
    private var passwordResolved = false
    private var startupCheckInProgress = false
    private var startupChecksResolved = false
    private var biometricRequired = false
    private var biometricPromptActive = false
    private var activityResumed = false
    private var introFinished = false
    private var introPlayer: ExoPlayer? = null

    private val updateActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!navigating) continueToApp()
    }

    // --- Biometric Variables ---
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let the intro video extend behind the hidden status/navigation bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_start_screen)

        // 1. ÁP DỤNG HÌNH NỀN TÙY CHỈNH NGAY LẬP TỨC
        applyCustomBackground()

        appNameTextView = findViewById(R.id.appNameTextView)
        developerTextView = findViewById(R.id.developerTextView)

        // Khởi tạo Biometric
        setupBiometric()

        startStartupIntro()
    }

    // ==========================================================
    // HÀM ÁP DỤNG HÌNH NỀN TÙY CHỈNH
    // ==========================================================
    private fun applyCustomBackground() {
        val prefs = AccountDataScope.preferences(this, "PiperPrefs")
        val hasCustomBg = prefs.getBoolean("has_custom_bg", false)

        // Lấy trực tiếp lớp gốc ngoài cùng của màn hình
        val bgView = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        if (hasCustomBg) {
            try {
                // Đọc file ảnh custom_bg.jpg từ bộ nhớ kín của app
                val file = AccountDataScope.file(this, "appearance", "custom_bg.jpg")
                if (file.exists()) {
                    val drawable = android.graphics.drawable.Drawable.createFromPath(file.absolutePath)
                    bgView.background = drawable
                } else {
                    bgView.setBackgroundResource(R.drawable.backgroud)
                }
            } catch (e: Exception) {
                bgView.setBackgroundResource(R.drawable.backgroud)
            }
        } else {
            bgView.setBackgroundResource(R.drawable.backgroud)
        }
    }

    private val permissionSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkNavigation()
    }

    private fun startStartupChecks() {
        if (startupCheckInProgress || startupChecksResolved || navigating) return
        startupCheckInProgress = true
        findViewById<ViewGroup>(R.id.startupCheckPanel).visibility = android.view.View.VISIBLE
        appNameTextView.visibility = android.view.View.GONE
        developerTextView.visibility = android.view.View.GONE
        findViewById<android.widget.ProgressBar>(R.id.startupCheckProgress).visibility = android.view.View.VISIBLE
        setStartupStep("Đang kiểm tra Internet…")
        setStartupLine(R.id.startupInternetStatus, "Internet", InfoHealthState.CHECKING, "Đang kiểm tra")
        setStartupLine(R.id.startupGithubStatus, "GitHub", InfoHealthState.CHECKING, "Chờ kiểm tra")
        setStartupLine(R.id.startupFirebaseStatus, "Firebase Auth", InfoHealthState.CHECKING, "Chờ kiểm tra")
        setStartupLine(R.id.startupUpdateStatus, "Bản cập nhật", InfoHealthState.CHECKING, "Chờ kiểm tra")

        lifecycleScope.launch {
            try {
                val internet = InfoConnectivityChecker.checkInternetNow(this@SplashScreenActivity)
                showStartupResult(R.id.startupInternetStatus, internet)
                if (internet.state == InfoHealthState.UNAVAILABLE) {
                    startupCheckInProgress = false
                    findViewById<android.widget.ProgressBar>(R.id.startupCheckProgress).visibility = android.view.View.GONE
                    setStartupStep("Không có kết nối Internet")
                    showOfflineChoice()
                    return@launch
                }

                setStartupStep("Đang kiểm tra GitHub…")
                showStartupResult(
                    R.id.startupGithubStatus,
                    InfoConnectivityChecker.checkGitHubNow(this@SplashScreenActivity)
                )

                setStartupStep("Đang kiểm tra Firebase Auth…")
                showStartupResult(
                    R.id.startupFirebaseStatus,
                    InfoConnectivityChecker.checkFirebaseAuthNow(this@SplashScreenActivity)
                )

                setStartupStep("Đang kiểm tra phiên bản cập nhật…")
                val latest = try {
                    withTimeout(20_000L) {
                        val available = AppUpdateRepository.releases()
                        val preferences = getSharedPreferences("update_program", MODE_PRIVATE)
                        val channel = AppUpdateRepository.selectedChannel(preferences)
                        val current = AppVersion.name(this@SplashScreenActivity)
                        AppUpdateRepository.nextRelease(available, current, channel)
                            ?: available.lastOrNull { AppUpdateRepository.isEligible(it.tag, channel) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val localVersion = AppUpdateRepository.version(AppVersion.name(this@SplashScreenActivity))
                val latestVersion = latest?.let { AppUpdateRepository.version(it.tag) }
                val hasUpdate = latest != null && localVersion != null && latestVersion != null && latestVersion > localVersion
                when {
                    latest == null -> setStartupLine(
                        R.id.startupUpdateStatus,
                        "Bản cập nhật",
                        InfoHealthState.UNAVAILABLE,
                        "Không lấy được danh sách GitHub Releases"
                    )
                    hasUpdate -> setStartupLine(
                        R.id.startupUpdateStatus,
                        "Bản cập nhật",
                        InfoHealthState.SLOW,
                        "Có phiên bản mới ${latest.tag}"
                    )
                    else -> setStartupLine(
                        R.id.startupUpdateStatus,
                        "Bản cập nhật",
                        InfoHealthState.HEALTHY,
                        "Đang dùng bản mới nhất"
                    )
                }

                startupCheckInProgress = false
                if (hasUpdate) {
                    findViewById<android.widget.ProgressBar>(R.id.startupCheckProgress).visibility = android.view.View.GONE
                    setStartupStep("Đã kiểm tra xong")
                    showUpdateAvailable(latest)
                } else {
                    setStartupStep("Đã kiểm tra xong")
                    delay(750L)
                    continueToApp()
                }
            } catch (cancelled: CancellationException) {
                startupCheckInProgress = false
                throw cancelled
            } catch (_: Exception) {
                startupCheckInProgress = false
                delay(750L)
                continueToApp()
            }
        }
    }

    private fun startStartupIntro() {
        val playerView = findViewById<PlayerView>(R.id.startupIntroVideo)
        findViewById<View>(R.id.startupSkipTapArea).setOnClickListener {
            finishStartupIntro(skipped = true)
        }
        hideSystemBarsForIntro()

        val player = ExoPlayer.Builder(this).build()
        introPlayer = player
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finishStartupIntro(skipped = false)
            }

            override fun onPlayerError(error: PlaybackException) {
                finishStartupIntro(skipped = true)
            }
        })
        player.setMediaItem(MediaItem.fromUri("android.resource://$packageName/${R.raw.videobootapp}"))
        player.prepare()
        player.playWhenReady = true
    }

    private fun hideSystemBarsForIntro() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBarsAfterIntro() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun finishStartupIntro(skipped: Boolean) {
        if (introFinished || isFinishing || isDestroyed) return
        introFinished = true

        val playerView = findViewById<PlayerView>(R.id.startupIntroVideo)
        val flash = findViewById<View>(R.id.startupTransitionFlash)
        findViewById<View>(R.id.startupSkipTapArea).apply {
            isEnabled = false
            visibility = View.GONE
        }
        flash.visibility = View.VISIBLE
        flash.alpha = 0f

        if (!skipped && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            playerView.setRenderEffect(
                RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
            )
        }

        val duration = if (skipped) 180L else 620L
        val videoFade = ObjectAnimator.ofFloat(playerView, View.ALPHA, playerView.alpha, 0f)
        val videoZoomX = ObjectAnimator.ofFloat(playerView, View.SCALE_X, 1f, 1.035f)
        val videoZoomY = ObjectAnimator.ofFloat(playerView, View.SCALE_Y, 1f, 1.035f)
        val lightBloom = ObjectAnimator.ofFloat(
            flash,
            View.ALPHA,
            0f,
            if (skipped) 0.48f else 0.92f,
            0f
        )
        AnimatorSet().apply {
            playTogether(videoFade, videoZoomX, videoZoomY, lightBloom)
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        playerView.setRenderEffect(null)
                    }
                    playerView.visibility = View.GONE
                    flash.visibility = View.GONE
                    introPlayer?.pause()
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    showSystemBarsAfterIntro()
                    startStartupChecks()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) = Unit
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
            })
            start()
        }
    }

    private fun showStartupResult(viewId: Int, result: InfoHealthItem) {
        setStartupLine(viewId, result.title, result.state, result.detail)
    }

    private fun setStartupLine(viewId: Int, title: String, state: InfoHealthState, detail: String) {
        val color = when (state) {
            InfoHealthState.HEALTHY -> Color.rgb(34, 197, 94)
            InfoHealthState.SLOW, InfoHealthState.WARNING -> Color.rgb(245, 158, 11)
            InfoHealthState.UNAVAILABLE -> Color.rgb(239, 68, 68)
            InfoHealthState.CHECKING -> Color.argb(210, 255, 255, 255)
        }
        val dot = when (state) {
            InfoHealthState.HEALTHY -> "●"
            InfoHealthState.SLOW, InfoHealthState.WARNING -> "●"
            InfoHealthState.UNAVAILABLE -> "●"
            InfoHealthState.CHECKING -> "○"
        }
        findViewById<TextView>(viewId).apply {
            text = "$dot  $title · $detail"
            setTextColor(color)
        }
    }

    private fun setStartupStep(message: String) {
        findViewById<TextView>(R.id.startupCurrentStep).text = message
    }

    private fun showOfflineChoice() {
        PiperDialog.showCustom(
            context = this,
            title = "Không có kết nối Internet",
            message = "Bạn muốn tiếp tục Offline hay thử lại toàn bộ quy trình kiểm tra?",
            icon = R.drawable.ic_browser_globe,
            positiveLabel = "Thử lại",
            negativeLabel = "Tiếp tục Offline",
            onPositive = {
                startStartupChecks()
                true
            },
            onNegative = { continueToApp() }
        ).setCanceledOnTouchOutside(false)
    }

    private fun showUpdateAvailable(release: AppRelease) {
        val notes = release.description
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*-\\s+"), "• ")
            .replace("**", "")
            .replace("`", "")
            .trim()
            .take(420)
        PiperDialog.showCustom(
            context = this,
            title = "Có phiên bản mới ${release.tag}",
            message = notes.ifBlank { "Đã có phiên bản PiperOS Tool mới trên GitHub Releases." },
            icon = R.drawable.details,
            positiveLabel = "Cập nhật",
            negativeLabel = "Bỏ qua",
            onPositive = {
                updateActivityLauncher.launch(Intent(this, AppUpdateActivity::class.java))
                true
            },
            onNegative = { continueToApp() }
        ).setCanceledOnTouchOutside(false)
    }

    private fun continueToApp() {
        if (startupChecksResolved || navigating) return
        startupChecksResolved = true
        startupCheckInProgress = false
        if (StartupPermissionSetupActivity.shouldShow(this)) {
            permissionSetupLauncher.launch(Intent(this, StartupPermissionSetupActivity::class.java))
        } else {
            checkNavigation()
        }
    }

    // --- LOGIC ĐIỀU HƯỚNG ---
    private fun checkNavigation() {
        if (navigating) return
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            // 1. Chưa đăng nhập -> Vào Welcome
            navigateTo(WelcomeActivity::class.java)
            return
        }
        val emailNeedsVerification = currentUser.providerData.any {
            it.providerId == com.google.firebase.auth.EmailAuthProvider.PROVIDER_ID
        } && !currentUser.isEmailVerified
        if (emailNeedsVerification) {
            FirebaseAuth.getInstance().signOut()
            navigateTo(LoginActivity::class.java)
            return
        }

        val timeout = Runnable {
            if (!sessionResolved && !navigating) {
                sessionResolved = true
                continueSecurityNavigation(currentUser.uid, forceOffline = true)
            }
        }
        handler.postDelayed(timeout, 3_500L)
        AccountSessionGuard.verify(this) { state ->
            if (sessionResolved || navigating) return@verify
            sessionResolved = true
            handler.removeCallbacks(timeout)
            when (state) {
                AccountSessionState.Valid, AccountSessionState.Offline ->
                    continueSecurityNavigation(currentUser.uid)
                is AccountSessionState.Expired -> {
                    FirebaseAuth.getInstance().signOut()
                    navigateTo(LoginActivity::class.java)
                }
            }
        }
    }

    private fun continueSecurityNavigation(userId: String, forceOffline: Boolean = false) {

        // 2. Đã đăng nhập -> Kiểm tra các lớp bảo mật
        val prefs = AccountDataScope.preferences(this, "PiperPrefs")
        val isFingerprintEnabled = prefs.getBoolean("fingerprint_enabled", false)

        if (forceOffline || !NetworkAccess.isOnline(this)) {
            continueWithCachedSecurity(isFingerprintEnabled)
            return
        }

        val database = FirebaseDatabase.getInstance()
        val myRef = database.getReference("users/$userId/security/password")
        val timeout = Runnable {
            if (!passwordResolved && !navigating) {
                passwordResolved = true
                continueWithCachedSecurity(isFingerprintEnabled)
            }
        }
        handler.postDelayed(timeout, 3_500L)

        myRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (passwordResolved || navigating) return
                passwordResolved = true
                handler.removeCallbacks(timeout)
                val hasPassword = snapshot.exists() && snapshot.value.toString().isNotEmpty()

                when {
                    // Ưu tiên 1: CÓ mật khẩu -> LockScreenActivity
                    hasPassword -> {
                        val intent = Intent(this@SplashScreenActivity, LockScreenActivity::class.java)
                        intent.putExtra("IS_UNLOCK_MODE", true)
                        navigateTo(intent)
                    }

                    // Ưu tiên 2: KHÔNG có mật khẩu, nhưng CÓ vân tay -> Quét luôn
                    isFingerprintEnabled -> {
                        requestBiometricUnlock()
                    }

                    // Trường hợp còn lại: KHÔNG có cả hai -> Vào Home
                    else -> {
                        navigateTo(HomeActivity::class.java)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (passwordResolved || navigating) return
                passwordResolved = true
                handler.removeCallbacks(timeout)
                continueWithCachedSecurity(isFingerprintEnabled)
            }
        })
    }

    private fun continueWithCachedSecurity(isFingerprintEnabled: Boolean) {
        if (navigating) return
        val lockPrefs = AccountDataScope.preferences(this, LockScreenActivity.PREFS_NAME)
        when {
            lockPrefs.getString(LockScreenActivity.KEY_CACHED_PASS, null) != null -> {
                val intent = Intent(this, LockScreenActivity::class.java)
                intent.putExtra("IS_UNLOCK_MODE", true)
                navigateTo(intent)
            }
            isFingerprintEnabled -> requestBiometricUnlock()
            else -> navigateTo(HomeActivity::class.java)
        }
    }

    companion object {
        const val EXTRA_SESSION_EXPIRED = "session_expired"
        private const val BIOMETRIC_RESUME_DELAY_MS = 350L
    }

    // --- LOGIC BIOMETRIC (VÂN TAY) ---
    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    biometricPromptActive = false
                    biometricRequired = false
                    Toast.makeText(applicationContext, "Xác thực thành công!", Toast.LENGTH_SHORT).show()
                    navigateTo(HomeActivity::class.java)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    biometricPromptActive = false
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        biometricRequired = false
                        finish()
                        return
                    }
                    biometricRequired = true
                    val message = if (errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        "Xác thực tạm dừng. Mở khóa ứng dụng để quét vân tay lại."
                    } else {
                        "Chưa xác thực được vân tay: $errString. Mở lại để thử lại."
                    }
                    setStartupStep(message)
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                    if ((errorCode == BiometricPrompt.ERROR_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED) && activityResumed) {
                        handler.postDelayed({ showBiometricPromptIfReady() }, BIOMETRIC_RESUME_DELAY_MS)
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Vân tay không đúng.", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực vân tay")
            .setSubtitle("Mở khóa PiperOS Tool")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Thoát")
            .build()
    }

    private fun requestBiometricUnlock() {
        biometricRequired = true
        setStartupStep("Đang chờ xác thực vân tay…")
        showBiometricPromptIfReady()
    }

    private fun showBiometricPromptIfReady() {
        if (!biometricRequired || biometricPromptActive || !activityResumed || navigating) return
        biometricPromptActive = true
        runCatching { biometricPrompt.authenticate(promptInfo) }
            .onFailure { error ->
                biometricPromptActive = false
                setStartupStep("Không mở được xác thực vân tay: ${error.message ?: "hãy thử lại"}")
            }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (!introFinished) introPlayer?.play()
        if (biometricRequired && !biometricPromptActive && !navigating) {
            handler.postDelayed({ showBiometricPromptIfReady() }, BIOMETRIC_RESUME_DELAY_MS)
        }
    }

    override fun onPause() {
        activityResumed = false
        if (!introFinished) introPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        introPlayer?.release()
        introPlayer = null
        super.onDestroy()
    }

    private fun navigateTo(activityClass: Class<*>) {
        if (navigating) return
        navigating = true
        handler.removeCallbacksAndMessages(null)
        val intent = Intent(this, activityClass)
        startActivity(intent)
        finish()
    }

    private fun navigateTo(intent: Intent) {
        if (navigating) return
        navigating = true
        handler.removeCallbacksAndMessages(null)
        startActivity(intent)
        finish()
    }

}
