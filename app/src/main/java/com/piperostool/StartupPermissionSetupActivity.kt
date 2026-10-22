package com.piperostool

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

private data class StartupPermission(
    val title: String,
    val description: String,
    val runtimePermissions: List<String> = emptyList(),
    val settingsAction: String? = null,
    val customIntent: ((Context) -> Intent)? = null,
    val granted: (Context) -> Boolean
)

private object StartupPermissionCatalog {
    fun shouldShow(context: Context): Boolean = missing(context).isNotEmpty()

    fun missing(context: Context): List<StartupPermission> = requirements(context).filterNot { it.granted(context) }

    fun runtimePermissions(context: Context): List<String> = missing(context)
        .flatMap { it.runtimePermissions }
        .distinct()

    private fun requirements(context: Context): List<StartupPermission> {
        @Suppress("DEPRECATION")
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        fun has(permission: String) = permission in declared
        val entries = mutableListOf<StartupPermission>()

        fun runtime(
            title: String,
            description: String,
            permission: String,
            minimumSdk: Int = Build.VERSION_CODES.M,
            maximumSdk: Int = Int.MAX_VALUE
        ) {
            if (Build.VERSION.SDK_INT < minimumSdk || Build.VERSION.SDK_INT > maximumSdk || !has(permission)) return
            entries += StartupPermission(title, description, listOf(permission)) { contextNow ->
                ContextCompat.checkSelfPermission(contextNow, permission) == PackageManager.PERMISSION_GRANTED
            }
        }

        runtime("Máy ảnh", "Quét mã QR và chụp nội dung trong các công cụ PiperOS.", Manifest.permission.CAMERA)
        runtime("Micro", "Ghi âm trong các tính năng có âm thanh.", Manifest.permission.RECORD_AUDIO)

        val locationPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter(::has)
        if (locationPermissions.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            entries += StartupPermission(
                "Vị trí",
                "Dùng bản đồ giả lập và tìm thiết bị gần đây. Có thể chọn vị trí chính xác để dùng đầy đủ tính năng.",
                locationPermissions
            ) { ctx -> locationPermissions.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED } }
        }

