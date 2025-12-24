package com.piperostool

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.Executor

class SettingFragment : Fragment() {

    private lateinit var layoutAdmin: LinearLayout
    private lateinit var switchAdmin: SwitchMaterial
    private lateinit var layoutFingerprint: LinearLayout
    private lateinit var switchFingerprint: SwitchMaterial

    // UI Password
    private lateinit var layoutPasswordToggle: LinearLayout
    private lateinit var switchPassword: SwitchMaterial

    private lateinit var btnChangeLock: LinearLayout
    private lateinit var btnPermissions: LinearLayout
    private lateinit var tvChangeLockStatus: TextView

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var componentName: ComponentName
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // Firebase reference
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val userId: String
        get() = auth.currentUser?.uid ?: "unknown_user"

    // Launcher cho Device Admin
    private val adminResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateAdminSwitchState()
    }

    // Launcher cho LockScreenActivity (nhận kết quả trả về khi thiết lập xong)
    private val lockScreenLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Khi quay lại từ màn hình cài password, cập nhật lại trạng thái switch password
        checkPasswordStatusFromFirebase()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_setting, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupBiometric()
        setupDeviceAdmin()

        // --- XỬ LÝ SWITCH VÂN TAY (Chỉ bắt sự kiện Layout) ---
        layoutFingerprint.setOnClickListener {
            // Kiểm tra trạng thái hiện tại của Switch để quyết định hành động
            val isFingerprintOn = switchFingerprint.isChecked
            if (isFingerprintOn) {
                // Đang ON -> Muốn Tắt -> Check ràng buộc
                checkSecurityConstraintForFingerprint()
            } else {
                // Đang OFF -> Muốn Bật -> Cần xác thực vân tay trước
                biometricPrompt.authenticate(promptInfo)
            }
        }
        // XÓA: switchFingerprint.setOnClickListener (Vì đã tắt cảm ứng trong XML)

        // --- XỬ LÝ CLICK VÀO HÀNG PASSWORD ---
        layoutPasswordToggle.setOnClickListener {
            showLockTypeSelectionDialog()
        }

        // --- NÚT THAY ĐỔI MÃ KHÓA ---
        btnChangeLock.setOnClickListener {
            showLockTypeSelectionDialog()
        }
    }

    private fun initViews(view: View) {
        switchAdmin = view.findViewById(R.id.switchDeviceAdmin)
        layoutAdmin = view.findViewById(R.id.layoutDeviceAdmin)

        switchFingerprint = view.findViewById(R.id.switchFingerprint)
        layoutFingerprint = view.findViewById(R.id.layoutFingerprint)

        layoutPasswordToggle = view.findViewById(R.id.layoutPasswordToggle)
        switchPassword = view.findViewById(R.id.switchPassword)

        btnChangeLock = view.findViewById(R.id.btnChangeLock)
        btnPermissions = view.findViewById(R.id.btnPermissions)
        tvChangeLockStatus = view.findViewById(R.id.tvChangeLockStatus)

        btnPermissions.setOnClickListener {
            val intent = Intent(requireContext(), PermissionManagerActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAdminSwitchState()
        updateFingerprintSwitchState()
        checkPasswordStatusFromFirebase()
    }

    // --- LOGIC KIỂM TRA FIREBASE & TRẠNG THÁI ---
    private fun checkPasswordStatusFromFirebase() {
        val myRef = database.getReference("users/$userId/security/password")
        myRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val hasPassword = snapshot.exists() && (snapshot.value as String).isNotEmpty()
                switchPassword.isChecked = hasPassword
                tvChangeLockStatus.text = if(hasPassword) "Thay đổi / Tắt mã khóa" else "Thiết lập mã khóa mới"
            }
            override fun onCancelled(error: DatabaseError) { }
        })
    }

    // --- LOGIC RÀNG BUỘC BẢO MẬT (Constraint) ---
    private fun checkSecurityConstraintForFingerprint() {
        // Người dùng muốn TẮT vân tay. Kiểm tra xem có Password không.
        val myRef = database.getReference("users/$userId/security/password")
        myRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val hasPassword = snapshot.exists() && (snapshot.value as String).isNotEmpty()

                if (hasPassword) {
                    // OK, có password dự phòng, cho phép tắt vân tay nhưng cần xác thực vân tay lần cuối
                    biometricPrompt.authenticate(promptInfo)
                } else {
                    // Không được tắt vì sẽ không còn bảo mật nào
                    Toast.makeText(requireContext(), "Không thể tắt! Phải bật ít nhất 1 phương thức bảo mật.", Toast.LENGTH_LONG).show()
                    // switchFingerprint.isChecked = true // Không cần dòng này nữa vì switch không tự nhảy
                }
            }
            override fun onCancelled(error: DatabaseError) {
                // switchFingerprint.isChecked = true
            }
        })
    }

    // --- DIALOG CHỌN LOẠI KHÓA ---
    private fun showLockTypeSelectionDialog() {
        val options = arrayOf("Tắt mã khóa", "Mã PIN 4 số", "Mã PIN 6 số", "Mật khẩu tùy chỉnh (Chữ & Số)")
        val keys = arrayOf("none", "pin_4", "pin_6", "custom")

        AlertDialog.Builder(requireContext())
            .setTitle("Chọn loại khóa bảo mật")
            .setItems(options) { dialog, which ->
                val selectedKey = keys[which]
                val intent = Intent(requireContext(), LockScreenActivity::class.java)
                intent.putExtra("LOCK_TYPE_TO_CREATE", selectedKey)
                lockScreenLauncher.launch(intent)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // --- DEVICE ADMIN ---
    private fun setupDeviceAdmin() {
        devicePolicyManager = requireActivity().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireActivity(), MyDeviceAdminReceiver::class.java)

        // XÓA: switchAdmin.setOnClickListener (Vì đã tắt cảm ứng trong XML)

        // Chỉ bắt sự kiện ở Layout
        layoutAdmin.setOnClickListener {
            // Kiểm tra trạng thái hiện tại của switch để toggle
            if (switchAdmin.isChecked) {
                deactivateDeviceAdmin()
            } else {
                activateDeviceAdmin()
            }
        }
    }

    private fun updateAdminSwitchState() {
        switchAdmin.isChecked = devicePolicyManager.isAdminActive(componentName)
    }

    private fun activateDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Kích hoạt quyền để bảo vệ thiết bị.")
        adminResultLauncher.launch(intent)
    }

    private fun deactivateDeviceAdmin() {
        devicePolicyManager.removeActiveAdmin(componentName)
        updateAdminSwitchState()
    }

    // --- BIOMETRIC (VÂN TAY) ---
    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(requireContext())
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                toggleFingerprintSetting()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(context, "Lỗi: $errString", Toast.LENGTH_SHORT).show()
                }
                updateFingerprintSwitchState() // Revert UI nếu lỗi
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(context, "Vân tay không đúng.", Toast.LENGTH_SHORT).show()
            }
        })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực vân tay")
            .setSubtitle("Quét vân tay để thay đổi cài đặt")
            .setNegativeButtonText("Hủy")
            .build()
    }

    private fun updateFingerprintSwitchState() {
        val prefs = requireActivity().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        switchFingerprint.isChecked = prefs.getBoolean("fingerprint_enabled", false)
    }

    private fun toggleFingerprintSetting() {
        val prefs = requireActivity().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val isCurrentlyEnabled = prefs.getBoolean("fingerprint_enabled", false)
        val newSetting = !isCurrentlyEnabled

        // Không cần check constraint ở đây nữa vì đã check ở đầu vào (layoutFingerprint.setOnClickListener)

        prefs.edit().putBoolean("fingerprint_enabled", newSetting).apply()
        updateFingerprintSwitchState() // Cập nhật lại UI Switch sau khi lưu xong
        Toast.makeText(context, if (newSetting) "Đã bật vân tay" else "Đã tắt vân tay", Toast.LENGTH_SHORT).show()
    }
}
