package com.piperostool

import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class ForgotPassword : AppCompatActivity() {

    private lateinit var edtEmail: EditText
    private lateinit var btnReset: Button
    private lateinit var tvBackToLogin: TextView

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Phải vẽ giao diện ra trước
        setContentView(R.layout.activity_forgotpassword)

        // 2. Ẩn Status Bar tạo hiệu ứng tràn viền LiquidGlass
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

        initViews()
        setupListeners()
    }

    private fun initViews() {
        edtEmail = findViewById(R.id.edtForgotEmail)
        btnReset = findViewById(R.id.btnResetPassword)
        tvBackToLogin = findViewById(R.id.tvBackToLoginFromForgot)
    }

    private fun setupListeners() {
        btnReset.setOnClickListener {
            val email = edtEmail.text.toString().trim()

            if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                edtEmail.error = "Vui lòng nhập Email hợp lệ"
                return@setOnClickListener
            }

            // Hiệu ứng loading
            btnReset.isEnabled = false
            btnReset.text = "SENDING..."
            btnReset.alpha = 0.5f

            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "Recovery link sent! Please check your email.", Toast.LENGTH_LONG).show()
                        finish() // Tự động quay về màn Login
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    } else {
                        Toast.makeText(this, "Lỗi: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                        btnReset.isEnabled = true
                        btnReset.text = "SEND RESET LINK"
                        btnReset.alpha = 1.0f
                    }
                }
        }

        tvBackToLogin.setOnClickListener {
            finish() // Tắt màn hình hiện tại để lùi về Login
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Xóa lỗi khi người dùng bắt đầu gõ lại
        edtEmail.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) edtEmail.error = null
        }
    }
}