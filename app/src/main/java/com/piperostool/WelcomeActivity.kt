package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class WelcomeActivity : AppCompatActivity() {

    private lateinit var btnLogin: Button
    private lateinit var btnSignup: Button
    private lateinit var auth: FirebaseAuth
    private lateinit var root: View
    private lateinit var offlineState: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Phải setContentView trước khi setInsets
        setContentView(R.layout.welcome)

        // Khởi tạo Firebase Auth
        auth = Firebase.auth

        // 3. Tự động chuyển hướng nếu User đã đăng nhập trước đó
        AccountSessionGuard.cachedDisabled(this)?.let { disabled ->
            startActivity(DisabledAccountActivity.createIntent(this, disabled))
            finish()
            return
        }
        if (auth.currentUser != null) {
            AccountSessionGuard.verify(this) { state ->
                when (state) {
                    AccountSessionState.Valid, AccountSessionState.Offline -> {
                        startActivity(Intent(this, HomeActivity::class.java))
                        finish()
                    }
                    is AccountSessionState.Disabled ->
                        startActivity(DisabledAccountActivity.createIntent(this, state))
                    is AccountSessionState.Expired -> {
                        auth.signOut()
                        recreate()
                    }
                }
            }
            return
        }

        // 4. Khai báo nút
        btnLogin = findViewById(R.id.btnLogin)
        btnSignup = findViewById(R.id.btnSignup)
        root = findViewById(R.id.welcomeRoot)
        AuthScreenUi.apply(this, root)
        offlineState = findViewById(R.id.welcomeOfflineState)
        findViewById<TextView>(R.id.welcomeVersion).text =
            getString(R.string.auth_version, AppVersion.name(this))
        NetworkAccess.observe(this, this) { online ->
            offlineState.visibility = if (online) View.GONE else View.VISIBLE
            btnLogin.visibility = if (online) View.VISIBLE else View.GONE
            btnSignup.visibility = if (online) View.VISIBLE else View.GONE
            if (!online) NetworkAccess.showOffline(root)
        }

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
