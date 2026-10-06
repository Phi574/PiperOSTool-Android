package com.piperostool.privileged.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.adb.PiperAdbPairingNotifications
import com.piperostool.privileged.client.PiperPrivilegedClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private var actionJob: Job? = null
    private var busy = false
    private var suppressSwitchCallback = false
    private var adbEnabled = false

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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.piperAdbRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.piperAdbToolbar).setPadding(16, bars.top + 10, 16, 10)
            insets
        }
        findViewById<View>(R.id.piperAdbBack).setOnClickListener { finish() }
        refreshButton.setOnClickListener { refreshConnection() }
        connectButton.setOnClickListener {
            if (adbSwitch.isChecked) refreshConnection() else adbSwitch.isChecked = true
        }
        adbSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!suppressSwitchCallback) setAdbEnabled(enabled)
        }
        pairButton.setOnClickListener { beginPairing() }
        PiperModernUi.apply(findViewById(R.id.piperAdbRoot))
        PiperAutoFont.watch(findViewById(R.id.piperAdbRoot))
        refreshConnection()
    }

    override fun onResume() {
        super.onResume()
        if (::client.isInitialized && !busy) refreshConnection()
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
        pairButton.isEnabled = !value
        refreshButton.isEnabled = !value
    }

    private fun refreshConnection() {
        if (busy) return
        actionJob = lifecycleScope.launch {
            setBusy(true)
            stateView.setText(R.string.piper_adb_checking)
            try {
                val enabled = client.adbEnabled()
                setSwitchChecked(enabled)
                adbEnabled = enabled
                val current = client.status()
                if (current?.state != PiperServiceState.STARTING) client.refresh()
                val status = awaitStatus()
                if (enabled && !isPiperAdbConnected(status)) {
                    turnOffAfterConnectionFailure(status)
                } else {
                    render(status, enabled)
                }
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setAdbEnabled(enabled: Boolean) {
        if (busy || enabled == adbEnabled) return
        actionJob = lifecycleScope.launch {
            setBusy(true)
            stateView.setText(if (enabled) R.string.piper_adb_checking else R.string.piper_adb_disconnecting)
            try {
                val saved = client.setAdbEnabled(enabled)
                if (!saved) {
                    if (enabled) turnOffAfterConnectionFailure(null)
                    else PiperDialog.showMessage(this@PiperAdbActivity, getString(R.string.piper_adb_title), getString(R.string.piper_adb_disconnect_failed))
                    return@launch
                }
                val status = awaitStatus()
                if (enabled && !isPiperAdbConnected(status)) {
                    turnOffAfterConnectionFailure(status)
                } else {
                    adbEnabled = enabled
                    render(status, enabled)
                }
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun turnOffAfterConnectionFailure(failedStatus: PiperServiceStatus?) {
        setSwitchChecked(false)
        adbEnabled = false
        client.setAdbEnabled(false)
        awaitStatus()
        stateView.setText(R.string.piper_adb_setup_required)
        detailView.text = failedStatus?.detail?.takeIf(String::isNotBlank)
            ?.let { getString(R.string.piper_adb_setup_required_detail, it) }
            ?: getString(R.string.piper_adb_setup_required_short)
        PiperDialog.showMessage(
            this,
            getString(R.string.piper_adb_title),
            getString(R.string.piper_adb_setup_required_short)
        )
    }

    private fun setSwitchChecked(checked: Boolean) {
        suppressSwitchCallback = true
        adbSwitch.isChecked = checked
        suppressSwitchCallback = false
    }

    private suspend fun awaitStatus(): PiperServiceStatus? {
        repeat(52) {
            val status = client.status()
            if (status != null && status.state != PiperServiceState.STARTING) return status
            delay(250)
        }
        return client.status()
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
                detailView.setText(R.string.piper_adb_checking_detail)
            }
            else -> {
                stateView.setText(R.string.piper_adb_not_connected)
                val error = status?.detail?.takeIf(String::isNotBlank)
                detailView.text = error ?: getString(R.string.piper_adb_not_connected_detail)
            }
        }
        connectButton.setText(if (enabled) R.string.piper_adb_reconnect else R.string.piper_adb_connect)
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
