package com.piperostool

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.LinearLayout
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
import com.hbb20.CountryCodePicker
import java.util.Calendar

class SignupActivity : AppCompatActivity() {

    // View
    private lateinit var btnBack: LinearLayout

    // Họ, Đệm, Tên
    private lateinit var etLastName: TextInputEditText
    private lateinit var etMiddleName: TextInputEditText
    private lateinit var etFirstName: TextInputEditText

    // Thông tin khác
    private lateinit var etUsername: TextInputEditText
    private lateinit var etDob: TextInputEditText
    private lateinit var ccp: CountryCodePicker
    private lateinit var etPhone: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var tvPasswordStrength: TextView

    private lateinit var btnGetStarted: Button
    private lateinit var tvLogin: TextView

    // Layout Wrappers (để báo lỗi)
    private lateinit var tilDob: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilConfirmPassword: TextInputLayout

    // Firebase
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        // Khởi tạo Firebase
        auth = Firebase.auth
        db = Firebase.firestore
        initViews()
        setupListeners()
        setupPasswordStrengthChecker()
        setupDateFormatter()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBackContainer)

        etLastName = findViewById<TextInputLayout>(R.id.tilLastName).editText as TextInputEditText
        etMiddleName = findViewById<TextInputLayout>(R.id.tilMiddleName).editText as TextInputEditText
        etFirstName = findViewById<TextInputLayout>(R.id.tilFirstName).editText as TextInputEditText

        etUsername = findViewById<TextInputLayout>(R.id.tilUsername).editText as TextInputEditText

        tilDob = findViewById(R.id.tilDob)
        etDob = tilDob.editText as TextInputEditText

        ccp = findViewById(R.id.ccp)
        etPhone = findViewById<TextInputLayout>(R.id.tilPhone).editText as TextInputEditText

        etEmail = findViewById<TextInputLayout>(R.id.tilEmail).editText as TextInputEditText

        tilPassword = findViewById(R.id.tilPassword)
        etPassword = tilPassword.editText as TextInputEditText
        tvPasswordStrength = findViewById(R.id.tvPasswordStrength)

        tilConfirmPassword = findViewById(R.id.tilConfirmPassword)
        etConfirmPassword = tilConfirmPassword.editText as TextInputEditText

        btnGetStarted = findViewById(R.id.btnGetStarted)
        tvLogin = findViewById(R.id.tvLogin)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        tvLogin.setOnClickListener {
            // Đảm bảo bạn có LoginActivity rồi nhé
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        btnGetStarted.setOnClickListener {
            if (validateInput()) {
                performSignUp()
            }
        }
    }

    private fun setupPasswordStrengthChecker() {
        etPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val password = s.toString()
                checkStrength(password)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun checkStrength(password: String) {
        if (password.isEmpty()) {
            tvPasswordStrength.text = "Độ mạnh mật khẩu: Chưa nhập"
            tvPasswordStrength.setTextColor(Color.GRAY)
            return
        }

        var score = 0
        if (password.length >= 8) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { it.isUpperCase() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        when {
            password.length < 6 -> {
                tvPasswordStrength.text = "Độ mạnh: YẾU (Quá ngắn)"
                tvPasswordStrength.setTextColor(Color.RED)
            }
            score < 2 -> {
                tvPasswordStrength.text = "Độ mạnh: YẾU"
                tvPasswordStrength.setTextColor(Color.RED)
            }
            score == 2 || score == 3 -> {
                tvPasswordStrength.text = "Độ mạnh: VỪA"
                tvPasswordStrength.setTextColor(Color.parseColor("#FF9800"))
            }
            score == 4 -> {
                tvPasswordStrength.text = "Độ mạnh: MẠNH"
                tvPasswordStrength.setTextColor(Color.parseColor("#4CAF50"))
            }
        }
    }

    private fun setupDateFormatter() {
        etDob.addTextChangedListener(object : TextWatcher {
            private var current = ""
            private val cal = Calendar.getInstance()

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.toString() != current) {

                    var clean = s.toString().replace("[^\\d]".toRegex(), "")


                    if (clean.length > 8) {
                        clean = clean.substring(0, 8)
                    }

                    val cleanC = current.replace("[^\\d]".toRegex(), "")

                    val cl = clean.length
                    var sel = cl
                    var index = 2
                    while (index <= cl && index < 6) {
                        index += 2
                    }
                    if (clean == cleanC) sel--


                    if (clean.length < 4) {

                        if (clean.length >= 2) {
                            clean = clean.substring(0, 2) + "/" + clean.substring(2)
                        }
                    } else {

                        clean = clean.substring(0, 2) + "/" + clean.substring(2, 4) + "/" + clean.substring(4)
                    }

                    if (clean.replace("/", "").length == 8) {
                    }

                    sel = if (sel < 0) 0 else sel
                    current = clean
                    etDob.setText(current)

                    etDob.setSelection(if (current.length < 10) current.length else 10)
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }


    private fun validateInput(): Boolean {
        tilDob.error = null
        tilPassword.error = null
        tilConfirmPassword.error = null

        val dob = etDob.text.toString()
        val password = etPassword.text.toString()
        val confirmPass = etConfirmPassword.text.toString()

        if (dob.length != 10) { // Check độ dài 10 ký tự (DD/MM/YYYY)
            tilDob.error = "Vui lòng nhập đầy đủ ngày sinh (DD/MM/YYYY)"
            return false
        }

        if (password != confirmPass) {
            tilConfirmPassword.error = "Mật khẩu nhập lại không khớp!"
            return false
        }

        if (etEmail.text.toString().isEmpty()) {
            etEmail.error = "Vui lòng nhập Email"
            return false
        }

        if (etFirstName.text.toString().isEmpty()) {
            etFirstName.error = "Vui lòng nhập tên"
            return false
        }

        return true
    }

    private fun performSignUp() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        val lastName = etLastName.text.toString().trim()
        val middleName = etMiddleName.text.toString().trim()
        val firstName = etFirstName.text.toString().trim()
        val fullName = "$lastName $middleName $firstName".trim()

        val username = etUsername.text.toString().trim()
        val dob = etDob.text.toString().trim()

        // Lấy số điện thoại đầy đủ từ CCP
        ccp.registerCarrierNumberEditText(etPhone)
        val fullPhoneNumber = ccp.fullNumberWithPlus

        btnGetStarted.isEnabled = false
        btnGetStarted.text = "Creating..."

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    if (userId != null) {
                        val userMap = hashMapOf(
                            "uid" to userId,
                            "last_name" to lastName,
                            "middle_name" to middleName,
                            "first_name" to firstName,
                            "full_name" to fullName,
                            "username" to username,
                            "dob" to dob,
                            "phone" to fullPhoneNumber,
                            "email" to email,
                            "password" to password,
                            "role" to "user",
                            "created_at" to System.currentTimeMillis()
                        )

                        db.collection("users").document(userId)
                            .set(userMap)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Đăng ký thành công!", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Lỗi lưu DB: ${it.message}", Toast.LENGTH_SHORT).show()
                                btnGetStarted.isEnabled = true
                                btnGetStarted.text = "Get Started"
                            }
                    }
                } else {
                    Toast.makeText(this, "Lỗi Auth: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    btnGetStarted.isEnabled = true
                    btnGetStarted.text = "Get Started"
                }
            }
    }
}
