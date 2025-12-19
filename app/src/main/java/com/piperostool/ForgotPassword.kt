package com.piperostool

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class ForgotPassword : AppCompatActivity() {

    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var btnReset: Button
    private lateinit var btnBack: LinearLayout
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgotpassword)

        auth = Firebase.auth

        initViews()
        setupListeners()
    }

    private fun initViews() {
        tilEmail = findViewById(R.id.tilEmailForgot)
        etEmail = tilEmail.editText as TextInputEditText
        btnReset = findViewById(R.id.btnResetPassword)
        btnBack = findViewById(R.id.btnBackContainer)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnReset.setOnClickListener {
            val email = etEmail.text.toString().trim()

            if (email.isEmpty()) {
                tilEmail.error = "Vui lòng nhập Email"
                return@setOnClickListener
            } else {
                tilEmail.error = null
            }

            performResetPassword(email)
        }
    }

    private fun performResetPassword(email: String) {
        btnReset.isEnabled = false
        btnReset.text = "Đang kiểm tra..."

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                btnReset.isEnabled = true
                btnReset.text = "Gửi yêu cầu"

                if (task.isSuccessful) {
                    // TRƯỜNG HỢP 3: Tài khoản tồn tại và bình thường
                    showSuccessDialog(email)
                } else {
                    val exception = task.exception

                    // Xử lý các mã lỗi cụ thể
                    when (exception) {
                        // TRƯỜNG HỢP 2: Tài khoản bị vô hiệu hóa (Disabled)
                        is FirebaseAuthInvalidUserException -> {
                            val errorCode = exception.errorCode
                            if (errorCode == "ERROR_USER_DISABLED") {
                                showDisabledDialog()
                            } else {
                                // TRƯỜNG HỢP 1: Email không tồn tại trong hệ thống
                                showUserNotFoundDialog()
                            }
                        }
                        else -> {
                            // Lỗi khác (Mạng, server...)
                            // Đôi khi Firebase báo "USER_NOT_FOUND" qua message thay vì class exception
                            if (exception?.message?.contains("There is no user record") == true) {
                                showUserNotFoundDialog()
                            } else {
                                Toast.makeText(this, "Lỗi: ${exception?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
    }

    // --- CÁC HÀM HIỂN THỊ THÔNG BÁO ---

    // 1. Thông báo tài khoản bị vô hiệu hóa
    private fun showDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tài khoản bị vô hiệu hóa")
            .setMessage("Tài khoản này đã bị vô hiệu hóa do vi phạm tiêu chuẩn cộng đồng của chúng tôi.\n\nNếu có thắc mắc, vui lòng liên hệ:\nEmail: support@piperos.com\nSĐT: 1900 xxxx")
            .setPositiveButton("Đã hiểu") { dialog, _ -> dialog.dismiss() }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    // 2. Thông báo không tìm thấy tài khoản -> Gợi ý đăng ký
    private fun showUserNotFoundDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tài khoản không tồn tại")
            .setMessage("Chúng tôi nhận thấy thông tin bạn điền không có trong cơ sở dữ liệu.\n\nBạn có muốn tạo tài khoản mới luôn không?")
            .setPositiveButton("Đăng ký ngay") { _, _ ->
                // Chuyển sang màn hình Đăng ký
                val intent = Intent(this, SignupActivity::class.java)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Thử lại", null)
            .show()
    }

    // 3. Thông báo thành công -> Đã gửi mail
    private fun showSuccessDialog(email: String) {
        AlertDialog.Builder(this)
            .setTitle("Đã gửi email khôi phục")
            .setMessage("Chúng tôi đã gửi liên kết đặt lại mật khẩu tới:\n$email\n\nVui lòng kiểm tra hộp thư (cả mục Spam/Rác) để lấy lại mật khẩu.")
            .setPositiveButton("Về đăng nhập") { _, _ ->
                finish() // Đóng màn hình này để quay về Login
            }
            .setCancelable(false)
            .setIcon(android.R.drawable.ic_dialog_email)
            .show()
    }
}
