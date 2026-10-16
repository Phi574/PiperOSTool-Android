package com.piperostool

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class ForgotPassword : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUIRED_PASSWORD_UPGRADE = "required_password_upgrade"
        const val EXTRA_EMAIL = "recovery_email"
    }

    private lateinit var edtEmail: EditText
    private lateinit var btnReset: Button
    private lateinit var tvBackToLogin: TextView
    private lateinit var root: View
    private lateinit var offlineState: View
    private var requiredPasswordUpgrade = false

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Phải vẽ giao diện ra trước
        setContentView(R.layout.activity_forgotpassword)

        // Khởi tạo Firebase Auth
        auth = Firebase.auth
        requiredPasswordUpgrade = intent.getBooleanExtra(EXTRA_REQUIRED_PASSWORD_UPGRADE, false)

        initViews()
        setupListeners()
        NetworkAccess.observe(this, this) { updateNetworkUi(it) }
    }

    private fun initViews() {
        edtEmail = findViewById(R.id.edtForgotEmail)
        btnReset = findViewById(R.id.btnResetPassword)
        tvBackToLogin = findViewById(R.id.tvBackToLoginFromForgot)
        val title = findViewById<TextView>(R.id.forgotTitle)
        val summary = findViewById<TextView>(R.id.forgotSummary)
        if (requiredPasswordUpgrade) {
            title.setText(R.string.auth_password_upgrade_title)
            summary.setText(R.string.auth_password_upgrade_summary)
            btnReset.setText(R.string.auth_password_upgrade_send)
            edtEmail.setText(intent.getStringExtra(EXTRA_EMAIL).orEmpty())
        }
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
            btnReset.text = getString(R.string.auth_reset_sending)
            btnReset.alpha = 0.5f

            auth.useAppLanguage()
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val message = if (requiredPasswordUpgrade) {
                            R.string.auth_password_upgrade_sent
                        } else {
                            R.string.auth_reset_generic_success
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        finish() // Tự động quay về màn Login
                        overridePendingTransition(R.anim.piper_page_enter, R.anim.piper_page_exit)
                    } else {
                        showAuthError(this, task.exception)
                        btnReset.isEnabled = true
                        btnReset.text = getString(
                            if (requiredPasswordUpgrade) R.string.auth_password_upgrade_send
                            else R.string.auth_send_reset
                        )
                        btnReset.alpha = 1.0f
                    }
                }
        }

        tvBackToLogin.setOnClickListener {
            finish() // Tắt màn hình hiện tại để lùi về Login
            overridePendingTransition(R.anim.piper_page_enter, R.anim.piper_page_exit)
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
