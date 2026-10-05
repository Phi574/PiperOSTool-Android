package com.piperostool

import android.content.Intent
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore

class LoginActivity : AppCompatActivity() {

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnGoogle: Button
    private lateinit var btnPhone: Button
    private lateinit var btnResendVerification: Button
    private lateinit var tvGoToSignUp: TextView
    private lateinit var tvForgotPassword: TextView
    private lateinit var root: View
    private lateinit var offlineState: View

    // Khai báo Firebase
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.login_screen)

        auth = Firebase.auth
        db = Firebase.firestore

        initViews()
        setupListeners()
        NetworkAccess.observe(this, this) { updateNetworkUi(it) }
        // Consume legacy session extras without showing a persistent Toast.
        intent.removeExtra(SplashScreenActivity.EXTRA_SESSION_EXPIRED)
    }

    public override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // Logic tự động đăng nhập nếu cần
        }
    }

    private fun initViews() {
        // SỬA: Map ID chuẩn theo file login_screen.xml mới
        etEmail = findViewById(R.id.edtUsername)
        etPassword = findViewById(R.id.edtPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnGoogle = findViewById(R.id.btnGoogleSignIn)
        btnPhone = findViewById(R.id.btnPhoneSignIn)
        btnResendVerification = findViewById(R.id.btnResendVerification)
        tvGoToSignUp = findViewById(R.id.tvSignUp) // Cập nhật đúng ID nút Đăng ký
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        root = findViewById(R.id.loginRoot)
        AuthScreenUi.apply(
            this,
            root,
            findViewById(R.id.authBackground),
            findViewById(R.id.modernAuthOverlay)
        )
        offlineState = findViewById(R.id.loginOfflineState)
        findViewById<TextView>(R.id.authVersion).text =
            getString(R.string.auth_version, AppVersion.name(this))
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            NetworkAccess.requireOnline(root) {
                if (validateInput()) {
                    performLogin()
                }
            }
        }

        btnGoogle.setOnClickListener {
            NetworkAccess.requireOnline(root) { signInWithGoogle() }
        }
        btnPhone.setOnClickListener {
            startActivity(Intent(this, PhoneAuthActivity::class.java))
            overridePendingTransition(R.anim.piper_page_enter, R.anim.piper_page_exit)
        }
        btnResendVerification.setOnClickListener {
            NetworkAccess.requireOnline(root) { resendEmailVerification() }
        }

        tvGoToSignUp.setOnClickListener {
            // Navigation between auth forms must remain available even while
            // connectivity is being checked. Only account operations require
            // an online connection.
            startActivity(Intent(this, SignupActivity::class.java))
        }

        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPassword::class.java))
        }

        // Xóa lỗi khi người dùng bấm vào ô nhập
        etEmail.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etEmail.error = null }
        etPassword.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etPassword.error = null }
    }

    private fun validateInput(): Boolean {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (email.isEmpty()) {
            etEmail.error = "Vui lòng nhập Username/Email"
            return false
        }

        if (password.isEmpty()) {
            etPassword.error = "Vui lòng nhập Password"
            return false
        }

        return true
    }

    private fun performLogin() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        // Hiệu ứng nút bấm khi loading
        setAuthButtonsEnabled(false)
        btnLogin.text = getString(R.string.auth_signing_in)

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user == null) {
                        resetLoginButton()
                        return@addOnCompleteListener
                    }
                    val passwordProvider = user.providerData.any {
                        it.providerId == com.google.firebase.auth.EmailAuthProvider.PROVIDER_ID
                    }
                    if (passwordProvider && !user.isEmailVerified) {
                        auth.signOut()
                        resetLoginButton()
                        btnResendVerification.visibility = View.VISIBLE
                        Toast.makeText(this, R.string.auth_email_verification_required, Toast.LENGTH_LONG).show()
                    } else {
                        btnResendVerification.visibility = View.GONE
                        AuthPostLogin.continueToApp(this, user.uid)
                    }
                } else {
                    resetLoginButton()
                    showAuthError(this, task.exception)
                }
            }
    }

    private fun signInWithGoogle() {
        setAuthButtonsEnabled(false)
        GoogleFirebaseSignIn.launch(auth = auth, activity = this, onBusy = { busy ->
            setAuthButtonsEnabled(!busy)
        }) { user, isNewUser, error ->
            if (user == null) {
                setAuthButtonsEnabled(true)
                showAuthError(this, error)
                return@launch
            }
            AuthSocialProfile.createForNewUser(this, user, isNewUser, null) {
                AuthPostLogin.continueToApp(this, user.uid)
            }
        }
    }

    private fun resendEmailVerification() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, R.string.auth_invalid_credentials, Toast.LENGTH_LONG).show()
            return
        }
        setAuthButtonsEnabled(false)
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(this) { signIn ->
            val user = auth.currentUser
            if (!signIn.isSuccessful || user == null) {
                auth.signOut()
                setAuthButtonsEnabled(true)
                showAuthError(this, signIn.exception)
                return@addOnCompleteListener
            }
            user.reload().addOnCompleteListener(this) {
                val refreshedUser = auth.currentUser
                if (refreshedUser?.isEmailVerified == true) {
                    btnResendVerification.visibility = View.GONE
                    AuthPostLogin.continueToApp(this, refreshedUser.uid)
                    return@addOnCompleteListener
                }
                auth.useAppLanguage()
                refreshedUser?.sendEmailVerification()?.addOnCompleteListener(this) { sent ->
                    auth.signOut()
                    setAuthButtonsEnabled(true)
                    if (sent.isSuccessful) {
                        Toast.makeText(this, R.string.auth_email_verification_sent, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, R.string.auth_email_verification_failed, Toast.LENGTH_LONG).show()
                    }
                } ?: run {
                    auth.signOut()
                    setAuthButtonsEnabled(true)
                    Toast.makeText(this, R.string.auth_email_verification_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateNetworkUi(online: Boolean) {
        offlineState.visibility = if (online) View.GONE else View.VISIBLE
        btnLogin.visibility = if (online) View.VISIBLE else View.GONE
        btnGoogle.visibility = if (online) View.VISIBLE else View.GONE
        btnPhone.visibility = if (online) View.VISIBLE else View.GONE
        // Form navigation must remain available while connectivity is being
        // resolved, especially after Splash redirects an expired session.
        tvForgotPassword.visibility = View.VISIBLE
        tvGoToSignUp.visibility = View.VISIBLE
        if (!online) NetworkAccess.showOffline(root)
    }

    private fun setAuthButtonsEnabled(enabled: Boolean) {
        btnLogin.isEnabled = enabled
        btnGoogle.isEnabled = enabled
        btnPhone.isEnabled = enabled
        btnResendVerification.isEnabled = enabled
        listOf(btnLogin, btnGoogle, btnPhone, btnResendVerification).forEach {
            it.alpha = if (enabled) 1f else 0.55f
        }
    }


    private fun checkSecurityAndProceed(userId: String) {
        val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("users/$userId/security/password")

        dbRef.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                if (snapshot.exists() && (snapshot.value as String).isNotEmpty()) {
                    // Có password -> Sang LockScreen
                    val intent = Intent(this@LoginActivity, LockScreenActivity::class.java)
                    intent.putExtra("IS_UNLOCK_MODE", true)
                    startActivity(intent)
                    finish()
                } else {
                    // Không có password -> Sang Home
                    fetchUserInfo(userId) // Gọi hàm lấy tên User thay vì gọi finish luôn
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                // Lỗi mạng -> Vào Home luôn
                finishLoginProcess()
            }
        })
    }

    private fun fetchUserInfo(userId: String) {
        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val name = document.getString("name") ?: "User"
                    Toast.makeText(this, "System Access Granted, $name!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "System Access Granted!", Toast.LENGTH_SHORT).show()
                }
                finishLoginProcess()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Offline Access Granted", Toast.LENGTH_SHORT).show()
                finishLoginProcess()
            }
    }

    private fun finishLoginProcess() {
        resetLoginButton()
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun resetLoginButton() {
        btnLogin.text = getString(R.string.auth_login_action)
        setAuthButtonsEnabled(true)
    }
}
