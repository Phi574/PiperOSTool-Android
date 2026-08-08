package com.piperostool

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class ForgotPassword : AppCompatActivity() {

    private lateinit var edtEmail: EditText
    private lateinit var btnReset: Button
    private lateinit var tvBackToLogin: TextView
    private lateinit var root: View
    private lateinit var offlineState: View

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Phải vẽ giao diện ra trước
        setContentView(R.layout.activity_forgotpassword)

        // Khởi tạo Firebase Auth
        auth = Firebase.auth

        initViews()
        setupListeners()
        NetworkAccess.observe(this, this) { updateNetworkUi(it) }
    }

    private fun initViews() {
        edtEmail = findViewById(R.id.edtForgotEmail)
        btnReset = findViewById(R.id.btnResetPassword)
        tvBackToLogin = findViewById(R.id.tvBackToLoginFromForgot)
        root = findViewById(R.id.forgotRoot)
        AuthScreenUi.apply(this, root)
        offlineState = findViewById(R.id.forgotOfflineState)
        findViewById<TextView>(R.id.forgotVersion).text =
            getString(R.string.auth_version, AppVersion.name(this))
    }

    private fun setupListeners() {
        btnReset.setOnClickListener {
            if (!NetworkAccess.isOnline(this)) {
                NetworkAccess.showOffline(root)
                return@setOnClickListener
            }
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

    private fun updateNetworkUi(online: Boolean) {
        offlineState.visibility = if (online) View.GONE else View.VISIBLE
        btnReset.visibility = if (online) View.VISIBLE else View.GONE
        if (!online) NetworkAccess.showOffline(root)
    }
}
