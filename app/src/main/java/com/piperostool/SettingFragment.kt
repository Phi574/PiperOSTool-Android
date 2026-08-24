package com.piperostool

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlin.system.exitProcess



class SettingFragment : Fragment() {
    private companion object {
        const val ANDROID_SOURCE_URL = "https://github.com/Phi574/PiperOSTool-Android"
        const val RUNTIME_SOURCE_URL = "https://github.com/Phi574/Piperos_termux"
    }

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

    private lateinit var layoutChangeBackground: LinearLayout

    private lateinit var layoutResetBackground: LinearLayout

    private lateinit var tvCurrentBackground: TextView
    private lateinit var layoutUiStyle: View
    private lateinit var layoutColorMode: View
    private lateinit var layoutLanguage: View
    private lateinit var layoutFont: View
    private lateinit var tvUiStyleValue: TextView
    private lateinit var tvColorModeValue: TextView
    private lateinit var tvLanguageValue: TextView
    private lateinit var tvFontValue: TextView
    private lateinit var settingsRoot: View
// Firebase reference
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val userId: String
        get() = auth.currentUser?.uid ?: "unknown_user"
// Launcher cho Device Admin

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            if (saveImageToInternalStorage(it)) {
                showRestartDialog()
            } else {
                Toast.makeText(context, "Lỗi khi lưu ảnh! Hãy thử ảnh khác.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val pickFontLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val activeContext = context ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        PiperFontPreferences.importFont(activeContext, uri)
            .onSuccess { font ->
                Toast.makeText(
                    activeContext,
                    getString(R.string.settings_font_imported, font.name),
                    Toast.LENGTH_SHORT
                ).show()
                activity?.recreate()
            }
            .onFailure {
                Toast.makeText(
                    activeContext,
                    R.string.settings_font_import_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
    }
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
        settingsRoot = view
        view.findViewById<TextView>(R.id.tvSettingVersion).text =
            getString(R.string.auth_version, AppVersion.name(requireContext()))
        view.findViewById<View>(R.id.btnSettingLogout).setOnClickListener {
            showLogoutConfirmation()
        }
        view.findViewById<View>(R.id.btnAndroidSource).setOnClickListener {
            openProjectUrl(ANDROID_SOURCE_URL)
        }
        view.findViewById<View>(R.id.btnRuntimeSource).setOnClickListener {
            openProjectUrl(RUNTIME_SOURCE_URL)
        }
        NetworkAccess.observe(viewLifecycleOwner, requireContext()) { online ->
            layoutPasswordToggle.visibility = if (online) View.VISIBLE else View.GONE
            btnChangeLock.visibility = if (online) View.VISIBLE else View.GONE
            if (!online) {
                tvChangeLockStatus.text = getString(R.string.settings_lock_offline)
                NetworkAccess.showOffline(view)
            }
        }

        setupBiometric()

        setupDeviceAdmin()

        updateBackgroundStatusText()
        updateAppearanceStatus()

        layoutUiStyle.setOnClickListener { showUiStyleDialog() }
        layoutColorMode.setOnClickListener { showColorModeDialog() }
        layoutLanguage.setOnClickListener { showLanguageDialog() }
        layoutFont.setOnClickListener { showFontDialog() }


        // --- XỬ LÝ HÌNH NỀN (MỚI) ---
        layoutChangeBackground.setOnClickListener {
            pickImageLauncher.launch("image/*") // Mở thư viện chọn ảnh
        }

        layoutResetBackground.setOnClickListener {
            resetBackgroundAndRestart()
        }

// --- XỬ LÝ SWITCH VÂN TAY (Chỉ bắt sự kiện Layout) ---

        layoutFingerprint.setOnClickListener {
            val isFingerprintOn = switchFingerprint.isChecked
            if (isFingerprintOn && !NetworkAccess.isOnline(requireContext())) {
                NetworkAccess.showOffline(settingsRoot)
                return@setOnClickListener
            }
            if (isFingerprintOn) {
                checkSecurityConstraintForFingerprint()
            } else {
                biometricPrompt.authenticate(promptInfo)

            }

        }


        layoutPasswordToggle.setOnClickListener {

            showLockTypeSelectionDialog()

        }



// --- NÚT THAY ĐỔI MÃ KHÓA ---

        btnChangeLock.setOnClickListener {

            showLockTypeSelectionDialog()

        }

    }

    private fun openProjectUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showLogoutConfirmation() {
        PiperDialog.showConfirm(
            context = requireContext(),
            title = getString(R.string.auth_logout),
            message = getString(R.string.logout_confirmation),
            positiveLabel = getString(R.string.auth_logout),
            destructive = true
        ) {
                DeviceSessionManager.endCurrentSession(requireContext()) {
                    auth.signOut()
                    startActivity(
                        Intent(requireContext(), LoginActivity::class.java).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                    requireActivity().finish()
                }
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


        layoutChangeBackground = view.findViewById(R.id.layoutChangeBackground)

        layoutResetBackground = view.findViewById(R.id.layoutResetBackground)

        tvCurrentBackground = view.findViewById(R.id.tvCurrentBackground)
        layoutUiStyle = view.findViewById(R.id.layoutUiStyle)
        layoutColorMode = view.findViewById(R.id.layoutColorMode)
        layoutLanguage = view.findViewById(R.id.layoutLanguage)
        layoutFont = view.findViewById(R.id.layoutFont)
        tvUiStyleValue = view.findViewById(R.id.tvUiStyleValue)
        tvColorModeValue = view.findViewById(R.id.tvColorModeValue)
        tvLanguageValue = view.findViewById(R.id.tvLanguageValue)
        tvFontValue = view.findViewById(R.id.tvFontValue)


        btnPermissions.setOnClickListener {

            val intent = Intent(requireContext(), PermissionManagerActivity::class.java)

            startActivity(intent)

        }


    }

    private fun updateAppearanceStatus() {
        tvUiStyleValue.setText(
            if (PiperUiPreferences.style(requireContext()) == PiperUiStyle.MODERN) {
                R.string.settings_ui_modern
            } else {
                R.string.settings_ui_classic
            }
        )
        tvColorModeValue.setText(
            when (PiperUiPreferences.colorMode(requireContext())) {
                PiperColorMode.SYSTEM -> R.string.settings_color_system
                PiperColorMode.LIGHT -> R.string.settings_color_light
                PiperColorMode.DARK -> R.string.settings_color_dark
            }
        )
        tvLanguageValue.setText(
            if (PiperUiPreferences.language(requireContext()) == "en") {
                R.string.settings_language_en
            } else {
                R.string.settings_language_vi
            }
        )
        tvFontValue.text = PiperFontPreferences.selectedName(requireContext())
    }

    private fun showUiStyleDialog() {
        val current = PiperUiPreferences.style(requireContext())
        PiperActionSheet.showSingleSelect(
            context = requireContext(),
            title = getString(R.string.settings_ui_style),
            choices = listOf(
                PiperSheetChoice("modern", getString(R.string.settings_ui_modern), current == PiperUiStyle.MODERN),
                PiperSheetChoice("classic", getString(R.string.settings_ui_classic), current == PiperUiStyle.CLASSIC)
            ),
            onSelect = { key ->
                PiperUiPreferences.setStyle(
                    requireContext(),
                    if (key == "modern") PiperUiStyle.MODERN else PiperUiStyle.CLASSIC
                )
                requireActivity().recreate()
            },
            onRemove = {},
            onAdd = {}
        )
    }

    private fun showColorModeDialog() {
        val modes = arrayOf(
            PiperColorMode.SYSTEM,
            PiperColorMode.LIGHT,
            PiperColorMode.DARK
        )
        val current = PiperUiPreferences.colorMode(requireContext())
        PiperActionSheet.showSingleSelect(
            context = requireContext(),
            title = getString(R.string.settings_color_mode),
            choices = listOf(
                PiperSheetChoice(PiperColorMode.SYSTEM.name, getString(R.string.settings_color_system), current == PiperColorMode.SYSTEM),
                PiperSheetChoice(PiperColorMode.LIGHT.name, getString(R.string.settings_color_light), current == PiperColorMode.LIGHT),
                PiperSheetChoice(PiperColorMode.DARK.name, getString(R.string.settings_color_dark), current == PiperColorMode.DARK)
            ),
            onSelect = { key ->
                PiperUiPreferences.setColorMode(requireContext(), modes.first { it.name == key })
                requireActivity().recreate()
            },
            onRemove = {},
            onAdd = {}
        )
    }

    private fun showLanguageDialog() {
        val current = PiperUiPreferences.language(requireContext())
        PiperActionSheet.showSingleSelect(
            context = requireContext(),
            title = getString(R.string.settings_language),
            choices = listOf(
                PiperSheetChoice("vi", getString(R.string.settings_language_vi), current == "vi"),
                PiperSheetChoice("en", getString(R.string.settings_language_en), current == "en")
            ),
            onSelect = { key ->
                PiperUiPreferences.setLanguage(requireContext(), key)
                requireActivity().recreate()
            },
            onRemove = {},
            onAdd = {}
        )
    }

    private fun showFontDialog() {
        val activeContext = requireContext()
        val selected = PiperFontPreferences.selectedKey(activeContext)
        PiperActionSheet.showSingleSelect(
            context = activeContext,
            title = getString(R.string.settings_font),
            choices = PiperFontPreferences.choices(activeContext).map { choice ->
                PiperSheetChoice(
                    key = choice.key,
                    label = choice.name,
                    selected = choice.key == selected,
                    removable = choice.removable
                )
            },
            addLabel = getString(R.string.settings_font_add),
            onSelect = { key ->
                PiperFontPreferences.select(activeContext, key)
                requireActivity().recreate()
            },
            onRemove = { key ->
                PiperFontPreferences.delete(activeContext, key)
                requireActivity().recreate()
            },
            onAdd = {
                pickFontLauncher.launch(
                    arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")
                )
            }
        )
    }



    override fun onResume() {

        super.onResume()

        updateAdminSwitchState()

        updateFingerprintSwitchState()

        checkPasswordStatusFromFirebase()

    }



    // ==========================================
    // CÁC HÀM XỬ LÝ HÌNH NỀN (MỚI)
    // ==========================================
    private fun updateBackgroundStatusText() {
        val prefs = AccountDataScope.preferences(requireContext(), "PiperPrefs")
        val hasCustomBg = prefs.getBoolean("has_custom_bg", false)
        tvCurrentBackground.text = if (hasCustomBg) "Tùy chỉnh" else "Mặc định"
    }

    private fun saveImageToInternalStorage(uri: Uri): Boolean {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            // Tạo một file tên là custom_bg.jpg nằm sâu trong app
            val file = AccountDataScope.file(requireContext(), "appearance", "custom_bg.jpg")
            val outputStream = java.io.FileOutputStream(file)

            // Copy dữ liệu ảnh sang file
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()

            // Dùng commit() thay vì apply() để bắt máy tính phải lưu xong ngay lập tức
            val prefs = AccountDataScope.preferences(requireContext(), "PiperPrefs")
            prefs.edit().putBoolean("has_custom_bg", true).commit()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Cập nhật hàm Reset
    private fun resetBackgroundAndRestart() {
        val prefs = AccountDataScope.preferences(requireContext(), "PiperPrefs")
        if (prefs.getBoolean("has_custom_bg", false)) {
            // Xóa file ảnh tùy chỉnh đi
            val file = AccountDataScope.file(requireContext(), "appearance", "custom_bg.jpg")
            if (file.exists()) file.delete()

            prefs.edit().putBoolean("has_custom_bg", false).commit()
            showRestartDialog()
        } else {
            Toast.makeText(context, "Đã là hình nền mặc định", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Yêu cầu Khởi động lại")
            .setMessage("Cần khởi động lại ứng dụng để áp dụng hình nền mới.")
            .setCancelable(false)
            .setPositiveButton("Khởi động lại ngay") { _, _ ->
                restartApp()
            }
            .show()
    }

    private fun restartApp() {
        // Tắt app hiện tại và mở lại
        val intent = Intent(requireContext(), WelcomeActivity::class.java) // Hoặc SplashScreenActivity
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

// --- LOGIC KIỂM TRA FIREBASE & TRẠNG THÁI ---

    private fun checkPasswordStatusFromFirebase() {
        if (!NetworkAccess.isOnline(requireContext())) {
            val lockPrefs = AccountDataScope.preferences(requireContext(), LockScreenActivity.PREFS_NAME)
            val hasPassword =
                lockPrefs.getString(LockScreenActivity.KEY_CACHED_PASS, null) != null
            switchPassword.isChecked = hasPassword
            tvChangeLockStatus.text = getString(R.string.settings_lock_offline)
            return
        }

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

    private fun showLockTypeSelectionDialog() {
        if (!NetworkAccess.isOnline(requireContext())) {
            NetworkAccess.showOffline(settingsRoot)
            return
        }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)

        val view = layoutInflater.inflate(R.layout.dialog_lock_type_selection, null)
        PiperAutoFont.watch(view)



// Hàm phụ để xử lý click cho gọn

        fun setupClick(viewId: Int, selectedKey: String) {

            view.findViewById<android.view.View>(viewId).setOnClickListener {

                val intent = Intent(requireContext(), LockScreenActivity::class.java)

                intent.putExtra("LOCK_TYPE_TO_CREATE", selectedKey)

                lockScreenLauncher.launch(intent)

                dialog.dismiss()

            }

        }



// Gán sự kiện cho từng nút Kính

        setupClick(R.id.btnNone, "none")

        setupClick(R.id.btnPin4, "pin_4")

        setupClick(R.id.btnPin6, "pin_6")

        setupClick(R.id.btnCustom, "custom")



        dialog.setContentView(view)

        dialog.show()

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

        val prefs = AccountDataScope.preferences(requireContext(), "PiperPrefs")

        switchFingerprint.isChecked = prefs.getBoolean("fingerprint_enabled", false)

    }



    private fun toggleFingerprintSetting() {

        val prefs = AccountDataScope.preferences(requireContext(), "PiperPrefs")

        val isCurrentlyEnabled = prefs.getBoolean("fingerprint_enabled", false)

        val newSetting = !isCurrentlyEnabled



// Không cần check constraint ở đây nữa vì đã check ở đầu vào (layoutFingerprint.setOnClickListener)



        prefs.edit().putBoolean("fingerprint_enabled", newSetting).apply()

        updateFingerprintSwitchState() // Cập nhật lại UI Switch sau khi lưu xong

        Toast.makeText(context, if (newSetting) "Đã bật vân tay" else "Đã tắt vân tay", Toast.LENGTH_SHORT).show()

    }

}
