package com.piperostool

import android.content.Intent
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {

    // SỬA: Đổi sang EditText thuần để khớp với giao diện LiquidGlass (Không dùng TextInputLayout nữa)
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvGoToSignUp: TextView
    private lateinit var tvForgotPassword: TextView

    // Khai báo Firebase
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.login_screen)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

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
        // SỬA: Map ID chuẩn theo file login_screen.xml mới
        etEmail = findViewById(R.id.edtUsername)
        etPassword = findViewById(R.id.edtPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvGoToSignUp = findViewById(R.id.tvSignUp) // Cập nhật đúng ID nút Đăng ký
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
        btnLogin.isEnabled = false
        btnLogin.text = "LOADING..."
        btnLogin.alpha = 0.5f

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    if (userId != null) {

                        checkSecurityAndProceed(userId)
                    } else {
                        finishLoginProcess()
                    }
                } else {
                    btnLogin.isEnabled = true
                    btnLogin.text = "LOGIN"
                    btnLogin.alpha = 1.0f
                    Toast.makeText(this, "Kiểm tra lại thông tin: ${task.exception?.message}", Toast.LENGTH_LONG).show()
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
        btnLogin.isEnabled = true
        btnLogin.text = "INITIALIZE"
        btnLogin.alpha = 1.0f
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}