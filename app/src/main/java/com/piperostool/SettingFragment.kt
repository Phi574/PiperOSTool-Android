package com.piperostool

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executor

class SettingFragment : Fragment() {

    // --- Device Admin Components ---
    private lateinit var switchAdmin: SwitchMaterial
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var btnPermissions: LinearLayout
    private lateinit var componentName: ComponentName
    private val adminResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Sau khi người dùng tương tác với màn hình quyền, kiểm tra lại trạng thái
        updateAdminSwitchState()
    }

    // --- Biometric Components ---
    private lateinit var switchFingerprint: SwitchMaterial
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    // ----------------------------

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_setting, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- Cài đặt cho Device Admin ---
        switchAdmin = view.findViewById(R.id.switchDeviceAdmin)
        devicePolicyManager = requireActivity().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireActivity(), MyDeviceAdminReceiver::class.java)
        switchAdmin.setOnCheckedChangeListener { _, isChecked ->
            if (switchAdmin.isPressed) {
                if (isChecked) activateDeviceAdmin() else deactivateDeviceAdmin()
            }
            btnPermissions = view.findViewById(R.id.btnPermissions)
            btnPermissions.setOnClickListener {
                val intent = Intent(requireContext(), PermissionManagerActivity::class.java)
                startActivity(intent)
            }
        }
        // -----------------------------

        // --- Cài đặt cho Vân tay ---
        switchFingerprint = view.findViewById(R.id.switchFingerprint)
        setupBiometric()

        switchFingerprint.setOnCheckedChangeListener { _, _ ->
            // Chỉ xử lý khi người dùng tự tay bấm vào, để tránh vòng lặp
            if (switchFingerprint.isPressed) {
                // Hiển thị hộp thoại xác thực trước khi thay đổi cài đặt
                biometricPrompt.authenticate(promptInfo)
            }
        }
        // -----------------------------
    }

    override fun onResume() {
        super.onResume()
        // Luôn cập nhật trạng thái của các Switch mỗi khi Fragment được hiển thị
        updateAdminSwitchState()
        updateFingerprintSwitchState()
    }

    // --- CÁC HÀM XỬ LÝ VÂN TAY ---
    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(requireContext())

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                // Được gọi khi có lỗi không thể phục hồi
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Nếu lỗi không phải do người dùng bấm "Hủy", thì thông báo
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(context, "Lỗi xác thực: $errString", Toast.LENGTH_SHORT).show()
                    }
                    // Dù lỗi gì, cũng đặt lại trạng thái Switch về như cũ
                    updateFingerprintSwitchState()
                }

                // Được gọi khi xác thực thành công
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // Thay đổi và lưu cài đặt
                    toggleFingerprintSetting()
                }

                // Được gọi khi vân tay không khớp
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(context, "Vân tay không khớp. Thử lại.", Toast.LENGTH_SHORT).show()
                }
            })

        // Cấu hình nội dung cho hộp thoại xác thực
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực để thay đổi cài đặt")
            .setSubtitle("Sử dụng vân tay của bạn để tiếp tục")
            .setNegativeButtonText("Hủy")
            .build()
    }

    private fun updateFingerprintSwitchState() {
        // Đọc cài đặt đã lưu và cập nhật giao diện Switch
        val prefs = requireActivity().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        switchFingerprint.isChecked = prefs.getBoolean("fingerprint_enabled", false)
    }

    private fun toggleFingerprintSetting() {
        // Hàm này được gọi KHI ĐÃ XÁC THỰC THÀNH CÔNG
        val prefs = requireActivity().getSharedPreferences("PiperPrefs", Context.MODE_PRIVATE)
        val isCurrentlyEnabled = prefs.getBoolean("fingerprint_enabled", false)
        val newSetting = !isCurrentlyEnabled // Đảo ngược trạng thái

        // Lưu trạng thái mới
        prefs.edit().putBoolean("fingerprint_enabled", newSetting).apply()
        // Cập nhật lại giao diện Switch
        updateFingerprintSwitchState()

        val message = if (newSetting) "Đã bật khóa bằng vân tay." else "Đã tắt khóa bằng vân tay."
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    // --------------------------------

    // --- CÁC HÀM DEVICE ADMIN ---
    private fun updateAdminSwitchState() {
        val isAdminActive = devicePolicyManager.isAdminActive(componentName)
        switchAdmin.isChecked = isAdminActive
    }

    private fun activateDeviceAdmin() {
        val isAdminActive = devicePolicyManager.isAdminActive(componentName)
        if (isAdminActive) {
            Toast.makeText(requireContext(), "Quyền đã được kích hoạt.", Toast.LENGTH_SHORT).show()
            return
        }

        // Tạo Intent để mở màn hình xin quyền của hệ thống
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Kích hoạt quyền này để sử dụng các tính năng bảo mật nâng cao của Piper OS Tool."
        )
        // Mở màn hình và chờ kết quả
        adminResultLauncher.launch(intent)
    }

    private fun deactivateDeviceAdmin() {
        val isAdminActive = devicePolicyManager.isAdminActive(componentName)
        if (isAdminActive) {
            devicePolicyManager.removeActiveAdmin(componentName)
            // Cập nhật lại giao diện ngay sau khi gọi lệnh hủy
            updateAdminSwitchState()
        }
    }
}