        runtime("Thông báo", "Báo tiến trình tải xuống và bản cập nhật mới.", Manifest.permission.POST_NOTIFICATIONS, 33)
        runtime("Tệp âm thanh", "Cho phép PiperOS Media duyệt nhạc trên máy.", Manifest.permission.READ_MEDIA_AUDIO, 33)
        runtime("Tệp video", "Cho phép PiperOS Media duyệt video trên máy.", Manifest.permission.READ_MEDIA_VIDEO, 33)
        runtime("Ảnh đã chọn", "Cho phép PiperOS truy cập ảnh bạn chọn trên Android mới.", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, 34)
        runtime("Bộ nhớ chung", "Cho phép chọn tệp trên Android cũ.", Manifest.permission.READ_EXTERNAL_STORAGE, 29, 32)
        runtime("Mạng cục bộ", "Tìm và kết nối thiết bị trong cùng mạng Wi‑Fi.", "android.permission.ACCESS_LOCAL_NETWORK", 37)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && has(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
            entries += StartupPermission(
                "Quản lý tất cả tệp",
                "Cho phép trình quản lý tệp PiperOS đọc và quản lý các thư mục dùng chung.",
                settingsAction = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            ) { ctx -> Environment.isExternalStorageManager() }
        }
        if (has(Manifest.permission.PACKAGE_USAGE_STATS)) {
            entries += StartupPermission(
                "Thống kê sử dụng ứng dụng",
                "Hiển thị hoạt động và thông tin sử dụng ứng dụng trong trang Ứng dụng.",
                settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS
            ) { ctx ->
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
            }
        }
        val hasNotificationListener = runCatching {
            context.packageManager.getServiceInfo(ComponentName(context, NotificationListener::class.java), 0)
                .permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
        }.getOrDefault(false)
        if (hasNotificationListener) {
            entries += StartupPermission(
                "Truy cập thông báo",
                "Cho phép tính năng thông báo của PiperOS đọc thông báo sau khi bạn bật dịch vụ.",
                settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ) { ctx ->
                Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                    .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString)
                    .any { it.packageName == ctx.packageName }
            }
        }
        val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
        entries += StartupPermission(
            title = "Quản trị viên thiết bị",
            description = "Cần bật để sẵn sàng cho PiperOS Family. Bạn có thể bỏ qua và bật sau trong mục Quyền.",
            customIntent = { ctx ->
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, MyDeviceAdminReceiver::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Quyền này được dùng cho các tính năng quản trị thiết bị của PiperOS.")
            },
            granted = { ctx ->
                (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComponent)
            }
        )
        if (Build.VERSION.SDK_INT >= 26 && has(Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
            entries += StartupPermission(
                "Cài ứng dụng từ PiperOS",
                "Cho phép mở trình cài đặt sau khi bạn xác minh và tải APK về.",
                settingsAction = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            ) { ctx -> ctx.packageManager.canRequestPackageInstalls() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && has(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
            entries += StartupPermission(
                "Cho phép chạy nền ổn định (tùy chọn)",
                "Giảm khả năng Android dừng các tác vụ nền. Có thể bỏ qua nếu bạn không cần.",
                settingsAction = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ) { ctx ->
                (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && has("android.permission.SYSTEM_ALERT_WINDOW")) {
            entries += StartupPermission(
                "Hiển thị trên ứng dụng khác",
                "Cho phép các tiện ích nổi hiển thị khi bạn sử dụng ứng dụng khác.",
                settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            ) { ctx -> Settings.canDrawOverlays(ctx) }
        }
        return entries
    }
}

class StartupPermissionSetupActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var requestAll: MaterialButton

    private val requestRuntimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val openSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(TextView(this).apply {
            text = "Chuẩn bị quyền cho PiperOS"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@StartupPermissionSetupActivity))
        }, margin(bottom = 8))
        body.addView(TextView(this).apply {
            text = "Cấp các quyền bạn muốn dùng ngay bây giờ. PiperOS sẽ gom những quyền còn thiếu tại đây; bạn có thể bỏ qua và cấp sau trong Cài đặt hoặc khi mở tính năng."
            textSize = 14f
            setTextColor(PiperModernUi.secondaryTextColor(this@StartupPermissionSetupActivity))
            setLineSpacing(dp(3).toFloat(), 1f)
        }, margin(bottom = 14))
        summary = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@StartupPermissionSetupActivity))
        }
        body.addView(summary, margin(bottom = 8))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        body.addView(TextView(this).apply {
            text = "Một số quyền hệ thống được bảo vệ (như WRITE_SECURE_SETTINGS, GRANT_RUNTIME_PERMISSIONS và DUMP) không thể bật bằng hộp thoại thông thường; chúng cần ADB hoặc quyền hệ thống."
            textSize = 12f
            setTextColor(PiperModernUi.secondaryTextColor(this@StartupPermissionSetupActivity))
            setPadding(0, dp(12), 0, dp(8))
        })
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        requestAll = MaterialButton(this).apply {
            text = "Cấp các quyền ứng dụng"
            setOnClickListener { requestMissingRuntimePermissions() }
        }
        root.addView(requestAll, margin(top = 8, bottom = 6))
        val actions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(MaterialButton(this).apply {
            text = "Để sau"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        actions.addView(MaterialButton(this).apply {
            text = "Tiếp tục"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(8) })
        root.addView(actions)
        setContentView(root)

        PiperModernUi.watch(this)
        PiperAutoFont.watch(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(18) + bars.left, dp(16) + bars.top, dp(18) + bars.right, dp(12) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refresh()
    }

    private fun refresh() {
        val missing = StartupPermissionCatalog.missing(this)
        summary.text = if (missing.isEmpty()) "Bạn đã cấp đủ các quyền khả dụng." else "Còn thiếu ${missing.size} quyền hoặc mục cài đặt:"
        list.removeAllViews()
        if (missing.isEmpty()) {
            list.addView(permissionCard("Sẵn sàng", "Các quyền có thể cấp trong ứng dụng đã được bật."))
        } else {
            missing.forEach { requirement ->
                val card = permissionCard(requirement.title, requirement.description)
                card.setOnClickListener { openRequirement(requirement) }
                list.addView(card, margin(bottom = 8))
            }
        }
        requestAll.visibility = if (StartupPermissionCatalog.runtimePermissions(this).isEmpty()) View.GONE else View.VISIBLE
        requestAll.text = "Cấp các quyền ứng dụng (${StartupPermissionCatalog.runtimePermissions(this).size})"
    }

    private fun permissionCard(title: String, description: String): MaterialCardView {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        layout.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(PiperModernUi.textColor(this@StartupPermissionSetupActivity))
        })
        layout.addView(TextView(this).apply {
            text = description
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
            setTextColor(PiperModernUi.secondaryTextColor(this@StartupPermissionSetupActivity))
        })
        return MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(PiperModernUi.surfaceColor(this@StartupPermissionSetupActivity))
            strokeColor = PiperModernUi.borderColor(this@StartupPermissionSetupActivity)
            isClickable = true
            isFocusable = true
            addView(layout)
        }
    }

    private fun requestMissingRuntimePermissions() {
        val permissions = StartupPermissionCatalog.runtimePermissions(this)
        if (permissions.isNotEmpty()) requestRuntimePermissions.launch(permissions.toTypedArray())
    }

    private fun openRequirement(requirement: StartupPermission) {
        if (requirement.runtimePermissions.isNotEmpty()) {
            requestRuntimePermissions.launch(requirement.runtimePermissions.toTypedArray())
            return
        }
        val intent = requirement.customIntent?.invoke(this)
            ?: requirement.settingsAction?.let(::Intent)
            ?: return
        val action = intent.action
        when (action) {
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
                intent.data = Uri.fromParts("package", packageName, null)
        }
        runCatching { openSettings.launch(intent) }
            .onFailure {
                openSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
    }

    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun shouldShow(context: Context): Boolean = StartupPermissionCatalog.shouldShow(context)
    }
}
