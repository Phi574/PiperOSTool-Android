package com.piperostool

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.Executor

class LockScreenActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubTitle: TextView
    private lateinit var etPassword: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var btnConfirm: Button
    private lateinit var btnLogout: LinearLayout // Nút đăng xuất

    private lateinit var layoutPinInput: RelativeLayout
    private lateinit var llDotsContainer: LinearLayout
    private lateinit var etPinHidden: EditText
    private lateinit var layoutFingerprint: LinearLayout
    // private lateinit var tvErrorMsg: TextView // Tạm thời dùng tvSubTitle

    // Mode
    private var isUnlockAppMode = false // True: Mở khóa app, False: Vào setup trong Setting
    private var currentMode = MODE_LOADING
    private var targetType: String? = null
    private var firstPassInput: String = ""

    private var currentSavedPass: String? = null
    private var currentSavedType: String? = null

    // Firebase
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val userId: String
        get() = auth.currentUser?.uid ?: "unknown_user"

    // Biometric
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // Lockout Logic
    private var failedAttempts = 0
    private var banEndTime: Long = 0
    private var countDownTimer: CountDownTimer? = null

    companion object {
        const val MODE_LOADING = -1
        const val MODE_VERIFY_OLD = 0
        const val MODE_CREATE_STEP_1 = 1
        const val MODE_CREATE_STEP_2 = 2

        const val PREFS_NAME = "LockScreenPrefs"
        const val KEY_BAN_TIME = "ban_end_time"
        const val KEY_FAILED_COUNT = "failed_count"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        isUnlockAppMode = intent.getBooleanExtra("IS_UNLOCK_MODE", false)
        targetType = intent.getStringExtra("LOCK_TYPE_TO_CREATE")

        initViews()
        setupBiometric()

        // Check ban status immediately
        if (checkBanStatus()) {
            // Nếu bị ban, vẫn chạy đếm ngược, nhưng không load dữ liệu pass để nhập
        } else {
            checkFirebaseForExistingPass()
        }

        btnConfirm.setOnClickListener { handleConfirmClick() }

        layoutFingerprint.setOnClickListener {
            if (!checkBanStatus()) {
                biometricPrompt.authenticate(promptInfo)
            }
        }

        layoutPinInput.setOnClickListener {
            if (!checkBanStatus()) {
                etPinHidden.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etPinHidden, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // --- XỬ LÝ NÚT ĐĂNG XUẤT ---
        btnLogout.setOnClickListener {
            performLogout()
        }

        etPinHidden.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val length = s?.length ?: 0
                updateDotsView(length)

                if (!checkBanStatus()) {
                    val requiredLen = when {
                        currentMode == MODE_VERIFY_OLD -> {
                            if (currentSavedType == "pin_4") 4
                            else if (currentSavedType == "pin_6") 6
                            else -1 // Custom không tự submit
                        }
                        // Nếu đang tạo mới
                        targetType == "pin_4" -> 4
                        targetType == "pin_6" -> 6
                        else -> -1
                    }

                    if (requiredLen != -1 && length == requiredLen) {
                        // Đủ độ dài -> Tự động xác nhận
                        handleConfirmClick()
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvSubTitle = findViewById(R.id.tvSubTitle)
        etPassword = findViewById(R.id.etPassword)
        tilPassword = findViewById(R.id.tilPassword)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnLogout = findViewById(R.id.btnLogout) // Ánh xạ nút logout

        layoutPinInput = findViewById(R.id.layoutPinInput)
        llDotsContainer = findViewById(R.id.llDotsContainer)
        etPinHidden = findViewById(R.id.etPinHidden)
        layoutFingerprint = findViewById(R.id.layoutFingerprint)

        // Chỉ hiện nút Đăng xuất khi ở chế độ Mở khóa App
        // Nếu đang ở trong Settings (đổi pass) thì ẩn đi
        if (!isUnlockAppMode) {
            btnLogout.visibility = View.GONE
        }
    }

    // --- LOGIC ĐĂNG XUẤT ---
    private fun performLogout() {
        auth.signOut()
        Toast.makeText(this, "Đã đăng xuất", Toast.LENGTH_SHORT).show()

        // QUAN TRỌNG: Không xóa SharedPreferences liên quan đến ban (KEY_BAN_TIME)
        // để đảm bảo nếu người dùng bị ban, thoát ra vào lại vẫn bị ban.

        // Chuyển về màn hình Login
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // --- LOGIC CẤM NHẬP (BAN) ---
    private fun checkBanStatus(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        banEndTime = prefs.getLong(KEY_BAN_TIME, 0)
        failedAttempts = prefs.getInt(KEY_FAILED_COUNT, 0)

        val currentTime = System.currentTimeMillis()
        if (banEndTime > currentTime) {
            // Vẫn đang trong thời gian cấm
            startBanCountdown(banEndTime - currentTime)
            return true
        } else {
            // Hết giờ cấm hoặc chưa cấm
            if (banEndTime != 0L) {
                // Reset nếu vừa hết hạn
                resetFailedAttempts()
            }
            return false
        }
    }

    private fun startBanCountdown(millisInFuture: Long) {
        setInputsEnabled(false)
        layoutFingerprint.visibility = View.GONE

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(millisInFuture, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                tvSubTitle.text = "Bạn đã nhập sai quá 5 lần.\nVui lòng thử lại sau ${seconds}s"
                tvSubTitle.setTextColor(ContextCompat.getColor(this@LockScreenActivity, android.R.color.holo_red_light))
            }

            override fun onFinish() {
                resetFailedAttempts()
                tvSubTitle.text = "Mời bạn nhập lại mã khóa"
                tvSubTitle.setTextColor(ContextCompat.getColor(this@LockScreenActivity, R.color.white)) // Màu gốc
                setInputsEnabled(true)

                val prefsApp = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
                if (prefsApp.getBoolean("fingerprint_enabled", false)) {
                    layoutFingerprint.visibility = View.VISIBLE
                    biometricPrompt.authenticate(promptInfo)
                }

                if (layoutPinInput.visibility == View.VISIBLE) {
                    etPinHidden.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(etPinHidden, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }.start()
    }

    private fun registerFailedAttempt() {
        failedAttempts++
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_FAILED_COUNT, failedAttempts).apply()

        if (failedAttempts >= 5) {
            // Ban 60s
            val banTime = System.currentTimeMillis() + 60000
            prefs.edit().putLong(KEY_BAN_TIME, banTime).apply()
            checkBanStatus() // Kích hoạt UI cấm
        } else {
            val remaining = 5 - failedAttempts
            Toast.makeText(this, "Sai mã khóa! Còn $remaining lần thử.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetFailedAttempts() {
        failedAttempts = 0
        banEndTime = 0
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_FAILED_COUNT).remove(KEY_BAN_TIME).apply()
    }

    private fun setInputsEnabled(enabled: Boolean) {
        etPassword.isEnabled = enabled
        etPinHidden.isEnabled = enabled
        btnConfirm.isEnabled = enabled
        layoutPinInput.isEnabled = enabled
    }

    // --- CHECK FIREBASE ---
    private fun checkFirebaseForExistingPass() {
        setLoadingState()
        val myRef = database.getReference("users/$userId/security")

        myRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.hasChild("password")) {
                    currentSavedPass = snapshot.child("password").value.toString()
                    currentSavedType = snapshot.child("type").value.toString()
                } else {
                    currentSavedPass = null
                    currentSavedType = null
                }
                decideFlow()
            }
            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        })
    }

    private fun decideFlow() {
        if (checkBanStatus()) return

        if (isUnlockAppMode) {
            // Chế độ mở khóa App
            if (currentSavedPass.isNullOrEmpty()) {
                // Không có pass thì vào thẳng home
                startHomeActivity()
            } else {
                startVerifyOldFlow()
                tvTitle.text = "Piper OS Locked"
                tvSubTitle.text = "Nhập mã khóa để truy cập"
                btnConfirm.text = "Mở khóa"
            }
        } else {
            // Chế độ Setting (Tạo/Đổi/Tắt)
            if (currentSavedPass.isNullOrEmpty()) {
                if (targetType == "none") {
                    Toast.makeText(this, "Chưa thiết lập mã khóa nào!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    startCreateFlowStep1()
                }
            } else {
                startVerifyOldFlow()
            }
        }
    }

    private fun setLoadingState() {
        currentMode = MODE_LOADING
        tvTitle.text = "Đang kiểm tra..."
        tvSubTitle.text = "..."
        tilPassword.visibility = View.GONE
        layoutPinInput.visibility = View.GONE
        btnConfirm.isEnabled = false
    }

    private fun updateUiForType(type: String?) {
        etPassword.setText("")
        etPinHidden.setText("")

        if (type == "pin_4" || type == "pin_6") {
            tilPassword.visibility = View.GONE
            layoutPinInput.visibility = View.VISIBLE
            val len = if (type == "pin_4") 4 else 6
            setupDots(len)
            etPinHidden.filters = arrayOf(InputFilter.LengthFilter(len))
            etPinHidden.requestFocus()
        } else {
            tilPassword.visibility = View.VISIBLE
            layoutPinInput.visibility = View.GONE
            etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }

    private fun setupDots(count: Int) {
        llDotsContainer.removeAllViews()
        val params = LinearLayout.LayoutParams(60, 60)
        params.setMargins(20, 0, 20, 0)
        for (i in 0 until count) {
            val imageView = ImageView(this)
            imageView.setImageResource(R.drawable.bg_dot_off)
            imageView.layoutParams = params
            llDotsContainer.addView(imageView)
        }
    }

    private fun updateDotsView(length: Int) {
        val count = llDotsContainer.childCount
        for (i in 0 until count) {
            val imageView = llDotsContainer.getChildAt(i) as ImageView
            if (i < length) {
                imageView.setImageResource(R.drawable.bg_dot_on)
            } else {
                imageView.setImageResource(R.drawable.bg_dot_off)
            }
        }
    }

    private fun startVerifyOldFlow() {
        currentMode = MODE_VERIFY_OLD
        btnConfirm.isEnabled = true

        if (!isUnlockAppMode) {
            if (targetType == "none") {
                tvTitle.text = "Tắt mã khóa"
                tvSubTitle.text = "Xác thực để xóa mã khóa hiện tại"
                btnConfirm.text = "Xác nhận xóa"
            } else {
                tvTitle.text = "Xác thực bảo mật"
                tvSubTitle.text = "Nhập mật khẩu hiện tại"
                btnConfirm.text = "Tiếp tục"
            }
        }

        updateUiForType(currentSavedType ?: "custom")

        if (!checkBanStatus()) {
            val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("fingerprint_enabled", false)) {
                layoutFingerprint.visibility = View.VISIBLE
                biometricPrompt.authenticate(promptInfo)
            } else {
                layoutFingerprint.visibility = View.GONE
            }
        }
    }

    private fun startCreateFlowStep1() {
        currentMode = MODE_CREATE_STEP_1
        btnConfirm.isEnabled = true
        firstPassInput = ""
        layoutFingerprint.visibility = View.GONE

        tvTitle.text = "Thiết lập mã khóa"
        when (targetType) {
            "pin_4" -> {
                tvSubTitle.text = "Nhập mã PIN 4 số"
                updateUiForType("pin_4")
            }
            "pin_6" -> {
                tvSubTitle.text = "Nhập mã PIN 6 số"
                updateUiForType("pin_6")
            }
            else -> {
                tvSubTitle.text = "Nhập mật khẩu tùy chỉnh"
                updateUiForType("custom")
            }
        }
    }

    private fun startCreateFlowStep2() {
        currentMode = MODE_CREATE_STEP_2
        etPassword.setText("")
        etPinHidden.setText("")
        updateDotsView(0)
        tvTitle.text = "Xác nhận lại"
        tvSubTitle.text = "Nhập lại mã vừa tạo để xác nhận"
    }

    private fun handleConfirmClick() {
        if (checkBanStatus()) return

        val isPinMode = (layoutPinInput.visibility == View.VISIBLE)
        val input = if (isPinMode) etPinHidden.text.toString() else etPassword.text.toString()

        if (input.isEmpty()) return

        when (currentMode) {
            MODE_VERIFY_OLD -> {
                if (input == currentSavedPass) {
                    resetFailedAttempts()
                    if (isUnlockAppMode) {
                        startHomeActivity()
                    } else {
                        if (targetType == "none") {
                            deletePasswordOnFirebase()
                        } else {
                            startCreateFlowStep1()
                        }
                    }
                } else {
                    if (isPinMode) etPinHidden.setText("") else etPassword.setText("")
                    registerFailedAttempt()
                }
            }
            MODE_CREATE_STEP_1 -> {
                if (targetType == "pin_4" && input.length != 4) {
                    Toast.makeText(this, "Chưa đủ 4 số", Toast.LENGTH_SHORT).show()
                    return
                }
                if (targetType == "pin_6" && input.length != 6) {
                    Toast.makeText(this, "Chưa đủ 6 số", Toast.LENGTH_SHORT).show()
                    return
                }
                firstPassInput = input
                startCreateFlowStep2()
            }
            MODE_CREATE_STEP_2 -> {
                if (input == firstPassInput) {
                    savePasswordToFirebase(input)
                } else {
                    Toast.makeText(this, "Không khớp! Nhập lại từ đầu.", Toast.LENGTH_LONG).show()
                    startCreateFlowStep1()
                }
            }
        }
    }

    private fun savePasswordToFirebase(password: String) {
        val userMap = mapOf(
            "password" to password,
            "type" to targetType
        )
        database.getReference("users/$userId/security").updateChildren(userMap)
            .addOnSuccessListener {
                Toast.makeText(this, "Thành công!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Lỗi: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deletePasswordOnFirebase() {
        val prefs = getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val isFingerprintEnabled = prefs.getBoolean("fingerprint_enabled", false)

        if (!isFingerprintEnabled) {
            Toast.makeText(this, "Bạn phải bật Vân tay trước thì mới được tắt Mã khóa!", Toast.LENGTH_LONG).show()
            return
        }

        database.getReference("users/$userId/security").removeValue()
            .addOnSuccessListener {
                Toast.makeText(this, "Đã tắt mã khóa bảo mật!", Toast.LENGTH_SHORT).show()

                // Tắt luôn cờ vân tay khi xóa pass
                prefs.edit().putBoolean("fingerprint_enabled", false).apply()

                setResult(RESULT_OK)
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Lỗi khi xóa: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun startHomeActivity() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)

                if (checkBanStatus()) return

                resetFailedAttempts()
                Toast.makeText(this@LockScreenActivity, "Xác thực thành công", Toast.LENGTH_SHORT).show()

                if (isUnlockAppMode) {
                    startHomeActivity()
                } else {
                    if (targetType == "none") {
                        deletePasswordOnFirebase()
                    } else {
                        startCreateFlowStep1()
                    }
                }
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                registerFailedAttempt()
            }
        })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực")
            .setSubtitle(if(isUnlockAppMode) "Mở khóa Piper OS Tool" else "Xác nhận bảo mật")
            .setNegativeButtonText("Sử dụng mật khẩu")
            .build()
    }
}
