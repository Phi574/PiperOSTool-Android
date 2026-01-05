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

    // --- Biometric Variables ---
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_screen)

        appNameTextView = findViewById(R.id.appNameTextView)
        developerTextView = findViewById(R.id.developerTextView)

        // Khởi tạo Biometric
        setupBiometric()

        // Bắt đầu hiệu ứng hiện tên App ngay lập tức
        showAppNameAndDeveloper()
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
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            // 1. Chưa đăng nhập -> Vào Welcome
            navigateTo(WelcomeActivity::class.java)
            return
        }

        // 2. Đã đăng nhập -> Kiểm tra các lớp bảo mật
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val isFingerprintEnabled = prefs.getBoolean("fingerprint_enabled", false)

        val database = FirebaseDatabase.getInstance()
        val userId = currentUser.uid
        val myRef = database.getReference("users/$userId/security/password")

        myRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
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
                // Lỗi mạng, tạm cho vào Home
                navigateTo(HomeActivity::class.java)
            }
        })
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
        val intent = Intent(this, activityClass)
        startActivity(intent)
        finish()
    }

    private fun navigateTo(intent: Intent) {
        startActivity(intent)
        finish()
    }

    private fun showAppNameAndDeveloper() {
        // Hiệu ứng hiện tên App (Fade In)
        val fadeInAppName = ObjectAnimator.ofFloat(appNameTextView, "alpha", 0.0f, 1.0f)
        fadeInAppName.duration = 1000

        // Hiệu ứng hiện tên Dev (Trượt lên + Fade In)
        val slideInDeveloper = ObjectAnimator.ofFloat(developerTextView, "translationY", 100f, 0f)
        slideInDeveloper.duration = 800
        slideInDeveloper.interpolator = DecelerateInterpolator()

        val fadeInDeveloper = ObjectAnimator.ofFloat(developerTextView, "alpha", 0.0f, 1.0f)
        fadeInDeveloper.duration = 800

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(fadeInAppName, slideInDeveloper, fadeInDeveloper)
        animatorSet.startDelay = 200 // Đợi 0.2s rồi mới bắt đầu hiện

        animatorSet.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Sau khi hiện chữ xong, đợi 1 giây rồi kiểm tra quyền/điều hướng
                handler.postDelayed({
                    checkAndRequestStoragePermission()
                }, 1000)
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
        animatorSet.start()
    }
}
