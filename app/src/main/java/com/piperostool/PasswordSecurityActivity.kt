package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PasswordSecurityActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_security)
        PiperModernUi.watch(this)
        findViewById<View>(R.id.btnSecurityBack).setOnClickListener { finish() }

        val config = intent.getStringExtra(EXTRA_WINDOW_CONFIG_JSON)
            ?.let { raw -> runCatching { WindowConfigRepository.parse(raw) }.getOrNull() }

        if (config == null) {
            hideSecurityRows()
            showUnavailable("Không thể xác minh trạng thái các tính năng. Hãy quay lại trang chủ, kiểm tra mạng rồi thử lại.")
            return
        }

        val deviceHistory = config.securityMenu.firstOrNull {
            it.id == "device_history" && it.action == "device_sessions" && it.enabled
        }
        val changePassword = config.securityMenu.firstOrNull {
            it.id == "change_password" && it.action == "change_password" && it.enabled
        }
        val manageLinks = config.securityMenu.firstOrNull {
            it.id == "manage_links" && it.action == "linked_accounts" && it.enabled
        }

        if (deviceHistory == null && changePassword == null && manageLinks == null) {
            hideSecurityRows()
            showUnavailable("Hiện tại chưa khả dụng tính năng nào.")
            return
        }

        bindAction(
            rowId = R.id.securityDeviceHistory,
            item = deviceHistory,
            defaultTitle = R.string.security_device_history,
            defaultSummary = R.string.security_device_history_summary,
            defaultIcon = R.drawable.devices
        ) {
            startActivity(Intent(this, DeviceSessionsActivity::class.java))
        }

        bindAction(
            rowId = R.id.securityChangePassword,
            item = changePassword,
            defaultTitle = R.string.security_change_password,
            defaultSummary = R.string.security_change_password_summary,
            defaultIcon = R.drawable.security
        ) {
            startActivity(Intent(this, ChangePasswordActivity::class.java))
        }

        bindAction(
            rowId = R.id.securityManageLinksBeta,
            item = manageLinks,
            defaultTitle = R.string.security_manage_links,
            defaultSummary = R.string.security_manage_links_summary,
            defaultIcon = R.drawable.user
        ) {
            startActivity(Intent(this, LinkedAccountsActivity::class.java))
        }
    }

    private fun bindAction(
        rowId: Int,
        item: WindowConfigItem?,
        defaultTitle: Int,
        defaultSummary: Int,
        defaultIcon: Int,
        onOpen: () -> Unit
    ) {
        val row = findViewById<View>(rowId)
        if (item == null || !item.enabled) {
            row.visibility = View.GONE
            return
        }

        val iconResource = when (item.icon) {
            "devices" -> R.drawable.devices
            "security" -> R.drawable.security
            "user" -> R.drawable.user
            else -> defaultIcon
        }

        row.findViewById<TextView>(R.id.securityActionTitle)?.text =
            item.title.ifBlank { getString(defaultTitle) }
        row.findViewById<TextView>(R.id.securityActionSummary)?.text =
            item.summary.ifBlank { getString(defaultSummary) }
        row.findViewById<ImageView>(R.id.securityActionIcon)?.setImageResource(iconResource)
        row.alpha = 1f
        row.setOnClickListener { onOpen() }
        row.visibility = View.VISIBLE
    }

    private fun hideSecurityRows() {
        findViewById<View>(R.id.securityDeviceHistory).visibility = View.GONE
        findViewById<View>(R.id.securityChangePassword).visibility = View.GONE
        findViewById<View>(R.id.securityManageLinksBeta).visibility = View.GONE
    }

    private fun showUnavailable(message: String) {
        PiperDialog.showMessage(
            context = this,
            title = "Tính năng hiện không khả dụng",
            message = message,
            icon = R.drawable.error_bug,
            positiveLabel = "Đóng"
        )
    }

    companion object {
        const val EXTRA_WINDOW_CONFIG_JSON = "window_config_json"
    }
}
