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

        // Khởi tạo Firebase Auth
        auth = Firebase.auth

        // 3. Tự động chuyển hướng nếu User đã đăng nhập trước đó
        val currentUser = auth.currentUser
        val needsEmailVerification = currentUser?.providerData?.any {
            it.providerId == com.google.firebase.auth.EmailAuthProvider.PROVIDER_ID
        } == true && currentUser?.isEmailVerified == false
        if (needsEmailVerification) auth.signOut()
        if (currentUser != null && !needsEmailVerification) {
            AccountSessionGuard.verify(this) { state ->
                when (state) {
                    AccountSessionState.Valid, AccountSessionState.Offline -> {
                        startActivity(Intent(this, HomeActivity::class.java))
                        finish()
                    }
                    is AccountSessionState.Expired -> {
                        auth.signOut()
                        recreate()
                    }
                }
            }
            return
        }

        AuthJsonHost.install(this, "welcome") {
            bindWelcomeScreen()
        }
    }

    private fun bindWelcomeScreen() {
        btnLogin = findViewById(R.id.btnLogin)
        btnSignup = findViewById(R.id.btnSignup)
        root = findViewById(R.id.welcomeRoot)
        offlineState = findViewById(R.id.welcomeOfflineState)
        findViewById<TextView>(R.id.welcomeVersion).text =
            getString(R.string.auth_version, AppVersion.name(this))

        // Chuyển sang màn Login
        btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            // Thêm hiệu ứng mờ ảo khi chuyển màn (tùy chọn)
            overridePendingTransition(R.anim.piper_page_enter, R.anim.piper_page_exit)
        }

        // Chuyển sang màn Đăng ký
        btnSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
            overridePendingTransition(R.anim.piper_page_enter, R.anim.piper_page_exit)
        }
    }
}
