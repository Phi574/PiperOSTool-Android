package com.piperostool.privileged.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButton
import com.piperostool.PiperAutoFont
import com.piperostool.PiperDialog
import com.piperostool.PiperModernUi
import com.piperostool.R
import com.piperostool.privileged.PiperCapabilities
import com.piperostool.privileged.PiperError
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperPrivilegedPreferences
import com.piperostool.privileged.PiperServiceState
import com.piperostool.privileged.PiperServiceStatus
import com.piperostool.privileged.client.PiperPrivilegedClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

class AdvancedAccessActivity : AppCompatActivity() {
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
    private var operationJob: Job? = null

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
            val current = client.status()
            val settled = if (current?.state == PiperServiceState.STARTING || current == null) {
                awaitSettledStatus() ?: current
            } else current
            val capabilities = client.capabilities() ?: PiperCapabilities()
            latestCapabilities = capabilities
            render(settled ?: PiperServiceStatus(
                state = PiperServiceState.ERROR,
                error = PiperError.SERVICE_NOT_RUNNING
            ), capabilities)
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
        methodRow.setOnClickListener { openPiperAdb() }
        startButton.setOnClickListener { openPiperAdb() }
        stopButton.setOnClickListener {
            if (latestStatus.startupMethod == "PIPEROS_ADB") {
                openPiperAdb()
                return@setOnClickListener
            }
            operationJob?.cancel()
            lifecycleScope.launch {
                setOperationRunning(true)
                try {
                    if (!client.shutdown()) {
                        Toast.makeText(this@AdvancedAccessActivity, "Không thể dừng dịch vụ PiperOS", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    latestCapabilities = PiperCapabilities()
                    render(PiperServiceStatus(), latestCapabilities)
                } finally {
                    setOperationRunning(false)
                }
            }
        }
        findViewById<View>(R.id.ppsExportDiagnostics).setOnClickListener { exportDiagnostics() }
    }

    private fun openPiperAdb() {
        if (operationRunning) return
        startActivity(Intent(this, PiperAdbActivity::class.java))
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
        methodRow.isEnabled = !operationRunning
        methodRow.alpha = 1f
        startButton.setText(if (active) R.string.pps_active else R.string.pps_open_adb)
        startButton.isEnabled = !operationRunning && !active
        stopButton.isEnabled = active || latestStatus.state == PiperServiceState.STARTING
        refreshButton.isEnabled = !operationRunning
    }

    private fun updateMethodLabel() {
        methodValue.setText(R.string.pps_shared_access_status)
    }

    private fun refreshService() {
        if (operationRunning) return
        operationJob = lifecycleScope.launch {
            setOperationRunning(true)
            try {
                stateView.text = getString(R.string.pps_state_starting)
                val current = client.status()
                val settled = if (current?.state == PiperServiceState.STARTING || current == null) {
                    awaitSettledStatus() ?: current
                } else {
                    current
                }
                latestStatus = settled ?: PiperServiceStatus(
                    state = PiperServiceState.ERROR,
                    error = PiperError.SERVICE_NOT_RUNNING
                )
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
            if (status != null && status.state != PiperServiceState.STARTING) return status
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
        val identity = getString(
            R.string.pps_identity_format,
            status.uid,
            status.pid,
            status.startupMethod,
            status.selinux,
            if (status.startedAt > 0) DateFormat.getDateTimeInstance().format(Date(status.startedAt)) else "-"
        )
        val shizukuInstalled = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null
        identityView.text = getString(
            R.string.pps_identity_with_shizuku,
            identity,
            getString(if (shizukuInstalled) R.string.pps_shizuku_detected else R.string.pps_shizuku_not_detected)
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
