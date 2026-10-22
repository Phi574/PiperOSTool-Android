package com.piperostool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView

class PermissionManagerActivity : AppCompatActivity() {

    private lateinit var permissionContainer: LinearLayout
    private val runtimePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { loadAndDisplayPermissions() }

    private val deviceAdminPermission = "com.piperostool.permission.DEVICE_ADMIN"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_manager)

        val root = findViewById<NestedScrollView>(R.id.permissionRoot)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        permissionContainer = findViewById(R.id.permission_list_container)
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        loadAndDisplayPermissions()
        setupOptimizationButtons()
        root.post {
            root.requestFocus()
            root.scrollTo(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissionContainer.isInitialized) loadAndDisplayPermissions()
    }

    private fun loadAndDisplayPermissions() {
        permissionContainer.removeAllViews()
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            packageInfo.requestedPermissions.orEmpty().distinct().forEach { permission ->
                val granted = isPermissionGranted(permission)
                val simpleName = permission.substringAfterLast('.')
                addPermissionView("$simpleName: ${if (granted) "Đã cấp" else "Chưa cấp"}", granted, permission)
            }
            val adminGranted = isDeviceAdminActive()
            addPermissionView(
                "Quản trị viên thiết bị: ${if (adminGranted) "Đã bật" else "Chưa bật"}",
                adminGranted,
                deviceAdminPermission
            )
            if (permissionContainer.childCount == 0) addPermissionView("Không yêu cầu quyền đặc biệt nào.", true, "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isPermissionGranted(permission: String): Boolean = when (permission) {
        "android.permission.MANAGE_EXTERNAL_STORAGE" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        "android.permission.PACKAGE_USAGE_STATS" -> {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        }
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).any { it.packageName == packageName }
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        "android.permission.SYSTEM_ALERT_WINDOW" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        "android.permission.REQUEST_INSTALL_PACKAGES" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
        else -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isDeviceAdminActive(): Boolean {
        val component = ComponentName(this, MyDeviceAdminReceiver::class.java)
        return (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(component)
    }

    private fun addPermissionView(permissionText: String, isGranted: Boolean, permissionName: String) {
        val inflater = LayoutInflater.from(this)
        val permissionView =            inflater.inflate(R.layout.item_permission_view, permissionContainer, false)

        val icon = permissionView.findViewById<ImageView>(R.id.permission_icon)
        val text = permissionView.findViewById<TextView>(R.id.permission_text)

        text.text = permissionText
        if (isGranted) {
            icon.setImageResource(R.drawable.check_circle)
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))

            // Quyền đã cấp thì không cần click, hoặc click để thông báo
            permissionView.setOnClickListener {
                Toast.makeText(this, "Quyền này đã được cấp!", Toast.LENGTH_SHORT).show()
            }
        } else {
            icon.setImageResource(R.drawable.cancel_circle)
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_light))

            // === PHẦN THÊM MỚI: BẤM ĐỂ CẤP QUYỀN ===
            // Nếu chưa cấp quyền, làm cho dòng này có thể bấm được
            permissionView.isClickable = true
            permissionView.isFocusable = true

            // (Tuỳ chọn) Thêm hiệu ứng gợn sóng (ripple) hoặc đổi màu nền nhẹ để user biết bấm được
            // permissionView.setBackgroundResource(android.R.drawable.list_selector_background)

            permissionView.setOnClickListener {
                if (permissionName.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        "Đang mở cài đặt cho quyền: ${permissionName.substringAfterLast('.')}",
                        Toast.LENGTH_SHORT
                    ).show()
                    openPermissionGrant(permissionName)
                }
            }
        }
        permissionContainer.addView(permissionView)
        PiperAutoFont.apply(text)
        text.setTextColor(PiperModernUi.secondaryTextColor(this))
    }

    private fun openPermissionGrant(permissionName: String) {
        if (permissionName == deviceAdminPermission) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, MyDeviceAdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Quyền này được dùng cho các tính năng quản trị thiết bị của PiperOS.")
            runCatching { startActivity(intent) }.onFailure { openAppDetails() }
            return
        }
        when (permissionName) {
            "android.permission.MANAGE_EXTERNAL_STORAGE" -> openSettings(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                else Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                withPackage = true
            )
            "android.permission.PACKAGE_USAGE_STATS" -> openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
            "android.permission.SYSTEM_ALERT_WINDOW" -> openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true)
            "android.permission.REQUEST_INSTALL_PACKAGES" -> openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, withPackage = true)
            "android.permission.POST_NOTIFICATIONS" -> runtimePermissionRequest.launch(permissionName)
            "android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP" ->
                startActivity(Intent(this, com.piperostool.privileged.ui.PiperAdbActivity::class.java))
            else -> {
                val protection = runCatching {
                    @Suppress("DEPRECATION")
                    packageManager.getPermissionInfo(permissionName, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                }.getOrNull()
                if (protection == PermissionInfo.PROTECTION_DANGEROUS) runtimePermissionRequest.launch(permissionName)
                else openAppDetails()
            }
        }
    }

    private fun openSettings(action: String, withPackage: Boolean = false) {
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.fromParts("package", packageName, null)
        runCatching { startActivity(intent) }.onFailure { openAppDetails() }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null)))
        }.onFailure {
            Toast.makeText(this, "Không tìm thấy màn hình cấp quyền trên thiết bị.", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupOptimizationButtons() {
        // Nút tắt tối ưu hóa pin
        findViewById<LinearLayout>(R.id.btnDisableBatteryOptimization).setOnClickListener {
            val intent = Intent()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Ứng dụng đã được tắt tối ưu hóa pin.", Toast.LENGTH_SHORT).show()
            }
        }

        // Nút cho phép Autostart
        findViewById<LinearLayout>(R.id.btnAllowAutostart).setOnClickListener {
            tryToOpenAutostartSettings()
        }

        // ======== THÊM MỚI: SỰ KIỆN CHO NÚT QUẢN LÝ THÔNG BÁO ========
        findViewById<LinearLayout>(R.id.btnNotificationSettings).setOnClickListener {
            openNotificationSettings()
        }
        // ==========================================================
    }

    // ======== HÀM MỚI: MỞ CÀI ĐẶT THÔNG BÁO CHO APP ========
    private fun openNotificationSettings() {
        val intent = Intent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Từ Android 8.0 (Oreo) trở lên, mở thẳng đến channel của app
            intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            // Các phiên bản cũ hơn, mở đến cài đặt chi tiết của app
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
    // ======================================================

    private fun tryToOpenAutostartSettings() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")).setData(Uri.parse("mobilemanager://function/entry/AutoStart"))
        )

        for (intent in intents) {
            if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(intent)
                return
            }
        }
        Toast.makeText(this, "Không tìm thấy cài đặt tự khởi chạy, vui lòng tìm trong Cài đặt ứng dụng.", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }
}
