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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class SettingFragment : Fragment() {

    private lateinit var layoutAdmin: LinearLayout
    private lateinit var switchAdmin: SwitchMaterial
    private lateinit var layoutFingerprint: LinearLayout
    private lateinit var switchFingerprint: SwitchMaterial

    // UI Password
    private lateinit var layoutPasswordToggle: LinearLayout
    private lateinit var switchPassword: SwitchMaterial

    // UI Background Monitor (MỚI)
    private lateinit var layoutBackgroundCheck: LinearLayout
    private lateinit var switchBackgroundCheck: SwitchMaterial

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

    // Launcher cho LockScreenActivity
    private val lockScreenLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

        // --- XỬ LÝ BACKGROUND MONITOR (MỚI) ---
        layoutBackgroundCheck.setOnClickListener {
            val sharedPrefs = requireContext().getSharedPreferences("PiperOS_Prefs", Context.MODE_PRIVATE)
            val currentState = switchBackgroundCheck.isChecked
            val newState = !currentState

            // Cập nhật UI & Lưu biến
            switchBackgroundCheck.isChecked = newState
            sharedPrefs.edit().putBoolean("bg_monitor_enabled", newState).apply()

            // Thực thi lệnh chạy ngầm
            if (newState) {
                enableBackgroundMonitor()
            } else {
                disableBackgroundMonitor()
            }
        }

        // --- XỬ LÝ SWITCH VÂN TAY ---
        layoutFingerprint.setOnClickListener {
            val isFingerprintOn = switchFingerprint.isChecked
            if (isFingerprintOn) {
                checkSecurityConstraintForFingerprint()
            } else {
                biometricPrompt.authenticate(promptInfo)
            }
        }

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

        // Ánh xạ Background Monitor (MỚI)
        layoutBackgroundCheck = view.findViewById(R.id.layoutBackgroundCheck)
        switchBackgroundCheck = view.findViewById(R.id.switchBackgroundCheck)

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

        // Khôi phục trạng thái Background Monitor từ SharedPreferences (MỚI)
        val sharedPrefs = requireContext().getSharedPreferences("PiperOS_Prefs", Context.MODE_PRIVATE)
        switchBackgroundCheck.isChecked = sharedPrefs.getBoolean("bg_monitor_enabled", false)
    }

    // ========================================================
    // --- LOGIC CHẠY NGẦM BACKGROUND MONITOR (MỚI) ---
    // ========================================================
    private fun enableBackgroundMonitor() {
        // Cứ 12 tiếng âm thầm chạy check Play Integrity 1 lần
        val workRequest = PeriodicWorkRequestBuilder<IntegrityBackgroundWorker>(
            12, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork(
            "PlayIntegrityCheck",
            ExistingPeriodicWorkPolicy.UPDATE, // Ghi đè nếu có lịch cũ
            workRequest
        )
        Toast.makeText(context, "Đã bật giám sát hệ thống ngầm!", Toast.LENGTH_SHORT).show()
    }

    private fun disableBackgroundMonitor() {
        WorkManager.getInstance(requireContext()).cancelUniqueWork("PlayIntegrityCheck")
        Toast.makeText(context, "Đã tắt giám sát ngầm.", Toast.LENGTH_SHORT).show()
    }

    // ========================================================
    // CÁC HÀM CŨ GIỮ NGUYÊN (Firebase, Security, Admin, Biometric)
    // ========================================================
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

    private fun checkSecurityConstraintForFingerprint() {
        val myRef = database.getReference("users/$userId/security/password")
        myRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val hasPassword = snapshot.exists() && (snapshot.value as String).isNotEmpty()
                if (hasPassword) {
                    biometricPrompt.authenticate(promptInfo)
                } else {
                    Toast.makeText(requireContext(), "Không thể tắt! Phải bật ít nhất 1 phương thức bảo mật.", Toast.LENGTH_LONG).show()
                }
            }
            override fun onCancelled(error: DatabaseError) { }
        })
    }

    private fun showLockTypeSelectionDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_lock_type_selection, null)

        fun setupClick(viewId: Int, selectedKey: String) {
            view.findViewById<View>(viewId).setOnClickListener {
                val intent = Intent(requireContext(), LockScreenActivity::class.java)
                intent.putExtra("LOCK_TYPE_TO_CREATE", selectedKey)
                lockScreenLauncher.launch(intent)
                dialog.dismiss()
            }
        }

        setupClick(R.id.btnNone, "none")
        setupClick(R.id.btnPin4, "pin_4")
        setupClick(R.id.btnPin6, "pin_6")
        setupClick(R.id.btnCustom, "custom")

        dialog.setContentView(view)
        dialog.show()
    }

    private fun setupDeviceAdmin() {
        devicePolicyManager = requireActivity().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireActivity(), MyDeviceAdminReceiver::class.java)

        layoutAdmin.setOnClickListener {
            if (switchAdmin.isChecked) deactivateDeviceAdmin() else activateDeviceAdmin()
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
                updateFingerprintSwitchState()
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
        prefs.edit().putBoolean("fingerprint_enabled", newSetting).apply()
        updateFingerprintSwitchState()
        Toast.makeText(context, if (newSetting) "Đã bật vân tay" else "Đã tắt vân tay", Toast.LENGTH_SHORT).show()
    }
}