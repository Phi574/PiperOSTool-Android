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
import android.view.ViewGroup
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
    private lateinit var root: View
    private lateinit var offlineState: View

    // Mode
    private var isUnlockAppMode = false
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
        const val KEY_CACHED_PASS = "cached_lock_password"
        const val KEY_CACHED_TYPE = "cached_lock_type"
        const val KEY_CACHE_READY = "cached_lock_ready"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        // 1. ÁP DỤNG HÌNH NỀN TÙY CHỈNH
        applyCustomBackground()

        isUnlockAppMode = intent.getBooleanExtra("IS_UNLOCK_MODE", false)
        targetType = intent.getStringExtra("LOCK_TYPE_TO_CREATE")

        initViews()
        setupBiometric()

        // Check ban status immediately
        if (checkBanStatus()) {
            // Nếu bị ban, vẫn chạy đếm ngược, nhưng không load dữ liệu pass để nhập
        } else {
            setLoadingState()
        }

        NetworkAccess.observe(this, this) { online ->
            offlineState.visibility = if (online) View.GONE else View.VISIBLE
            if (!online) NetworkAccess.showOffline(root)
            if (!checkBanStatus() && currentMode == MODE_LOADING) {
                if (online) checkFirebaseForExistingPass() else loadCachedSecurity()
            }
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

    // ==========================================================
    // HÀM ÁP DỤNG HÌNH NỀN TÙY CHỈNH
    // ==========================================================
    private fun applyCustomBackground() {
        val prefs = AccountDataScope.preferences(this, "PiperPrefs")
        val hasCustomBg = prefs.getBoolean("has_custom_bg", false)

        // Lấy trực tiếp lớp gốc ngoài cùng của màn hình (chính là thẻ ConstraintLayout)
        val bgView = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        if (hasCustomBg) {
            try {
                // Đọc file ảnh custom_bg.jpg từ bộ nhớ kín của app
                val file = AccountDataScope.file(this, "appearance", "custom_bg.jpg")
                if (file.exists()) {
                    val drawable = android.graphics.drawable.Drawable.createFromPath(file.absolutePath)
                    bgView.background = drawable
                } else {
                    bgView.setBackgroundResource(R.drawable.backgroud)
                }
            } catch (e: Exception) {
                bgView.setBackgroundResource(R.drawable.backgroud)
            }
        } else {
            bgView.setBackgroundResource(R.drawable.backgroud)
        }
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvSubTitle = findViewById(R.id.tvSubTitle)
        etPassword = findViewById(R.id.etPassword)
        tilPassword = findViewById(R.id.tilPassword)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnLogout = findViewById(R.id.btnLogout)

        layoutPinInput = findViewById(R.id.layoutPinInput)
        llDotsContainer = findViewById(R.id.llDotsContainer)
        etPinHidden = findViewById(R.id.etPinHidden)
        layoutFingerprint = findViewById(R.id.layoutFingerprint)
        root = findViewById(R.id.lockRoot)
        offlineState = findViewById(R.id.lockOfflineState)
        findViewById<TextView>(R.id.lockVersion).text =
            getString(R.string.auth_version, AppVersion.name(this))

        if (!isUnlockAppMode) {
            btnLogout.visibility = View.GONE
        }
    }

    // --- LOGIC ĐĂNG XUẤT ---
    private fun performLogout() {
        auth.signOut()
        Toast.makeText(this, "Đã đăng xuất", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // --- LOGIC CẤM NHẬP (BAN) ---
    private fun checkBanStatus(): Boolean {
        val prefs = AccountDataScope.preferences(this, PREFS_NAME)
        banEndTime = prefs.getLong(KEY_BAN_TIME, 0)
        failedAttempts = prefs.getInt(KEY_FAILED_COUNT, 0)

        val currentTime = System.currentTimeMillis()
        if (banEndTime > currentTime) {
            startBanCountdown(banEndTime - currentTime)
            return true
        } else {
            if (banEndTime != 0L) {
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
                tvSubTitle.setTextColor(ContextCompat.getColor(this@LockScreenActivity, R.color.white))
                setInputsEnabled(true)

                val prefsApp = AccountDataScope.preferences(this@LockScreenActivity, "PiperPrefs")
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
        val prefs = AccountDataScope.preferences(this, PREFS_NAME)
        prefs.edit().putInt(KEY_FAILED_COUNT, failedAttempts).apply()

        if (failedAttempts >= 5) {
            val banTime = System.currentTimeMillis() + 60000
            prefs.edit().putLong(KEY_BAN_TIME, banTime).apply()
            checkBanStatus()
        } else {
            val remaining = 5 - failedAttempts
            Toast.makeText(this, "Sai mã khóa! Còn $remaining lần thử.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetFailedAttempts() {
        failedAttempts = 0
        banEndTime = 0
        val prefs = AccountDataScope.preferences(this, PREFS_NAME)
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
                cacheSecurity(currentSavedPass, currentSavedType)
                decideFlow()
            }
            override fun onCancelled(error: DatabaseError) {
                loadCachedSecurity()
            }
        })
    }

    private fun cacheSecurity(password: String?, type: String?) {
        AccountDataScope.preferences(this, PREFS_NAME)
            .edit()
            .putBoolean(KEY_CACHE_READY, true)
            .apply {
                if (password.isNullOrEmpty()) {
                    remove(KEY_CACHED_PASS)
                    remove(KEY_CACHED_TYPE)
                } else {
                    putString(KEY_CACHED_PASS, password)
                    putString(KEY_CACHED_TYPE, type ?: "custom")
                }
            }
            .apply()
    }

    private fun loadCachedSecurity() {
        val prefs = AccountDataScope.preferences(this, PREFS_NAME)
        if (!prefs.getBoolean(KEY_CACHE_READY, false)) {
            tvTitle.text = getString(R.string.lock_connection_required)
            tvSubTitle.text = getString(R.string.lock_no_offline_data)
            setInputsEnabled(false)
            tilPassword.visibility = View.GONE
            layoutPinInput.visibility = View.GONE
            return
        }
        currentSavedPass = prefs.getString(KEY_CACHED_PASS, null)
        currentSavedType = prefs.getString(KEY_CACHED_TYPE, null)
        decideFlow()
    }

    private fun decideFlow() {
        if (checkBanStatus()) return

        if (isUnlockAppMode) {
            if (currentSavedPass.isNullOrEmpty()) {
                startHomeActivity()
            } else {
                startVerifyOldFlow()
                tvTitle.text = "Piper OS Locked"
                tvSubTitle.text = "Nhập mã khóa để truy cập"
                btnConfirm.text = "Mở khóa"
            }
        } else {
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
            val prefs = AccountDataScope.preferences(this, "PiperPrefs")
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
        if (!NetworkAccess.isOnline(this)) {
            NetworkAccess.showOffline(root)
            return
        }
        val userMap = mapOf(
            "password" to password,
            "type" to targetType
        )
        database.getReference("users/$userId/security").updateChildren(userMap)
            .addOnSuccessListener {
                cacheSecurity(password, targetType)
                Toast.makeText(this, "Thành công!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Lỗi: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deletePasswordOnFirebase() {
        if (!NetworkAccess.isOnline(this)) {
            NetworkAccess.showOffline(root)
            return
        }
        val prefs = AccountDataScope.preferences(this, "PiperPrefs")
        val isFingerprintEnabled = prefs.getBoolean("fingerprint_enabled", false)

        if (!isFingerprintEnabled) {
            Toast.makeText(this, "Bạn phải bật Vân tay trước thì mới được tắt Mã khóa!", Toast.LENGTH_LONG).show()
            return
        }

        database.getReference("users/$userId/security").removeValue()
            .addOnSuccessListener {
                cacheSecurity(null, null)
                Toast.makeText(this, "Đã tắt mã khóa bảo mật!", Toast.LENGTH_SHORT).show()
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
