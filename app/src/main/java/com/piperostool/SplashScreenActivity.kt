package com.piperostool

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt // Import đúng
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import kotlin.random.Random

class SplashScreenActivity : AppCompatActivity() {

    private lateinit var codeRainTextView: TextView
    private lateinit var appNameTextView: TextView
    private lateinit var developerTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var codeRainJob: Job? = null

    // --- Biometric Components ---
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    // ----------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_screen)

        codeRainTextView = findViewById(R.id.codeRainTextView)
        appNameTextView = findViewById(R.id.appNameTextView)
        developerTextView = findViewById(R.id.developerTextView)

        // Setup Biometric
        setupBiometric()

        // Start UI effects
        startCodeRainEffect()
        handler.postDelayed({
            stopCodeRainEffect()
            showAppNameAndDeveloper()
        }, 3500)
    }

    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Nếu lỗi không phải do người dùng tự hủy -> Đăng xuất
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(applicationContext, "Xác thực thất bại, vui lòng đăng nhập lại.", Toast.LENGTH_LONG).show()
                        forceLogout()
                    } else {
                        // Người dùng tự hủy -> đóng app
                        finish()
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // Xác thực thành công -> Tiếp tục vào app
                    proceedToApp()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Vân tay không khớp, người dùng có thể thử lại
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Yêu cầu xác thực")
            .setSubtitle("Sử dụng vân tay để mở Piper OS Tool")
            .setNegativeButtonText("Thoát")
            .build()
    }

    private fun startCodeRainEffect() {
        val random = Random(System.currentTimeMillis())
        val screenWidth = resources.displayMetrics.widthPixels / codeRainTextView.textSize.toInt()
        val screenHeight = resources.displayMetrics.heightPixels / codeRainTextView.textSize.toInt()

        codeRainJob = CoroutineScope(Dispatchers.Default).launch {
            val stringBuilder = StringBuilder()
            val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')')

            while (isActive) { // Tiếp tục chạy cho đến khi job bị hủy
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
                    checkBiometric()
                }, 1000)
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
        animatorSet.start()
    }

    private fun checkBiometric() {
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val isFingerprintEnabled = prefs.getBoolean("fingerprint_enabled", false)
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (isFingerprintEnabled && currentUser != null) {
            biometricPrompt.authenticate(promptInfo)
        } else {
            proceedToApp()
        }
    }

    private fun proceedToApp() {
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser != null) {
            val intent = Intent(this@SplashScreenActivity, HomeActivity::class.java)
            startActivity(intent)
        } else {
            val intent = Intent(this@SplashScreenActivity, WelcomeActivity::class.java)
            startActivity(intent)
        }
        finish()
    }

    private fun forceLogout() {
        FirebaseAuth.getInstance().signOut()
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        codeRainJob?.cancel()
    }
}
