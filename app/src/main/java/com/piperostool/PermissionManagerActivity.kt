package com.piperostool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionManagerActivity : AppCompatActivity() {

    private lateinit var permissionContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_manager)

        permissionContainer = findViewById(R.id.permission_list_container)
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        loadAndDisplayPermissions()
        setupOptimizationButtons()
    }

    private fun loadAndDisplayPermissions() {
        permissionContainer.removeAllViews()
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val requestedPermissions = packageInfo.requestedPermissions

            if (requestedPermissions.isNullOrEmpty()) {
                addPermissionView("Không yêu cầu quyền đặc biệt nào.", true)
                return
            }

            for (permission in requestedPermissions) {
                val isGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                val simpleName = permission.substringAfterLast('.')
                addPermissionView("$simpleName: ${if (isGranted) "Đã cấp" else "Chưa cấp"}", isGranted)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addPermissionView(permissionText: String, isGranted: Boolean) {
        val inflater = LayoutInflater.from(this)
        val permissionView = inflater.inflate(R.layout.item_permission_view, permissionContainer, false)

        val icon = permissionView.findViewById<ImageView>(R.id.permission_icon)
        val text = permissionView.findViewById<TextView>(R.id.permission_text)

        text.text = permissionText
        if (isGranted) {
            icon.setImageResource(R.drawable.check_circle)
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            icon.setImageResource(R.drawable.cancel_circle)
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }

        permissionContainer.addView(permissionView)
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
