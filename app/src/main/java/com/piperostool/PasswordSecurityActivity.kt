package com.piperostool

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PasswordSecurityActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_security)
        PiperModernUi.watch(this)
        findViewById<View>(R.id.btnSecurityBack).setOnClickListener { finish() }
        findViewById<View>(R.id.securityDeviceHistory).apply {
            bindAction(R.string.security_device_history, R.string.security_device_history_summary, R.drawable.devices)
            setOnClickListener { startActivity(Intent(this@PasswordSecurityActivity, DeviceSessionsActivity::class.java)) }
        }
        findViewById<View>(R.id.securityChangePassword).apply {
            bindAction(R.string.security_change_password, R.string.security_change_password_summary, R.drawable.security)
            setOnClickListener { startActivity(Intent(this@PasswordSecurityActivity, ChangePasswordActivity::class.java)) }
        }
        findViewById<View>(R.id.securityManageLinksBeta).apply {
            findViewById<TextView>(R.id.securityActionTitle)?.setText(R.string.security_manage_links)
            findViewById<TextView>(R.id.securityActionSummary)?.setText(R.string.security_manage_links_summary)
            findViewById<ImageView>(R.id.securityActionIcon)?.setImageResource(R.drawable.user)
            setOnClickListener { startActivity(Intent(this@PasswordSecurityActivity, LinkedAccountsActivity::class.java)) }
        }
    }

    private fun View.bindAction(title: Int, summary: Int, icon: Int) {
        findViewById<TextView>(R.id.securityActionTitle).setText(title)
        findViewById<TextView>(R.id.securityActionSummary).setText(summary)
        findViewById<ImageView>(R.id.securityActionIcon).setImageResource(icon)
    }
}
