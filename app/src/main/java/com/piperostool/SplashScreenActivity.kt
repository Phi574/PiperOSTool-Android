package com.piperostool

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlin.random.Random

class SplashScreenActivity : AppCompatActivity() {

    private lateinit var codeRainTextView: TextView
    private lateinit var appNameTextView: TextView
    private lateinit var developerTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var codeRainJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_screen)

        codeRainTextView = findViewById(R.id.codeRainTextView)
        appNameTextView = findViewById(R.id.appNameTextView)
        developerTextView = findViewById(R.id.developerTextView)

        startCodeRainEffect()

        handler.postDelayed({
            stopCodeRainEffect()
            showAppNameAndDeveloper()
        }, 3500)
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
                delay(50) // Cập nhật mỗi 50ms để tạo hiệu ứng động
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


        val slideInDeveloper = ObjectAnimator.ofFloat(developerTextView, "translationY", 100f, 0f) // Trượt từ dưới lên vị trí ban đầu
        slideInDeveloper.duration = 800
        slideInDeveloper.interpolator = DecelerateInterpolator()

        val fadeInDeveloper = ObjectAnimator.ofFloat(developerTextView, "alpha", 0.0f, 1.0f)
        fadeInDeveloper.duration = 800

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(fadeInAppName, slideInDeveloper, fadeInDeveloper)
        animatorSet.startDelay = 200 // Đợi một chút sau khi appNameTextView bắt đầu

        animatorSet.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {

                handler.postDelayed({
                    val intent = Intent(this@SplashScreenActivity, WelcomeActivity::class.java)
                    startActivity(intent)
                    finish()
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