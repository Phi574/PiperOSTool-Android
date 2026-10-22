package com.piperostool

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.piperostool.privileged.client.PiperPrivilegedClient
import com.piperostool.privileged.PiperAdbClientPermission
import com.piperostool.privileged.PiperError
import com.piperostool.privileged.PiperPrivilege
import com.piperostool.privileged.PiperServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PiperSetEditActivity : AppCompatActivity() {
    private val client by lazy { PiperPrivilegedClient(this) }
    private lateinit var rowsList: RecyclerView
    private lateinit var rowsAdapter: SetEditRowsAdapter
    private lateinit var search: EditText
    private lateinit var permissionSwitch: SwitchMaterial
    private lateinit var permissionStatus: TextView
    private lateinit var permissionDot: TextView
    private lateinit var tableSelector: MaterialButton
    private lateinit var addRowButton: MaterialButton
    private lateinit var actionStatus: TextView
    private var table = PiperSetEditPolicy.Table.SYSTEM
    private var settingsRows: List<Pair<String, String>> = emptyList()
    private var busy = false
    private var adbConnected = false
    private var resumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PiperModernUi.watch(this)
        val screen = buildScreen()
        setContentView(screen)
        ViewCompat.requestApplyInsets(screen)
        selectTable(table, load = false)
        checkPermission(loadInitialRows = true)
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (::permissionSwitch.isInitialized) checkPermission(loadInitialRows = true)
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = layoutInflater.inflate(R.layout.activity_piper_set_edit, null) as LinearLayout
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(18), bars.top + dp(4), bars.right + dp(18), bars.bottom + dp(8))
            insets
        }
        root.findViewById<MaterialButton>(R.id.setEditBack).apply {
            cornerRadius = dp(15)
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this@PiperSetEditActivity))
            backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperSetEditActivity))
            setTextColor(PiperModernUi.textColor(this@PiperSetEditActivity))
            setOnClickListener { finish() }
        }
        root.findViewById<TextView>(R.id.setEditTitle).setTextColor(PiperModernUi.textColor(this))
        root.findViewById<TextView>(R.id.setEditIntroTitle).setTextColor(PiperModernUi.textColor(this))
        root.findViewById<TextView>(R.id.setEditSummary).setTextColor(PiperModernUi.secondaryTextColor(this))
        root.findViewById<TextView>(R.id.setEditPermissionHelp).setTextColor(PiperModernUi.secondaryTextColor(this))
        root.findViewById<TextView>(R.id.setEditRiskNote).apply {
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(PiperModernUi.surfaceColor(this@PiperSetEditActivity))
                setStroke(dp(1), PiperModernUi.borderColor(this@PiperSetEditActivity))
            }
        }
        listOf(R.id.setEditIntroCard, R.id.setEditAccessCard).forEach { id ->
            root.findViewById<MaterialCardView>(id).apply {
                setCardBackgroundColor(PiperModernUi.surfaceColor(this@PiperSetEditActivity))
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(PiperModernUi.borderColor(this@PiperSetEditActivity))
            }
        }

        permissionSwitch = root.findViewById(R.id.setEditPermissionSwitch)
        permissionStatus = root.findViewById(R.id.setEditPermissionStatus)
        permissionDot = root.findViewById(R.id.setEditPermissionDot)
        permissionSwitch.setOnCheckedChangeListener { _, checked -> if (!busy) setPermission(checked) }
        root.findViewById<MaterialButton>(R.id.setEditOpenAdb).apply {
            cornerRadius = dp(13)
            setOnClickListener { startActivity(android.content.Intent(this@PiperSetEditActivity, com.piperostool.privileged.ui.PiperAdbActivity::class.java)) }
        }

        tableSelector = root.findViewById(R.id.setEditTableSelector)
        styleOutlineButton(tableSelector)
        tableSelector.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        tableSelector.setPadding(dp(14), 0, dp(14), 0)
        tableSelector.setOnClickListener { showTableMenu() }
        search = root.findViewById(R.id.setEditSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderRows()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val refreshButton = root.findViewById<MaterialButton>(R.id.setEditRefresh).apply {
            styleOutlineButton(this)
            setOnClickListener { loadRows() }
        }
        addRowButton = root.findViewById<MaterialButton>(R.id.setEditAddRow).apply {
            styleOutlineButton(this)
            setOnClickListener { editRow(null, null) }
        }
        root.findViewById<MaterialButton>(R.id.setEditBackup).apply {
            styleOutlineButton(this)
            setOnClickListener { backupAllTables() }
        }
        root.findViewById<MaterialButton>(R.id.setEditBackups).apply {
            styleOutlineButton(this)
            setOnClickListener { showBackups() }
        }
        root.findViewById<MaterialButton>(R.id.setEditRestoreDefaults).apply {
            cornerRadius = dp(14)
            backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperSetEditActivity))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(Color.rgb(190, 78, 78))
            setTextColor(Color.rgb(190, 78, 78))
            setOnClickListener { restoreSetEditDefaults() }
        }
        actionStatus = root.findViewById(R.id.setEditActionStatus)
        actionStatus.setTextColor(PiperModernUi.secondaryTextColor(this))
        root.findViewById<TextView>(R.id.setEditKeyHeader).setTextColor(PiperModernUi.secondaryTextColor(this))
        root.findViewById<TextView>(R.id.setEditValueHeader).setTextColor(PiperModernUi.secondaryTextColor(this))
        rowsAdapter = SetEditRowsAdapter()
        rowsList = root.findViewById<RecyclerView>(R.id.setEditRows).apply {
            layoutManager = LinearLayoutManager(this@PiperSetEditActivity)
            adapter = rowsAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(true)
            itemAnimator = null
            contentDescription = getString(R.string.setedit_search_hint)
        }
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun styleOutlineButton(button: MaterialButton) {
        button.isAllCaps = false
        button.textSize = 12f
        button.minHeight = dp(44)
        button.insetTop = 0
        button.insetBottom = 0
        button.cornerRadius = dp(14)
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this))
        button.strokeWidth = dp(1)
        button.strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this))
        button.setTextColor(PiperModernUi.textColor(this))
    }
    private fun selectTable(selected: PiperSetEditPolicy.Table, load: Boolean = true) {
        table = selected
        tableSelector.text = "${tableTitle(selected)}   ·   Chọn nhóm  ⌄"
        addRowButton.isEnabled = selected.editable
        addRowButton.alpha = if (selected.editable) 1f else 0.48f
        actionStatus.text = when {
            selected.editable -> getString(R.string.setedit_settings_editable)
            else -> getString(R.string.setedit_process_read_only)
        }
        search.setText("")
        if (load) loadRows()
    }

    private fun showTableMenu() {
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(4)) }
        var dialog: android.app.Dialog? = null
        PiperSetEditPolicy.Table.entries.forEach { section ->
            menu.addView(actionButton(R.string.setedit_table_option) {
                dialog?.dismiss()
                selectTable(section)
            }.apply {
                text = tableTitle(section)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(12), 0, dp(12), 0)
                if (section == table) setTextColor(PiperModernUi.accentColor(this@PiperSetEditActivity))
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(3) })
        }
        dialog = PiperDialog.showCustom(
            context = this,
            title = getString(R.string.setedit_choose_table),
            content = menu,
            positiveLabel = getString(R.string.cancel),
            negativeLabel = null,
            onPositive = { true }
        )
    }

    private fun tableTitle(value: PiperSetEditPolicy.Table): String = getString(when (value) {
        PiperSetEditPolicy.Table.SYSTEM -> R.string.setedit_table_system
        PiperSetEditPolicy.Table.SECURE -> R.string.setedit_table_secure
        PiperSetEditPolicy.Table.GLOBAL -> R.string.setedit_table_global
        PiperSetEditPolicy.Table.ANDROID_PROPERTIES -> R.string.setedit_table_android_properties
        PiperSetEditPolicy.Table.JAVA_PROPERTIES -> R.string.setedit_table_java_properties
        PiperSetEditPolicy.Table.LINUX_ENVIRONMENT -> R.string.setedit_table_linux_environment
    })

    private fun loadRows() {
        if (busy) return
        setBusy(true)
        val requestedTable = table
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                when (requestedTable) {
                    PiperSetEditPolicy.Table.JAVA_PROPERTIES -> System.getProperties().entries
                        .map { it.key.toString() to it.value.toString() }.sortedBy { it.first.lowercase() }
                    PiperSetEditPolicy.Table.LINUX_ENVIRONMENT -> System.getenv().entries
                        .map { it.key to it.value }.sortedBy { it.first.lowercase() }
                    else -> {
                        if (!hasShellAccess()) return@withContext null
                        val cmd = if (requestedTable == PiperSetEditPolicy.Table.ANDROID_PROPERTIES) "getprop" else "settings list ${requestedTable.commandName}"
                        val result = client.newProcess(cmd, timeoutMs = 15_000L)
                        if (result == null || result.exitCode != 0) return@withContext null
                        if (requestedTable == PiperSetEditPolicy.Table.ANDROID_PROPERTIES) parseProperties(result.output)
                        else PiperSetEditPolicy.parseSettingsList(result.output)
                    }
                }
            } }
            setBusy(false)
            if (result.isFailure) {
                settingsRows = emptyList()
                actionStatus.text = result.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                    ?: getString(R.string.setedit_command_failed)
            } else if (result.getOrNull() == null) {
                settingsRows = emptyList()
                actionStatus.text = getString(R.string.setedit_need_adb)
            } else {
                val loaded = result.getOrNull().orEmpty()
                settingsRows = loaded
                actionStatus.text = getString(R.string.setedit_row_count, loaded.size)
            }
            renderRows()
        }
    }

    private suspend fun hasShellAccess(): Boolean {
        if (!isPiperAdbConnected()) return false
        return client.clientPermissions()[PiperAdbClientPermission.SHELL_COMMANDS] == true
    }

    private suspend fun isPiperAdbConnected(): Boolean {
        if (!client.connect()) return false
        val status = client.status() ?: return false
        return status.state == PiperServiceState.RUNNING &&
            status.privilege == PiperPrivilege.SHELL &&
            status.startupMethod == "PIPEROS_ADB" &&
            status.error == PiperError.NONE
    }

    private fun checkPermission(loadInitialRows: Boolean = false, afterCheck: (() -> Unit)? = null) {
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val isConnected = isPiperAdbConnected()
                val hasPermission = isConnected &&
                    client.clientPermissions()[PiperAdbClientPermission.SHELL_COMMANDS] == true
                isConnected to hasPermission
            } }.getOrDefault(false to false)
            val connected = result.first
            val allowed = result.second
            adbConnected = connected
            permissionSwitch.setOnCheckedChangeListener(null)
            permissionSwitch.isChecked = connected && allowed
            permissionSwitch.isEnabled = connected && !busy
            permissionSwitch.setOnCheckedChangeListener { _, checked -> if (!busy) setPermission(checked) }
            permissionStatus.text = getString(when {
                !connected -> R.string.setedit_need_adb
                allowed -> R.string.setedit_permission_granted
                else -> R.string.setedit_permission_denied
            })
            val dotColor = ContextCompat.getColor(this@PiperSetEditActivity, if (connected && allowed) R.color.remote_accent else android.R.color.holo_red_light)
            permissionStatus.setTextColor(dotColor)
            permissionDot.setTextColor(dotColor)
            if (loadInitialRows) {
                if (connected && allowed) loadRows()
                else {
                    settingsRows = emptyList()
                    actionStatus.text = getString(R.string.setedit_need_adb)
                    renderRows()
                }
            }
            afterCheck?.invoke()
        }
    }

    private fun setPermission(enabled: Boolean) {
        if (!adbConnected) {
            checkPermission()
            return
        }
        if (enabled) {
            PiperDialog.showConfirm(
                context = this,
                title = getString(R.string.setedit_permission_confirm_title),
                message = getString(R.string.setedit_permission_confirm_message),
                positiveLabel = getString(R.string.setedit_confirm),
                negativeLabel = getString(R.string.cancel)
            ) { updatePermission(true) }
        } else updatePermission(false)
    }

    private fun updatePermission(enabled: Boolean) {
        setBusy(true)
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                if (!isPiperAdbConnected()) return@withContext false
                client.setClientPermission(PiperAdbClientPermission.SHELL_COMMANDS, enabled)
            }
            setBusy(false)
            checkPermission {
                actionStatus.text = getString(if (changed) R.string.setedit_permission_updated else R.string.setedit_adb_unavailable)
                if (changed && enabled) loadRows()
            }
        }
    }

    private fun renderRows() {
        if (!::rowsAdapter.isInitialized) return
        val query = search.text?.toString()?.trim().orEmpty()
        val filtered = settingsRows.filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
        if (filtered.isEmpty()) {
            rowsAdapter.submit(emptyList())
            if (settingsRows.isNotEmpty()) actionStatus.text = getString(R.string.setedit_no_rows)
        } else {
            rowsAdapter.submit(filtered)
            actionStatus.text = getString(R.string.setedit_row_count, filtered.size)
        }
    }

    private fun showRow(key: String, value: String) {
        lateinit var rowDialog: android.app.Dialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        if (table.editable) {
            content.addView(actionButton(R.string.setedit_edit) {
                rowDialog.dismiss()
                editRow(key, value)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
            content.addView(actionButton(R.string.setedit_delete) {
                rowDialog.dismiss()
                deleteRow(key, value)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        }
        rowDialog = PiperDialog.showCustom(
            context = this,
            title = key,
            message = value.ifBlank { "(empty)" },
            content = content,
            positiveLabel = getString(android.R.string.ok),
            negativeLabel = null,
            onPositive = { true }
        )
    }

    private fun editRow(key: String?, oldValue: String?) {
        if (!table.editable) return
        val keyInput = EditText(this).apply { hint = getString(R.string.setedit_key_hint); setText(key.orEmpty()); isEnabled = key == null }
        val valueInput = EditText(this).apply { hint = getString(R.string.setedit_value_hint); setText(oldValue.orEmpty()); minLines = 2; gravity = Gravity.TOP }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(4))
            addView(keyInput)
            addView(valueInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        lateinit var editDialog: android.app.Dialog
        editDialog = PiperDialog.showCustom(
            context = this,
            title = getString(if (key == null) R.string.setedit_add_row else R.string.setedit_edit_title),
            content = form,
            positiveLabel = getString(R.string.save),
            negativeLabel = getString(R.string.cancel),
            onPositive = {
                val targetKey = keyInput.text.toString().trim()
                val targetValue = valueInput.text.toString()
                if (!PiperSetEditPolicy.isValidKey(targetKey)) {
                    Toast.makeText(this, R.string.setedit_invalid_key, Toast.LENGTH_LONG).show()
                    false
                } else {
                    editDialog.dismiss()
                    confirmSave(targetKey, oldValue, targetValue) { saveRow(targetKey, targetValue, oldValue) }
                    false
                }
            }
        )
    }

    private fun deleteRow(key: String, oldValue: String) {
        confirmRisk(key, deleting = true) {
            PiperDialog.showConfirm(
                context = this,
                title = getString(R.string.setedit_delete_title),
                message = getString(R.string.setedit_delete_message, key),
                positiveLabel = getString(R.string.setedit_delete),
                destructive = true
            ) { mutateSetting(key, oldValue, null) }
        }
    }

    private fun confirmRisk(key: String, deleting: Boolean = false, action: () -> Unit) {
        if (PiperSetEditPolicy.risk(table, key) == PiperSetEditPolicy.Risk.RELATIVE_SAFE) {
            action(); return
        }
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.setedit_unsafe_title),
            message = getString(if (deleting) R.string.setedit_unsafe_delete_message else R.string.setedit_unsafe_edit_message, table.displayName, key),
            positiveLabel = getString(R.string.setedit_continue_unsafe),
            destructive = true,
            onConfirm = action
        )
    }

    private fun confirmSave(key: String, oldValue: String?, newValue: String, action: () -> Unit) {
        if (oldValue == newValue) {
            PiperDialog.showMessage(
                context = this,
                title = getString(R.string.setedit_save_title),
                message = getString(R.string.setedit_no_change)
            )
            return
        }
        val risky = PiperSetEditPolicy.risk(table, key) != PiperSetEditPolicy.Risk.RELATIVE_SAFE
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.setedit_save_title),
                message = getString(if (risky) R.string.setedit_confirm_save_risky else R.string.setedit_confirm_save, table.displayName, key),
            positiveLabel = getString(R.string.save),
            destructive = risky
        ) { action() }
    }

    private fun saveRow(key: String, value: String, oldValue: String?) = mutateSetting(key, oldValue, value)

    private fun mutateSetting(key: String, oldValue: String?, newValue: String?) {
        val requestedTable = table
        lifecycleScope.launch {
            setBusy(true)
            val success = withContext(Dispatchers.IO) {
                if (!hasShellAccess()) return@withContext false
                pushBackup(JSONObject().apply {
                    put("kind", "row"); put("table", requestedTable.commandName); put("key", key)
                    put("oldValue", oldValue ?: JSONObject.NULL)
                })
                // Persist the first value before changing the device. If the app or device
                // stops mid-operation, the user can still roll this key back later.
                recordOriginalValue(requestedTable.commandName, key, oldValue)
                val operation = if (newValue == null) "delete" else "put ${PiperSetEditPolicy.shellQuote(newValue)}"
                val result = client.newProcess("settings $operation ${requestedTable.commandName} ${PiperSetEditPolicy.shellQuote(key)}", timeoutMs = 15_000L)
                result?.exitCode == 0
            }
            setBusy(false)
            actionStatus.text = getString(if (success) R.string.setedit_saved_undo_available else R.string.setedit_command_failed)
            if (success) loadRows()
        }
    }

    private fun backupAllTables() {
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.setedit_backup_title),
            message = getString(R.string.setedit_backup_message),
            positiveLabel = getString(R.string.setedit_backup_all),
            onConfirm = {
                lifecycleScope.launch {
                    setBusy(true)
                    val backup = withContext(Dispatchers.IO) { captureSnapshot() }
                    if (backup != null) {
                        withContext(Dispatchers.IO) { pushBackup(backup) }
                        actionStatus.text = getString(R.string.setedit_backup_saved)
                    } else actionStatus.text = getString(R.string.setedit_adb_unavailable)
                    setBusy(false)
                }
            }
        )
    }

    private suspend fun captureSnapshot(): JSONObject? {
        if (!hasShellAccess()) return null
        val tables = JSONObject()
        PiperSetEditPolicy.Table.entries.filter { it.editable }.forEach { section ->
            val result = client.newProcess("settings list ${section.commandName}", timeoutMs = 15_000L)
            if (result?.exitCode != 0) return null
            val values = JSONObject()
            PiperSetEditPolicy.parseSettingsList(result.output).forEach { (key, value) -> values.put(key, value) }
            tables.put(section.commandName, values)
        }
        return JSONObject().put("kind", "full").put("tables", tables)
    }

    private fun showBackups() {
        val backups = readSnapshots().sortedByDescending { it.optLong("created") }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.setedit_backups_description)
            textSize = 13f
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        var listDialog: android.app.Dialog? = null
        if (backups.isEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.setedit_no_backup)
                textSize = 14f
                setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
                setPadding(0, dp(16), 0, dp(16))
            })
        } else {
            backups.forEach { backup ->
                val summary = backupSummary(backup)
                val item = card()
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                body.addView(TextView(this).apply {
                    text = backup.optString("label").ifBlank { getString(R.string.setedit_backup_snapshot) }
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(PiperModernUi.textColor(this@PiperSetEditActivity))
                })
                body.addView(TextView(this).apply {
                    text = "${formatBackupDate(backup.optLong("created"))}  •  $summary"
                    textSize = 11f
                    setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
                    setPadding(0, dp(3), 0, 0)
                })
                item.addView(body)
                item.isClickable = true
                item.isFocusable = true
                item.setOnClickListener {
                    listDialog?.dismiss()
                    showBackupDetails(backup)
                }
                content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }
        content.addView(actionButton(R.string.cancel) { listDialog?.dismiss() }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        val dialog = PiperDialog.createContent(this, content)
        listDialog = dialog
        dialog.show()
    }

    private fun showBackupDetails(backup: JSONObject) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val timestamp = formatBackupDate(backup.optLong("created"))
        content.addView(TextView(this).apply {
            text = timestamp
            textSize = 12f
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val details = TextView(this).apply {
            text = backupDetails(backup)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(PiperModernUi.textColor(this@PiperSetEditActivity))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = getDrawable(R.drawable.bg_auth_panel)
        }
        content.addView(details, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val detailDialog = PiperDialog.createContent(this, content)
        actions.addView(actionButton(R.string.setedit_restore) {
            detailDialog.dismiss()
            confirmRestoreBackup(backup)
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        actions.addView(actionButton(R.string.setedit_backup_delete) {
            detailDialog.dismiss()
            PiperDialog.showConfirm(
                context = this,
                title = getString(R.string.setedit_backup_delete_title),
                message = getString(R.string.setedit_backup_delete_message),
                positiveLabel = getString(R.string.setedit_backup_delete),
                destructive = true
            ) {
                removeBackup(backup)
                showBackups()
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2))
        content.addView(actionButton(R.string.cancel) { detailDialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        detailDialog.show()
    }

    private fun confirmRestoreBackup(backup: JSONObject) {
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.setedit_restore_title),
            message = getString(R.string.setedit_restore_message),
            positiveLabel = getString(R.string.setedit_restore),
            destructive = true
        ) {
            lifecycleScope.launch {
                setBusy(true)
                val outcome = withContext(Dispatchers.IO) { restoreBackup(backup) }
                setBusy(false)
                val message = when {
                    outcome == null -> getString(R.string.setedit_adb_unavailable)
                    outcome.restored == 0 && outcome.failed == 0 -> getString(R.string.setedit_restore_no_differences)
                    outcome.failed == 0 -> getString(R.string.setedit_restore_diff_done, outcome.restored)
                    else -> getString(R.string.setedit_restore_diff_partial, outcome.restored, outcome.failed)
                }
                actionStatus.text = message
                PiperDialog.showMessage(this@PiperSetEditActivity, getString(R.string.setedit_restore), message)
                if ((outcome?.restored ?: 0) > 0) loadRows()
            }
        }
    }

    private fun backupSummary(backup: JSONObject): String {
        if (backup.optString("kind") == "row") return "${backup.optString("table")} / ${backup.optString("key")}"
        val tables = backup.optJSONObject("tables") ?: return getString(R.string.setedit_backup_empty)
        val counts = listOf("system", "secure", "global").mapNotNull { name ->
            tables.optJSONObject(name)?.let { "$name ${it.length()}" }
        }
        return getString(R.string.setedit_backup_key_count, counts.sumOf { it.substringAfterLast(' ').toIntOrNull() ?: 0 }) +
            if (counts.isEmpty()) "" else "  •  ${counts.joinToString(" / ")}"
    }

    private fun backupDetails(backup: JSONObject): String {
        if (backup.optString("kind") == "row") {
            val old = backup.opt("oldValue")
            return "${backup.optString("table")} / ${backup.optString("key")}\n${old?.takeUnless { it == JSONObject.NULL } ?: getString(R.string.setedit_backup_missing_value)}"
        }
        val tables = backup.optJSONObject("tables") ?: return getString(R.string.setedit_backup_empty)
        return buildString {
            listOf("system", "secure", "global").forEach { tableName ->
                val values = tables.optJSONObject(tableName) ?: return@forEach
                appendLine("[$tableName] (${values.length()})")
                values.keys().asSequence().sorted().forEach { key -> appendLine("$key = ${values.optString(key)}") }
                appendLine()
            }
        }.ifBlank { getString(R.string.setedit_backup_empty) }
    }

    private fun formatBackupDate(timestamp: Long): String =
        if (timestamp <= 0) getString(R.string.setedit_backup_old) else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timestamp))

    private fun removeBackup(backup: JSONObject) {
        val id = backup.optString("id")
        val isSnapshot = backup.optString("kind") == "full"
        val file = File(filesDir, if (isSnapshot) SNAPSHOT_FILE else BACKUP_FILE)
        val remaining = readBackupFile(file).filterNot { candidate ->
            if (id.isNotBlank()) candidate.optString("id") == id
            else candidate.optLong("created") == backup.optLong("created") && candidate.optString("kind") == backup.optString("kind")
        }
        writeBackupFile(file, remaining)
        if (isSnapshot) {
            val legacy = File(filesDir, BACKUP_FILE)
            writeBackupFile(legacy, readBackupFile(legacy).filterNot { candidate ->
                candidate.optString("kind") == "full" && if (id.isNotBlank()) candidate.optString("id") == id
                else candidate.optLong("created") == backup.optLong("created")
            })
        }
    }

    private fun readSnapshots(): List<JSONObject> =
        (readBackupFile(File(filesDir, SNAPSHOT_FILE)) + readBackupFile(File(filesDir, BACKUP_FILE)).filter { it.optString("kind") == "full" })

    private fun restoreSetEditDefaults() {
        val changedRows = originalValuesCount(readOriginalValues())
        if (changedRows == 0) {
            PiperDialog.showMessage(
                context = this,
                title = getString(R.string.setedit_restore_defaults),
                message = getString(R.string.setedit_restore_defaults_empty)
            )
            return
        }
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.setedit_restore_defaults_title),
            message = getString(R.string.setedit_restore_defaults_message, changedRows),
            positiveLabel = getString(R.string.setedit_restore_defaults),
            destructive = true
        ) {
            lifecycleScope.launch {
                setBusy(true)
                val outcome = withContext(Dispatchers.IO) { restoreRecordedValues() }
                setBusy(false)
                val resultText = when {
                    outcome == null -> getString(R.string.setedit_adb_unavailable)
                    outcome.restored == 0 && outcome.failed == 0 -> getString(R.string.setedit_restore_defaults_empty)
                    outcome.failed == 0 -> getString(R.string.setedit_restore_defaults_done, outcome.restored)
                    else -> getString(R.string.setedit_restore_defaults_partial, outcome.restored, outcome.failed)
                }
                PiperDialog.showMessage(this@PiperSetEditActivity, getString(R.string.setedit_restore_defaults), resultText)
                if ((outcome?.restored ?: 0) > 0) loadRows()
            }
        }
    }

    private suspend fun restoreRecordedValues(): RestoreOutcome? {
        if (!hasShellAccess()) return null
        val originalValues = readOriginalValues()
        val total = originalValuesCount(originalValues)
        if (total == 0) return RestoreOutcome(0, 0)
        var restored = 0
        var failed = 0
        for (tableName in listOf("system", "secure", "global")) {
            val values = originalValues.optJSONObject(tableName) ?: continue
            val keys = values.keys().asSequence().toList()
            for (key in keys) {
                if (!PiperSetEditPolicy.isValidKey(key)) {
                    failed++
                    continue
                }
                val oldValue = values.opt(key)
                val command = if (oldValue == JSONObject.NULL || oldValue == null) {
                    "settings delete $tableName ${PiperSetEditPolicy.shellQuote(key)}"
                } else {
                    "settings put $tableName ${PiperSetEditPolicy.shellQuote(key)} ${PiperSetEditPolicy.shellQuote(oldValue.toString())}"
                }
                val okay = client.newProcess(command, timeoutMs = 15_000L)?.exitCode == 0
                if (okay) {
                    values.remove(key)
                    if (values.length() == 0) originalValues.remove(tableName)
                    writeOriginalValues(originalValues)
                    restored++
                } else failed++
            }
        }
        return RestoreOutcome(restored, failed)
    }

    private suspend fun restoreBackup(backup: JSONObject): RestoreOutcome? {
        if (!hasShellAccess()) return null
        if (backup.optString("kind") == "row") {
            val old = backup.opt("oldValue")
            val key = backup.optString("key")
            val tableName = backup.optString("table")
            if (!PiperSetEditPolicy.isValidKey(key) || tableName !in setOf("system", "secure", "global")) return RestoreOutcome(0, 1)
            val command = if (old == JSONObject.NULL || old == null) "settings delete $tableName ${PiperSetEditPolicy.shellQuote(key)}"
            else "settings put $tableName ${PiperSetEditPolicy.shellQuote(key)} ${PiperSetEditPolicy.shellQuote(old.toString())}"
            return if (client.newProcess(command, timeoutMs = 15_000L)?.exitCode == 0) RestoreOutcome(1, 0) else RestoreOutcome(0, 1)
        }
        if (backup.optString("kind") != "full") return RestoreOutcome(0, 1)
        val tables = backup.optJSONObject("tables") ?: return RestoreOutcome(0, 1)

        // Save the current state before rollback so the user can reverse a mistaken restore.
        captureSnapshot()?.let { current ->
            current.put("label", getString(R.string.setedit_backup_before_restore))
            pushBackup(current)
        }

        var restored = 0
        var failed = 0
        for (tableName in listOf("system", "secure", "global")) {
            val savedObject = tables.optJSONObject(tableName) ?: continue
            val currentResult = client.newProcess("settings list $tableName", timeoutMs = 15_000L)
            if (currentResult?.exitCode != 0) {
                failed += savedObject.length()
                continue
            }
            val saved = savedObject.keys().asSequence().associateWith { savedObject.optString(it) }
            val current = PiperSetEditPolicy.parseSettingsList(currentResult.output).toMap()
            val diff = PiperSetEditPolicy.diffSnapshot(saved, current)
            diff.valuesToRestore.forEach { (key, value) ->
                if (!PiperSetEditPolicy.isValidKey(key)) {
                    failed++
                } else {
                    val result = client.newProcess(
                        "settings put $tableName ${PiperSetEditPolicy.shellQuote(key)} ${PiperSetEditPolicy.shellQuote(value)}",
                        timeoutMs = 15_000L
                    )
                    if (result?.exitCode == 0) restored++ else failed++
                }
            }
            diff.keysToDelete.forEach { key ->
                if (!PiperSetEditPolicy.isValidKey(key)) {
                    failed++
                } else {
                    val result = client.newProcess("settings delete $tableName ${PiperSetEditPolicy.shellQuote(key)}", timeoutMs = 15_000L)
                    if (result?.exitCode == 0) restored++ else failed++
                }
            }
        }
        return RestoreOutcome(restored, failed)
    }

    private fun pushBackup(value: JSONObject) {
        val snapshots = value.optString("kind") == "full"
        val path = File(filesDir, if (snapshots) SNAPSHOT_FILE else BACKUP_FILE)
        val backups = readBackupFile(path).toMutableList()
        backups += JSONObject(value.toString())
            .put("id", java.util.UUID.randomUUID().toString())
            .put("created", System.currentTimeMillis())
        writeBackupFile(path, backups.takeLast(if (snapshots) MAX_SNAPSHOTS else MAX_BACKUPS))
    }

    private fun readOriginalValues(): JSONObject = runCatching {
        val path = File(filesDir, ORIGINAL_VALUES_FILE)
        if (path.isFile) JSONObject(path.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun writeOriginalValues(values: JSONObject) {
        File(filesDir, ORIGINAL_VALUES_FILE).writeText(values.toString())
    }

    private fun recordOriginalValue(tableName: String, key: String, oldValue: String?) {
        val originals = readOriginalValues()
        val tableValues = originals.optJSONObject(tableName) ?: JSONObject()
        if (!tableValues.has(key)) tableValues.put(key, oldValue ?: JSONObject.NULL)
        originals.put(tableName, tableValues)
        writeOriginalValues(originals)
    }

    private fun originalValuesCount(values: JSONObject): Int =
        listOf("system", "secure", "global").sumOf { values.optJSONObject(it)?.length() ?: 0 }

    private fun readBackupFile(path: File): List<JSONObject> = runCatching {
        if (!path.isFile) return emptyList()
        val array = JSONArray(path.readText())
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun writeBackupFile(path: File, backups: List<JSONObject>) {
        path.writeText(JSONArray().apply { backups.forEach(::put) }.toString())
    }

    private fun parseProperties(output: String): List<Pair<String, String>> = output.lineSequence().mapNotNull { line ->
        val start = line.indexOf('['); val end = line.indexOf("]: ")
        if (start < 0 || end <= start) null else line.substring(start + 1, end) to line.substring(end + 3).removeSurrounding("[", "]")
    }.sortedBy { it.first.lowercase() }.toList()

    private fun actionButton(label: Int, action: () -> Unit) = MaterialButton(this).apply {
        text = getString(label)
        isAllCaps = false
        textSize = 12f
        minHeight = dp(44)
        insetTop = 0; insetBottom = 0
        cornerRadius = dp(14)
        if (label == R.string.setedit_restore_defaults) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperSetEditActivity))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(Color.rgb(190, 78, 78))
            setTextColor(Color.rgb(190, 78, 78))
        }
        setOnClickListener { action() }
    }

    private fun card() = MaterialCardView(this).apply {
        setCardBackgroundColor(PiperModernUi.surfaceColor(this@PiperSetEditActivity))
        radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
        setStrokeColor(PiperModernUi.borderColor(this@PiperSetEditActivity))
    }

    private fun setBusy(value: Boolean) {
        busy = value
        permissionSwitch.isEnabled = !value && adbConnected
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class RestoreOutcome(val restored: Int, val failed: Int)

    private inner class SetEditRowsAdapter : RecyclerView.Adapter<SetEditRowsAdapter.Holder>() {
        private var rows: List<Pair<String, String>> = emptyList()

        inner class Holder(val item: MaterialCardView, val line: LinearLayout, val key: TextView, val value: TextView) : RecyclerView.ViewHolder(item)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val key = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(PiperModernUi.secondaryTextColor(parent.context))
                setPadding(dp(10), dp(12), dp(8), dp(12))
            }
            val value = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(PiperModernUi.textColor(parent.context))
                setPadding(dp(8), dp(12), dp(10), dp(12))
            }
            val line = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(key, LinearLayout.LayoutParams(0, -2, 0.46f))
                addView(value, LinearLayout.LayoutParams(0, -2, 0.54f))
            }
            val item = MaterialCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                setCardBackgroundColor(PiperModernUi.surfaceColor(parent.context))
                radius = dp(13).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(PiperModernUi.borderColor(parent.context))
                isClickable = true
                isFocusable = true
                addView(line)
            }
            return Holder(item, line, key, value).also { holder ->
                item.setOnClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION && rows.isNotEmpty()) {
                        showRow(rows[index].first, rows[index].second)
                    }
                }
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (rows.isEmpty()) {
                holder.key.text = getString(R.string.setedit_no_rows)
                holder.key.gravity = Gravity.CENTER
                holder.key.layoutParams = LinearLayout.LayoutParams(-1, -2)
                holder.value.visibility = View.GONE
                holder.item.isClickable = false
                return
            }
            val (key, value) = rows[position]
            holder.key.layoutParams = LinearLayout.LayoutParams(0, -2, 0.46f)
            holder.value.visibility = View.VISIBLE
            holder.key.gravity = Gravity.START
            holder.item.isClickable = true
            holder.key.text = key
            holder.value.text = value.ifBlank { "(empty)" }
        }

        override fun getItemCount(): Int = rows.size.coerceAtLeast(1)

        fun submit(newRows: List<Pair<String, String>>) {
            rows = newRows
            notifyDataSetChanged()
        }
    }

    companion object {
        private const val BACKUP_FILE = "piperos_setedit_backups.json"
        private const val SNAPSHOT_FILE = "piperos_setedit_snapshots.json"
        private const val ORIGINAL_VALUES_FILE = "piperos_setedit_original_values.json"
        private const val MAX_BACKUPS = 50
        private const val MAX_SNAPSHOTS = 20
    }
}
