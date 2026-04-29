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

class SignupActivity : AppCompatActivity() {

    private lateinit var edtName: EditText
    private lateinit var edtEmail: EditText
    private lateinit var edtPassword: EditText
    private lateinit var edtConfirm: EditText
    private lateinit var btnRegister: Button
    private lateinit var tvBackToLogin: TextView

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Phải vẽ giao diện ra trước
        setContentView(R.layout.activity_signup)

        // 2. Ẩn Status Bar tạo hiệu ứng tràn viền
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        // Khởi tạo Firebase
        auth = Firebase.auth
        db = Firebase.firestore

        initViews()
        setupListeners()
    }

    private fun initViews() {
        edtName = findViewById(R.id.edtSignupName)
        edtEmail = findViewById(R.id.edtSignupEmail)
        edtPassword = findViewById(R.id.edtSignupPassword)
        edtConfirm = findViewById(R.id.edtSignupConfirm)
        btnRegister = findViewById(R.id.btnRegister)
        tvBackToLogin = findViewById(R.id.tvBackToLogin)
    }

    private fun setupListeners() {
        btnRegister.setOnClickListener {
            if (validateInput()) {
                performSignUp()
            }
        }

        tvBackToLogin.setOnClickListener {
            finish() // Tắt màn hình này sẽ tự lùi về Login
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Xóa lỗi khi gõ
        edtName.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) edtName.error = null }
        edtEmail.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) edtEmail.error = null }
        edtPassword.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) edtPassword.error = null }
        edtConfirm.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) edtConfirm.error = null }
    }

    private fun validateInput(): Boolean {
        val name = edtName.text.toString().trim()
        val email = edtEmail.text.toString().trim()
        val pass = edtPassword.text.toString().trim()
        val confirm = edtConfirm.text.toString().trim()

        if (name.isEmpty()) {
            edtName.error = "Vui lòng nhập Tên/Bí danh"
            return false
        }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            edtEmail.error = "Email không hợp lệ"
            return false
        }
        if (pass.length < 6) {
            edtPassword.error = "Mật khẩu phải từ 6 ký tự"
            return false
        }
        if (pass != confirm) {
            edtConfirm.error = "Mật khẩu xác nhận không khớp"
            return false
        }
        return true
    }

    private fun performSignUp() {
        val email = edtEmail.text.toString().trim()
        val password = edtPassword.text.toString().trim()
        val name = edtName.text.toString().trim()

        btnRegister.isEnabled = false
        btnRegister.text = "LOADING..."
        btnRegister.alpha = 0.5f

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    if (userId != null) {
                        saveUserToFirestore(userId, name, email)
                    } else {
                        Toast.makeText(this, "ID Generated but UID is null!", Toast.LENGTH_SHORT).show()
                        resetButton()
                    }
                } else {
                    Toast.makeText(this, "Lỗi tạo ID: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    resetButton()
                }
            }
    }

    private fun saveUserToFirestore(userId: String, name: String, email: String) {
        val userMap = hashMapOf(
            "name" to name,
            "email" to email,
            "role" to "User",
            "createdAt" to System.currentTimeMillis()
        )

        db.collection("users").document(userId)
            .set(userMap)
            .addOnSuccessListener {
                Toast.makeText(this, "Identity Created Successfully!", Toast.LENGTH_SHORT).show()
                // Chuyển sang Home
                val intent = Intent(this, HomeActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Lỗi lưu dữ liệu: ${e.message}", Toast.LENGTH_SHORT).show()
                resetButton()
            }
    }

    private fun resetButton() {
        btnRegister.isEnabled = true
        btnRegister.text = "SIGNUP"
        btnRegister.alpha = 1.0f
    }
}