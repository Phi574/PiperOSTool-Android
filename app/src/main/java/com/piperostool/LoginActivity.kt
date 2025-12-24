package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
class LoginActivity : AppCompatActivity() {

    // Khai báo View
    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var tvGoToSignUp: TextView

    // ĐÃ SỬA: Đưa biến này ra ngoài để dùng chung
    private lateinit var tvForgotPassword: TextView

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
    }

    public override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // Logic tự động đăng nhập nếu cần
        }
    }

    private fun initViews() {
        tilEmail = findViewById(R.id.tilEmailLogin)
        etEmail = tilEmail.editText as TextInputEditText

        tilPassword = findViewById(R.id.tilPasswordLogin)
        etPassword = tilPassword.editText as TextInputEditText

        btnLogin = findViewById(R.id.btnLogin)
        tvGoToSignUp = findViewById(R.id.tvGoToSignUp)

        // ĐÃ SỬA: Gán ID cho biến toàn cục
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            if (validateInput()) {
                performLogin()
            }
        }

        tvGoToSignUp.setOnClickListener {
            val intent = Intent(this, SignupActivity::class.java)
            startActivity(intent)
        }

        // ĐÃ SỬA: Gọi đúng tên Activity mới sẽ tạo ở Bước 2
        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPassword::class.java))
        }

        etEmail.setOnFocusChangeListener { _, _ -> tilEmail.error = null }
        etPassword.setOnFocusChangeListener { _, _ -> tilPassword.error = null }
    }

    private fun validateInput(): Boolean {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (email.isEmpty()) {
            tilEmail.error = "Vui lòng nhập Email"
            return false
        }

        if (password.isEmpty()) {
            tilPassword.error = "Vui lòng nhập Mật khẩu"
            return false
        }

        return true
    }

    private fun performLogin() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        btnLogin.isEnabled = false
        btnLogin.text = "Signing in..."
        btnLogin.alpha = 0.7f

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    if (userId != null) {
                        // Đăng nhập thành công -> Kiểm tra bảo mật trước khi vào Home
                        checkSecurityAndProceed(userId)
                    } else {
                        finishLoginProcess()
                    }
                } else {
                    // Đăng nhập thất bại
                    btnLogin.isEnabled = true
                    btnLogin.text = "Log In"
                    btnLogin.alpha = 1.0f
                    Toast.makeText(this, "Đăng nhập lỗi: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
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
                    finishLoginProcess()
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                // Lỗi mạng -> Vào Home luôn (hoặc xử lý khác tùy bạn)
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
                    Toast.makeText(this, "Chào mừng quay lại, $name!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                }
                finishLoginProcess()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Đăng nhập thành công (Offline mode)", Toast.LENGTH_SHORT).show()
                finishLoginProcess()
            }
    }

    private fun finishLoginProcess() {
        btnLogin.isEnabled = true
        btnLogin.text = "Log In"
        btnLogin.alpha = 1.0f
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}