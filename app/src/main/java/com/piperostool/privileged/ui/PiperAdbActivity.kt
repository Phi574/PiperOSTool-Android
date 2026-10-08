package com.piperostool.privileged.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.piperostool.PiperAutoFont
import com.piperostool.PiperDialog
import com.piperostool.PiperModernUi
import com.piperostool.R
import com.piperostool.privileged.PiperError
import com.piperostool.privileged.PiperAdbClientPermission
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.adb.PiperAdbPairingNotifications
import com.piperostool.privileged.client.PiperPrivilegedClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Dedicated setup and live status screen for PiperOS's app-private Wireless ADB connection. */
class PiperAdbActivity : AppCompatActivity() {
    private companion object { const val REQUEST_PAIRING_NOTIFICATIONS = 3112 }

    private lateinit var client: PiperPrivilegedClient
    private lateinit var stateView: TextView
    private lateinit var detailView: TextView
    private lateinit var refreshButton: View
    private lateinit var connectButton: MaterialButton
    private lateinit var pairButton: MaterialButton
    private lateinit var adbSwitch: SwitchMaterial
    private lateinit var accessSwitches: List<Pair<SwitchMaterial, String>>
    private lateinit var logView: TextView
    private lateinit var logScrollView: android.widget.ScrollView
    private var actionJob: Job? = null
    private var busy = false
    private var resumedOnce = false
    private var suppressSwitchCallback = false
    private var suppressAccessCallback = false
    private var adbEnabled = false
    private var logSinceTimestamp = System.currentTimeMillis() - 5 * 60 * 1000L
    private val localLogs = mutableListOf<Triple<Long, String, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_piper_adb)
        client = PiperPrivilegedClient(this)
        stateView = findViewById(R.id.piperAdbState)
        detailView = findViewById(R.id.piperAdbDetail)
        refreshButton = findViewById(R.id.piperAdbRefresh)
        connectButton = findViewById(R.id.piperAdbConnect)
        pairButton = findViewById(R.id.piperAdbPair)
        adbSwitch = findViewById(R.id.piperAdbEnabled)
        accessSwitches = listOf(
            findViewById<SwitchMaterial>(R.id.piperAdbPermissionFileRead) to PiperAdbClientPermission.FILE_READ,
            findViewById<SwitchMaterial>(R.id.piperAdbPermissionFileWrite) to PiperAdbClientPermission.FILE_WRITE,
            findViewById<SwitchMaterial>(R.id.piperAdbPermissionApps) to PiperAdbClientPermission.APP_MANAGEMENT,
            findViewById<SwitchMaterial>(R.id.piperAdbPermissionActivities) to PiperAdbClientPermission.PRIVATE_ACTIVITIES,
            findViewById<SwitchMaterial>(R.id.piperAdbPermissionBinder) to PiperAdbClientPermission.SYSTEM_TRANSACTIONS,
            findViewById<SwitchMaterial>(R.id.piperAdbPermissionShell) to PiperAdbClientPermission.SHELL_COMMANDS
        )
        logView = findViewById(R.id.piperAdbLogs)
        logScrollView = findViewById(R.id.piperAdbLogScroll)
        val logPanel = findViewById<View>(R.id.piperAdbLogPanel)
        val logPanelPaddingBottom = logPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.piperAdbRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.piperAdbToolbar).setPadding(16, bars.top + 10, 16, 10)
            logPanel.setPadding(
                logPanel.paddingLeft,
                logPanel.paddingTop,
                logPanel.paddingRight,
                logPanelPaddingBottom + bars.bottom
            )
            insets
        }
        findViewById<View>(R.id.piperAdbBack).setOnClickListener { finish() }
        refreshButton.setOnClickListener { refreshConnection(forceReconnect = true) }
        connectButton.setOnClickListener {
            if (adbSwitch.isChecked) refreshConnection(forceReconnect = true) else adbSwitch.isChecked = true
        }
        adbSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!suppressSwitchCallback) setAdbEnabled(enabled)
        }
        accessSwitches.forEach { (switch, permission) ->
            switch.setOnCheckedChangeListener { button, granted ->
                if (!suppressAccessCallback) setClientPermission(switch, permission, granted)
            }
        }
        pairButton.setOnClickListener { beginPairing() }
        PiperModernUi.apply(findViewById(R.id.piperAdbRoot))
        PiperAutoFont.watch(findViewById(R.id.piperAdbRoot))
        lifecycleScope.launch { updateProgressLogs() }
        refreshConnection()
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (::client.isInitialized && !busy && actionJob?.isActive != true) refreshConnection()
    }

    override fun onDestroy() {
        actionJob?.cancel()
        if (::client.isInitialized) client.close()
        super.onDestroy()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        adbSwitch.isEnabled = !value
        connectButton.isEnabled = !value
        pairButton.isEnabled = !value && !isPiperAdbConnected(clientStatus)
        pairButton.alpha = if (pairButton.isEnabled) 1f else 0.55f
        refreshButton.isEnabled = !value
    }

    private var clientStatus: PiperServiceStatus? = null

    private fun refreshConnection(forceReconnect: Boolean = false) {
        if (busy || actionJob?.isActive == true) return
        actionJob = lifecycleScope.launch {
            logSinceTimestamp = System.currentTimeMillis() - 1_000
            setBusy(true)
            stateView.setText(R.string.piper_adb_checking)
            appendLocalLog("Bắt đầu kiểm tra kết nối PiperOS ADB", Color.LTGRAY)
            try {
                val enabled = client.adbEnabled()
                setSwitchChecked(enabled)
                adbEnabled = enabled
                val current = client.status()
                clientStatus = current
                when {
                    enabled && isPiperAdbConnected(current) -> Unit
                    forceReconnect && enabled -> client.reconnectAdb()
                    current?.state != PiperServiceState.STARTING -> client.refresh()
                }
                val status = awaitStatus()
                refreshClientPermissions()
                updateProgressLogs()
                render(status, enabled)
                if (forceReconnect && enabled) awaitStabilityWindow(SystemClock.elapsedRealtime())
            } finally {
                if (clientStatus?.state != PiperServiceState.STARTING) setBusy(false)
            }
        }
    }

    private fun setAdbEnabled(enabled: Boolean) {
        if (busy || actionJob?.isActive == true || enabled == adbEnabled) return
        actionJob = lifecycleScope.launch {
            logSinceTimestamp = System.currentTimeMillis() - 1_000
            setBusy(true)
            stateView.setText(if (enabled) R.string.piper_adb_checking else R.string.piper_adb_disconnecting)
            appendLocalLog(if (enabled) "Đang bật PiperOS ADB" else "Đang tắt PiperOS ADB", Color.LTGRAY)
            try {
                val saved = client.setAdbEnabled(enabled)
                if (!saved) {
                    if (enabled) {
                        setSwitchChecked(true)
                        adbEnabled = true
                        render(client.status(), true)
                    }
                    else PiperDialog.showMessage(this@PiperAdbActivity, getString(R.string.piper_adb_title), getString(R.string.piper_adb_disconnect_failed))
                    return@launch
                }
                val status = awaitStatus()
                updateProgressLogs()
                adbEnabled = enabled
                render(status, enabled)
                awaitStabilityWindow(SystemClock.elapsedRealtime())
            } finally {
                if (clientStatus?.state != PiperServiceState.STARTING) setBusy(false)
            }
        }
    }

    private fun setSwitchChecked(checked: Boolean) {
        suppressSwitchCallback = true
        adbSwitch.isChecked = checked
        suppressSwitchCallback = false
    }

    private fun setClientPermission(button: SwitchMaterial, permission: String, granted: Boolean) {
        button.isEnabled = false
        lifecycleScope.launch {
            val saved = client.setClientPermission(permission, granted)
            button.isEnabled = true
            if (!saved) {
                suppressAccessCallback = true
                button.isChecked = !granted
                suppressAccessCallback = false
                Toast.makeText(this@PiperAdbActivity, "Không lưu được quyền client PiperOS", Toast.LENGTH_SHORT).show()
            } else {
                appendLocalLog(
                    "Đã ${if (granted) "cấp" else "thu hồi"} quyền $permission cho PiperOS Tool",
                    if (granted) Color.rgb(34, 197, 94) else Color.rgb(239, 68, 68)
                )
            }
        }
    }

    private suspend fun refreshClientPermissions() {
        val granted = client.clientPermissions()
        suppressAccessCallback = true
        accessSwitches.forEach { (switch, permission) ->
            switch.isChecked = granted[permission] ?: true
            switch.isEnabled = true
        }
        suppressAccessCallback = false
    }

    private suspend fun awaitStatus(): PiperServiceStatus? {
        var lastLogRefresh = 0L
        while (true) {
            val status = client.status()
            clientStatus = status
            if (status != null && status.state != PiperServiceState.STARTING) return status
            val now = System.currentTimeMillis()
            if (now - lastLogRefresh >= 1_000) {
                updateProgressLogs()
                lastLogRefresh = now
                status?.detail?.takeIf(String::isNotBlank)?.let { detailView.text = it }
            }
            delay(250)
        }
    }

    private suspend fun awaitStabilityWindow(operationStartedAt: Long) {
        while (true) {
            val remaining = 10_000L - (SystemClock.elapsedRealtime() - operationStartedAt)
            if (remaining <= 0) return
            val seconds = (remaining + 999) / 1_000
            detailView.text = if (adbEnabled) {
                "Kết nối đã xác minh. Đang giữ ổn định thêm ${seconds}s; các nút ADB tạm khóa."
            } else {
                "Đã đóng phiên ADB. Đang xác nhận trạng thái thêm ${seconds}s; các nút ADB tạm khóa."
            }
            updateProgressLogs()
            delay(minOf(500L, remaining))
        }
    }

    private fun isPiperAdbConnected(status: PiperServiceStatus?): Boolean =
        status?.let {
            it.state == PiperServiceState.RUNNING && it.privilege == PiperPrivilege.SHELL &&
                it.startupMethod == "PIPEROS_ADB" && it.error == PiperError.NONE
        } == true

    private fun render(status: PiperServiceStatus?, enabled: Boolean) {
        adbEnabled = enabled
        setSwitchChecked(enabled)
        val isPiperAdb = isPiperAdbConnected(status)
        pairButton.visibility = if (isPiperAdb) View.GONE else View.VISIBLE
        clientStatus = status
        val isRoot = status?.let {
            it.state == PiperServiceState.RUNNING && it.privilege == PiperPrivilege.ROOT && it.error == PiperError.NONE
        } == true
        when {
            isPiperAdb -> {
                stateView.setText(R.string.piper_adb_connected)
                detailView.text = getString(R.string.piper_adb_connected_detail, status?.uid ?: 2000, status?.selinux ?: "Unknown")
            }
            isRoot -> {
                stateView.setText(R.string.piper_adb_root_detected)
                detailView.text = getString(R.string.piper_adb_root_detail, status?.selinux ?: "Unknown")
            }
            !enabled -> {
                stateView.setText(R.string.piper_adb_off)
                detailView.setText(R.string.piper_adb_off_detail)
            }
            status?.state == PiperServiceState.STARTING -> {
                stateView.setText(R.string.piper_adb_checking)
                detailView.text = status.detail.takeIf(String::isNotBlank)
                    ?: getString(R.string.piper_adb_checking_detail)
            }
            else -> {
                stateView.setText(R.string.piper_adb_not_connected)
                val error = status?.detail?.takeIf(String::isNotBlank)
                detailView.text = error ?: getString(R.string.piper_adb_not_connected_detail)
            }
        }
        connectButton.setText(if (enabled) R.string.piper_adb_reconnect else R.string.piper_adb_connect)
    }

    private suspend fun updateProgressLogs() {
        val lines = client.recentLogs(logSinceTimestamp)
        renderProgressLogs(lines)
    }

    private fun appendLocalLog(message: String, color: Int) {
        localLogs += Triple(System.currentTimeMillis(), message, color)
        while (localLogs.size > 12) localLogs.removeAt(0)
        lifecycleScope.launch { updateProgressLogs() }
    }

    private fun renderProgressLogs(lines: List<String>) {
        val output = SpannableStringBuilder()
        val rows = lines.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val operation = parts.getOrNull(1).orEmpty()
            val detail = parts.getOrNull(2).orEmpty()
            val isFailure = operation.contains("fail", true) || operation.contains("reject", true) ||
                operation == "initialize" ||
                detail.contains("error=", true) && !detail.contains("error=NONE", true) ||
                detail.contains("success=false", true)
            val isSuccess = operation.endsWith("-ok") || operation == "adb-stop" ||
                detail.contains("PIPEROS_ADB uid=2000", true) ||
                operation == "initialize-finish" && detail.contains("error=NONE", true) ||
                detail.contains("persisted=true", true) || detail.contains("persisted=false", true)
            val color = when {
                isFailure -> Color.rgb(239, 68, 68)
                isSuccess -> Color.rgb(34, 197, 94)
                else -> Color.rgb(148, 163, 184)
            }
            Triple(timestamp, formatServiceLog(operation, detail), color)
        } + localLogs
        val visibleRows = rows.sortedBy { it.first }.takeLast(30)
        if (visibleRows.isEmpty()) {
            val start = output.length
            output.append("Chưa có tiến trình ADB mới. Bật PiperOS ADB hoặc kết nối lại để xem từng bước.")
            output.setSpan(ForegroundColorSpan(Color.rgb(148, 163, 184)), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        visibleRows.forEach { (timestamp, message, color) ->
            val start = output.length
            output.append(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp)))
                .append("  ").append(message).append('\n')
            output.setSpan(ForegroundColorSpan(color), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logView.text = output
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun formatServiceLog(operation: String, detail: String): String = when (operation) {
        "adb-toggle" -> if (detail.contains("requested=true")) "Đã lưu yêu cầu bật PiperOS ADB" else "Đã lưu yêu cầu tắt PiperOS ADB"
        "adb-enable-start" -> "Đang khởi chạy dịch vụ và khôi phục quyền Wireless debugging đã lưu"
        "adb-enable-start-failed" -> "Không khởi chạy được dịch vụ ADB: $detail"
        "adb-stop-start" -> "Đang chờ tác vụ hiện tại xong rồi đóng phiên ADB"
        "adb-stop" -> "Đã đóng phiên ADB và yêu cầu dừng dịch vụ"
        "adb-stop-failed" -> "Lỗi khi đóng phiên ADB: $detail"
        "initialize-start" -> if (detail.contains("adb=true")) "Bắt đầu kết nối PiperOS ADB bằng quyền đã ghép đôi"
            else "Bắt đầu kiểm tra quyền hệ thống"
        "adb-connect-start" -> "Đang mở kết nối Wireless debugging đã ghép đôi"
        "adb-connect-ok" -> "Socket ADB đã mở; đang kiểm tra danh tính shell"
        "privileged-server-ready" -> "Tiến trình đặc quyền app_process đã chạy: $detail"
        "adb-identity-ok" -> detail.replace("Shell UID=", "UID shell=").replace(" verified; ", "; đã xác minh; ")
        "adb-connect-failed" -> "Kết nối hoặc xác minh ADB thất bại: $detail"
        "initialize-finish" -> "Khởi tạo xong: $detail"
        "start" -> detail
        "initialize" -> "Lỗi khởi tạo ADB: $detail"
        else -> "$operation: $detail"
    }

    private fun beginPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            PiperDialog.showMessage(this, getString(R.string.piper_adb_title), getString(R.string.piper_adb_android_version_required))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PAIRING_NOTIFICATIONS)
            return
        }
        PiperAdbPairingNotifications.showWaiting(this)
        openWirelessDebuggingSettings()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PAIRING_NOTIFICATIONS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            PiperAdbPairingNotifications.showWaiting(this)
            openWirelessDebuggingSettings()
        } else {
            PiperDialog.showMessage(this, getString(R.string.piper_adb_title), getString(R.string.pps_notification_permission_required))
        }
    }

    private fun openWirelessDebuggingSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        Toast.makeText(this, R.string.pps_keep_pairing_screen_open, Toast.LENGTH_LONG).show()
    }
}
