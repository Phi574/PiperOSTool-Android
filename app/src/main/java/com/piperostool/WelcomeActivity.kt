package com.piperostool

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class WelcomeActivity : AppCompatActivity() {

    private lateinit var btnLogin: Button
    private lateinit var btnSignup: Button
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Phải setContentView trước khi setInsets
        setContentView(R.layout.welcome)

        // 2. Xóa thanh trạng thái (Status bar) để tràn viền LiquidGlass
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        // Khởi tạo Firebase Auth
        auth = Firebase.auth

        // 3. Tự động chuyển hướng nếu User đã đăng nhập trước đó
        if (auth.currentUser != null) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return // Dừng hàm onCreate lại ở đây
        }

        // 4. Khai báo nút
        btnLogin = findViewById(R.id.btnLogin)
        btnSignup = findViewById(R.id.btnSignup)

        // Chuyển sang màn Login
        btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            // Thêm hiệu ứng mờ ảo khi chuyển màn (tùy chọn)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Chuyển sang màn Đăng ký
        btnSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}