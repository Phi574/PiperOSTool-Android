package com.piperostool.privileged.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButton
import com.piperostool.PiperActionSheet
import com.piperostool.PiperAutoFont
import com.piperostool.PiperDialog
import com.piperostool.PiperModernUi
import com.piperostool.PiperSheetChoice
import com.piperostool.R
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperError
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperPrivilegedPreferences
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.client.PiperPrivilegedClient
import com.piperostool.privileged.adb.PiperAdbBootstrap
import com.piperostool.privileged.adb.PiperAdbPairingNotifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

class AdvancedAccessActivity : AppCompatActivity() {
    private companion object {
        const val REQUEST_PAIRING_NOTIFICATIONS = 3111
    }

    private lateinit var client: PiperPrivilegedClient
    private lateinit var methodValue: TextView
    private lateinit var stateView: TextView
    private lateinit var identityView: TextView
    private lateinit var capabilityView: TextView
    private lateinit var errorView: TextView
    private lateinit var androidRestricted: SwitchMaterial
    private lateinit var systemFiles: SwitchMaterial
    private lateinit var systemWrite: SwitchMaterial
    private lateinit var workspace: SwitchMaterial
    private lateinit var hiddenFiles: SwitchMaterial
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var refreshButton: View
    private lateinit var methodRow: View
    private var latestCapabilities = PiperCapabilities()
    private var latestStatus = PiperServiceStatus()
    private var operationRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_advanced_access)
        client = PiperPrivilegedClient(this)
        bindViews()
        applyInsets()
        bindPreferences()
        configureActions()
        PiperModernUi.apply(findViewById(R.id.advancedAccessRoot))
        PiperAutoFont.watch(findViewById(R.id.advancedAccessRoot))
        refreshStatus()
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!::client.isInitialized) return
        lifecycleScope.launch {
            awaitSettledStatus()
            refreshStatus()
        }
    }

    private fun bindViews() {
        methodValue = findViewById(R.id.ppsMethodValue)
        stateView = findViewById(R.id.ppsState)
        identityView = findViewById(R.id.ppsIdentity)
        capabilityView = findViewById(R.id.ppsCapabilities)
        errorView = findViewById(R.id.ppsError)
        androidRestricted = findViewById(R.id.ppsAndroidRestricted)
        systemFiles = findViewById(R.id.ppsSystemFiles)
        systemWrite = findViewById(R.id.ppsSystemWrite)
        workspace = findViewById(R.id.ppsWorkspace)
        hiddenFiles = findViewById(R.id.ppsHiddenFiles)
        startButton = findViewById(R.id.ppsStart)
        stopButton = findViewById(R.id.ppsStop)
        refreshButton = findViewById(R.id.advancedAccessRefresh)
        methodRow = findViewById(R.id.ppsMethodRow)
    }

    private fun applyInsets() {
        val toolbar = findViewById<View>(R.id.advancedAccessToolbar)
        val initialTop = toolbar.paddingTop
        val root = findViewById<View>(R.id.advancedAccessRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, initialTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            insets
        }
    }

    private fun bindPreferences() {
        updateMethodLabel()
        androidRestricted.isChecked = PiperPrivilegedPreferences.androidRestricted(this)
        systemFiles.isChecked = PiperPrivilegedPreferences.systemFiles(this)
        systemWrite.isChecked = PiperPrivilegedPreferences.systemWrite(this)
        workspace.isChecked = PiperPrivilegedPreferences.workspace(this)
        hiddenFiles.isChecked = PiperPrivilegedPreferences.showHidden(this)

        androidRestricted.setOnCheckedChangeListener { _, value ->
            PiperPrivilegedPreferences.setAndroidRestricted(this, value)
        }
        systemFiles.setOnCheckedChangeListener { _, value ->
            PiperPrivilegedPreferences.setSystemFiles(this, value)
        }
        systemWrite.setOnCheckedChangeListener { _, value ->
            if (!value) {
                PiperPrivilegedPreferences.setSystemWrite(this, false)
                refreshService()
                return@setOnCheckedChangeListener
            }
            systemWrite.isChecked = false
            if (latestCapabilities.privilege != PiperPrivilege.ROOT) {
                Toast.makeText(this, R.string.pps_root_required, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            PiperDialog.showConfirm(
                this,
                getString(R.string.pps_system_write_warning_title),
                getString(R.string.pps_system_write_warning),
                getString(R.string.pps_enable_dangerous),
                destructive = true
            ) {
                PiperPrivilegedPreferences.setSystemWrite(this, true)
                systemWrite.isChecked = true
                refreshService()
            }
        }
        workspace.setOnCheckedChangeListener { _, value ->
            PiperPrivilegedPreferences.setWorkspace(this, value)
            if (value) createWorkspace()
        }
        hiddenFiles.setOnCheckedChangeListener { _, value ->
            PiperPrivilegedPreferences.setShowHidden(this, value)
        }
    }

    private fun configureActions() {
        findViewById<View>(R.id.advancedAccessBack).setOnClickListener { finish() }
        refreshButton.setOnClickListener { refreshService() }
        methodRow.setOnClickListener { showMethodPicker() }
        startButton.setOnClickListener { startSelectedMethod() }
        stopButton.setOnClickListener {
            if (operationRunning) return@setOnClickListener
            lifecycleScope.launch {
                setOperationRunning(true)
                try {
                    client.shutdown()
                    latestCapabilities = PiperCapabilities()
                    render(PiperServiceStatus(), latestCapabilities)
                } finally {
                    setOperationRunning(false)
                }
            }
        }
        findViewById<View>(R.id.ppsExportDiagnostics).setOnClickListener { exportDiagnostics() }
    }

    private fun startSelectedMethod() {
        if (operationRunning || isPrivilegedActive()) return
        when (PiperPrivilegedPreferences.method(this)) {
            PiperPrivilegedPreferences.METHOD_SHIZUKU -> showShizukuGuide()
            PiperPrivilegedPreferences.METHOD_SU -> refreshService()
            else -> startAutomaticPiperOs()
        }
    }

    private fun startAutomaticPiperOs() {
        lifecycleScope.launch {
            setOperationRunning(true)
            try {
                stateView.text = getString(R.string.pps_auto_connecting)
                client.refresh()
                val status = awaitSettledStatus()
                if (status != null) latestStatus = status
                if (status?.privilege == PiperPrivilege.ROOT || status?.privilege == PiperPrivilege.SHELL) {
                    refreshStatus()
                } else {
                    refreshStatus()
                    beginNotificationPairing()
                }
            } finally {
                setOperationRunning(false)
            }
        }
    }

    private fun setOperationRunning(running: Boolean) {
        operationRunning = running
        updateControlState()
    }

    private fun isPrivilegedActive(): Boolean =
        latestStatus.state == PiperServiceState.RUNNING &&
            latestStatus.error == PiperError.NONE &&
            latestStatus.privilege != PiperPrivilege.STANDARD

    private fun updateControlState() {
        val active = isPrivilegedActive()
        methodRow.isEnabled = !operationRunning && !active
        methodRow.alpha = if (active) 0.45f else 1f
        startButton.setText(if (active) R.string.pps_active else R.string.pps_start)
        startButton.isEnabled = !operationRunning && !active
        stopButton.isEnabled = !operationRunning && active
        refreshButton.isEnabled = !operationRunning
    }

    private fun beginNotificationPairing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_PAIRING_NOTIFICATIONS
            )
            return
        }
        PiperAdbPairingNotifications.showWaiting(this)
        openWirelessDebuggingSettings()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PAIRING_NOTIFICATIONS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            beginNotificationPairing()
        } else {
            Toast.makeText(this, R.string.pps_notification_permission_required, Toast.LENGTH_LONG).show()
            showPiperPairingDialog()
        }
    }

    private fun showPiperPairingDialog() {
        val codeInput = EditText(this).apply {
            hint = getString(R.string.pps_pairing_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            textSize = 20f
            setPadding(18, 16, 18, 16)
        }
        PiperDialog.showCustom(
            context = this,
            title = getString(R.string.pps_pairing_title),
            message = getString(R.string.pps_pairing_message),
            content = codeInput,
            positiveLabel = getString(R.string.pps_pair),
            neutralLabel = getString(R.string.pps_open_wireless_debugging),
            onNeutral = { openWirelessDebuggingSettings() },
            onPositive = {
                val code = codeInput.text?.toString()?.trim().orEmpty()
                if (!code.matches(Regex("\\d{6}"))) {
                    codeInput.error = getString(R.string.pps_pairing_code_invalid)
                    false
                } else {
                    pairPiperOs(code)
                    true
                }
            }
        )
    }

    private fun pairPiperOs(code: String) {
        lifecycleScope.launch {
            stateView.text = getString(R.string.pps_pairing_discovering)
            val port = PiperAdbBootstrap.discoverPairingPort(this@AdvancedAccessActivity)
                .getOrElse {
                    showPairingFailure(it.message)
                    return@launch
                }
            stateView.text = getString(R.string.pps_pairing_in_progress)
            val paired = PiperAdbBootstrap.pair(this@AdvancedAccessActivity, port, code)
                .getOrElse {
                    showPairingFailure(it.message)
                    return@launch
                }
            if (!paired) {
                showPairingFailure(null)
                return@launch
            }
            Toast.makeText(this@AdvancedAccessActivity, R.string.pps_pairing_success, Toast.LENGTH_SHORT).show()
            delay(500)
            refreshService()
        }
    }

    private fun showPairingFailure(detail: String?) {
        stateView.text = getString(R.string.pps_pairing_failed)
        PiperDialog.showMessage(
            this,
            getString(R.string.pps_pairing_failed),
            detail ?: getString(R.string.pps_pairing_failed_message)
        )
    }

    private fun openWirelessDebuggingSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        Toast.makeText(this, R.string.pps_keep_pairing_screen_open, Toast.LENGTH_LONG).show()
    }

    private fun showShizukuGuide() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.pps_shizuku_title),
            message = if (launch != null) {
                getString(R.string.pps_shizuku_installed_message)
            } else {
                getString(R.string.pps_shizuku_missing_message)
            },
            positiveLabel = if (launch != null) getString(R.string.pps_open_shizuku) else getString(R.string.pps_open_wireless_debugging)
        ) {
            if (launch != null) startActivity(launch) else openWirelessDebuggingSettings()
        }
    }

    private fun showMethodPicker() {
        if (operationRunning || isPrivilegedActive()) return
        val selected = PiperPrivilegedPreferences.method(this)
        PiperActionSheet.showSingleSelect(
            context = this,
            title = getString(R.string.pps_access_method),
            choices = listOf(
                PiperSheetChoice(PiperPrivilegedPreferences.METHOD_AUTO, getString(R.string.pps_method_auto), selected == PiperPrivilegedPreferences.METHOD_AUTO),
                PiperSheetChoice(PiperPrivilegedPreferences.METHOD_SU, getString(R.string.pps_method_su), selected == PiperPrivilegedPreferences.METHOD_SU),
                PiperSheetChoice(PiperPrivilegedPreferences.METHOD_SHIZUKU, getString(R.string.pps_method_shizuku), selected == PiperPrivilegedPreferences.METHOD_SHIZUKU)
            ),
            onSelect = {
                PiperPrivilegedPreferences.setMethod(this, it)
                updateMethodLabel()
                refreshService()
            },
            onRemove = {},
            onAdd = {}
        )
    }

    private fun updateMethodLabel() {
        methodValue.text = when (PiperPrivilegedPreferences.method(this)) {
            PiperPrivilegedPreferences.METHOD_SU -> getString(R.string.pps_method_su)
            PiperPrivilegedPreferences.METHOD_SHIZUKU -> getString(R.string.pps_method_shizuku)
            else -> getString(R.string.pps_method_auto)
        }
    }

    private fun refreshService() {
        if (operationRunning) return
        lifecycleScope.launch {
            setOperationRunning(true)
            try {
                stateView.text = getString(R.string.pps_state_starting)
                client.refresh()
                awaitSettledStatus()?.let { latestStatus = it }
                refreshStatus()
            } finally {
                setOperationRunning(false)
            }
        }
    }

    private suspend fun awaitSettledStatus(): PiperServiceStatus? {
        repeat(60) {
            delay(250)
            val status = client.status()
            if (status?.state != PiperServiceState.STARTING) return status
        }
        return client.status()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val status = client.status() ?: PiperServiceStatus(
                state = PiperServiceState.ERROR,
                error = PiperError.SERVICE_NOT_RUNNING
            )
            val capabilities = client.capabilities() ?: PiperCapabilities()
            latestCapabilities = capabilities
            render(status, capabilities)
        }
    }

    private fun render(status: PiperServiceStatus, capabilities: PiperCapabilities) {
        latestStatus = status
        stateView.text = getString(
            R.string.pps_state_format,
            when (status.state) {
                PiperServiceState.RUNNING -> getString(R.string.pps_running)
                PiperServiceState.STARTING -> getString(R.string.pps_starting)
                PiperServiceState.ERROR -> getString(R.string.error)
                PiperServiceState.STOPPED -> getString(R.string.pps_stopped)
            },
            status.privilege.name
        )
        identityView.text = getString(
            R.string.pps_identity_format,
            status.uid,
            status.pid,
            status.startupMethod,
            status.selinux,
            if (status.startedAt > 0) DateFormat.getDateTimeInstance().format(Date(status.startedAt)) else "-"
        )
        val available = buildList {
            if (capabilities.canAccessAndroidData) add("Android/data")
            if (capabilities.canAccessAndroidObb) add("Android/obb")
            if (capabilities.canReadSystemFiles) add(getString(R.string.pps_system_read_capability))
            if (capabilities.canWriteSystemFiles) add(getString(R.string.pps_system_write_capability))
            if (capabilities.canUsePackageManager) add("Package Manager")
            if (capabilities.canUseAppOps) add("AppOps")
            if (capabilities.canChmod) add("chmod")
            if (capabilities.canChown) add("chown")
        }
        capabilityView.text = getString(
            R.string.pps_capabilities_format,
            available.ifEmpty { listOf(getString(R.string.pps_standard_fallback)) }.joinToString(" · ")
        )
        errorView.visibility = if (status.error == PiperError.NONE) View.GONE else View.VISIBLE
        errorView.text = getString(R.string.pps_error_format, status.error.name, status.detail)
        val canUseRestrictedStorage = capabilities.canAccessAndroidData || capabilities.canAccessAndroidObb
        androidRestricted.isEnabled = canUseRestrictedStorage
        systemFiles.isEnabled = capabilities.canReadSystemFiles
        systemWrite.isEnabled = capabilities.privilege == PiperPrivilege.ROOT
        workspace.isEnabled = true
        hiddenFiles.isEnabled = true
        updateControlState()
    }

    private fun createWorkspace() {
        val root = File(filesDir, "piperos")
        listOf("config", "backups", "scripts", "mounts", "cache", "logs", "workspace")
            .forEach { File(root, it).mkdirs() }
    }

    private fun exportDiagnostics() {
        val source = File(filesDir, "piperos/logs/pps.log")
        if (!source.isFile) {
            Toast.makeText(this, R.string.pps_no_diagnostics, Toast.LENGTH_SHORT).show()
            return
        }
        val output = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PiperOS/pps-diagnostics-${System.currentTimeMillis()}.log"
        )
        runCatching {
            output.parentFile?.mkdirs()
            source.copyTo(output, overwrite = true)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.pps_diagnostics_saved, output.path), Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.error), Toast.LENGTH_LONG).show()
        }
    }
}
