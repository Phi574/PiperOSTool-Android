package com.piperostool

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.Executor

class SplashScreenActivity : AppCompatActivity() {

    private lateinit var appNameTextView: TextView
    private lateinit var developerTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var navigating = false
    private var sessionResolved = false
    private var passwordResolved = false

    // --- Biometric Variables ---
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_screen)

        // 1. ÁP DỤNG HÌNH NỀN TÙY CHỈNH NGAY LẬP TỨC
        applyCustomBackground()

        appNameTextView = findViewById(R.id.appNameTextView)
        developerTextView = findViewById(R.id.developerTextView)

        // Khởi tạo Biometric
        setupBiometric()

        // Bắt đầu hiệu ứng hiện tên App ngay lập tức
        showAppNameAndDeveloper()
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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkNavigation()
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                storagePermissionLauncher.launch(intent)
            } else {
                checkNavigation()
            }
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
                        biometricPrompt.authenticate(promptInfo)
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
            isFingerprintEnabled -> biometricPrompt.authenticate(promptInfo)
            else -> navigateTo(HomeActivity::class.java)
        }
    }

    companion object {
        const val EXTRA_SESSION_EXPIRED = "session_expired"
    }

    // --- LOGIC BIOMETRIC (VÂN TAY) ---
    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Toast.makeText(applicationContext, "Xác thực thành công!", Toast.LENGTH_SHORT).show()
                    navigateTo(HomeActivity::class.java)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Xác thực bị hủy.", Toast.LENGTH_SHORT).show()
                    finish()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Vân tay không đúng.", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực vân tay")
            .setSubtitle("Mở khóa Piper OS Tool")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Thoát")
            .build()
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

    private fun showAppNameAndDeveloper() {
        // Hiệu ứng hiện tên App (Fade In)
        val fadeInAppName = ObjectAnimator.ofFloat(appNameTextView, "alpha", 0.0f, 0.5f)
        fadeInAppName.duration = 650

        // Hiệu ứng hiện tên Dev (Trượt lên + Fade In)
        val slideInDeveloper = ObjectAnimator.ofFloat(developerTextView, "translationY", 100f, 0f)
        slideInDeveloper.duration = 650
        slideInDeveloper.interpolator = DecelerateInterpolator()

        val fadeInDeveloper = ObjectAnimator.ofFloat(developerTextView, "alpha", 0.0f, 0.5f)
        fadeInDeveloper.duration = 650

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(fadeInAppName, slideInDeveloper, fadeInDeveloper)
        animatorSet.startDelay = 100

        animatorSet.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Giữ logo một nhịp ngắn rồi điều hướng, kể cả khi offline.
                handler.postDelayed({
                    checkAndRequestStoragePermission()
                }, 250)
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
        animatorSet.start()
    }
}
