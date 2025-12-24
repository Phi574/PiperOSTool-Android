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
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import kotlin.random.Random

class SplashScreenActivity : AppCompatActivity() {

    private lateinit var codeRainTextView: TextView
    private lateinit var appNameTextView: TextView
    private lateinit var developerTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var codeRainJob: Job? = null

    // --- Thêm logic Biometric vào đây ---
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_screen)

        codeRainTextView = findViewById(R.id.codeRainTextView)
        appNameTextView = findViewById(R.id.appNameTextView)
        developerTextView = findViewById(R.id.developerTextView)

        // Khởi tạo Biometric
        setupBiometric()

        startCodeRainEffect()
        handler.postDelayed({
            stopCodeRainEffect()
            showAppNameAndDeveloper()
        }, 3500)
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

    // --- LOGIC ĐIỀU HƯỚNG MỚI ---
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
                    // Ưu tiên 1: CÓ mật khẩu (có thể có hoặc không có vân tay)
                    // -> Chuyển sang LockScreenActivity để xử lý toàn bộ logic (pass, vân tay, cấm...)
                    hasPassword -> {
                        val intent = Intent(this@SplashScreenActivity, LockScreenActivity::class.java)
                        intent.putExtra("IS_UNLOCK_MODE", true)
                        navigateTo(intent)
                    }

                    // Ưu tiên 2: KHÔNG có mật khẩu, nhưng CÓ vân tay
                    isFingerprintEnabled -> {
                        // -> Hiện popup quét vân tay ngay tại đây
                        biometricPrompt.authenticate(promptInfo)
                    }

                    // Trường hợp còn lại: KHÔNG có cả hai
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
                    // Quét thành công -> Vào Home
                    Toast.makeText(applicationContext, "Xác thực thành công!", Toast.LENGTH_SHORT).show()
                    navigateTo(HomeActivity::class.java)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Người dùng bấm Hủy hoặc lỗi -> Thoát ứng dụng
                    // Điều này ngăn người dùng bypass khi chỉ có vân tay
                    Toast.makeText(applicationContext, "Xác thực bị hủy.", Toast.LENGTH_SHORT).show()
                    finish()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Vân tay không đúng, không làm gì cả, popup vẫn hiện để thử lại
                    Toast.makeText(applicationContext, "Vân tay không đúng.", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực vân tay")
            .setSubtitle("Mở khóa Piper OS Tool")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Thoát") // Đổi nút "Sử dụng mật khẩu" thành "Thoát"
            .build()
    }


    // Hàm điều hướng tiện ích
    private fun navigateTo(activityClass: Class<*>) {
        val intent = Intent(this, activityClass)
        startActivity(intent)
        finish()
    }
    private fun navigateTo(intent: Intent) {
        startActivity(intent)
        finish()
    }


    // --- Các hàm cũ giữ nguyên ---
    private fun startCodeRainEffect() {
        // ... (Giữ nguyên)
        val random = Random(System.currentTimeMillis())
        codeRainTextView.post {
            val width = codeRainTextView.width
            val height = codeRainTextView.height
            if (width > 0 && height > 0) {
                val textSize = if (codeRainTextView.textSize > 0) codeRainTextView.textSize.toInt() else 12
                val screenWidth = width / textSize
                val screenHeight = height / textSize

                codeRainJob = CoroutineScope(Dispatchers.Default).launch {
                    val stringBuilder = StringBuilder()
                    val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')')

                    while (isActive) {
                        stringBuilder.clear()
                        for (i in 0 until screenHeight * screenWidth) {
                            stringBuilder.append(charPool[random.nextInt(charPool.size)])
                        }
                        withContext(Dispatchers.Main) {
                            codeRainTextView.text = stringBuilder.toString()
                        }
                        delay(50)
                    }
                }
            }
        }
    }

    private fun stopCodeRainEffect() {
        codeRainJob?.cancel()
        codeRainTextView.text = ""
    }

    private fun showAppNameAndDeveloper() {
        val fadeInAppName = ObjectAnimator.ofFloat(appNameTextView, "alpha", 0.0f, 1.0f)
        fadeInAppName.duration = 1000

        val slideInDeveloper = ObjectAnimator.ofFloat(developerTextView, "translationY", 100f, 0f)
        slideInDeveloper.duration = 800
        slideInDeveloper.interpolator = DecelerateInterpolator()

        val fadeInDeveloper = ObjectAnimator.ofFloat(developerTextView, "alpha", 0.0f, 1.0f)
        fadeInDeveloper.duration = 800

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(fadeInAppName, slideInDeveloper, fadeInDeveloper)
        animatorSet.startDelay = 200

        animatorSet.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                handler.postDelayed({
                    checkAndRequestStoragePermission()
                }, 1000)
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
        animatorSet.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        codeRainJob?.cancel()
    }
}
