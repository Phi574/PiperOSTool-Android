package com.piperostool

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
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
    private lateinit var tableButtons: LinearLayout
    private lateinit var actionStatus: TextView
    private var table = PiperSetEditPolicy.Table.SYSTEM
    private var settingsRows: List<Pair<String, String>> = emptyList()
    private var busy = false
    private var adbConnected = false
    private var resumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PiperModernUi.watch(this)
        setContentView(buildScreen())
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@PiperSetEditActivity, R.color.remote_page_background))
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "‹   ${getString(R.string.back)}"
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
            textSize = 15f
            setPadding(0, dp(4), 0, dp(14))
            setOnClickListener { finish() }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.setedit_title)
            setTextColor(PiperModernUi.textColor(this@PiperSetEditActivity))
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.setedit_summary)
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
            textSize = 13f
            setPadding(0, dp(5), 0, dp(16))
        })

        val accessCard = card()
        val accessBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val permissionLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        permissionDot = TextView(this).apply { text = "●"; textSize = 17f; setPadding(0, 0, dp(8), 0) }
        permissionStatus = TextView(this).apply {
            text = getString(R.string.setedit_permission_checking)
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
            textSize = 13f
        }
        permissionLine.addView(permissionDot)
        permissionLine.addView(permissionStatus, LinearLayout.LayoutParams(0, -2, 1f))
        permissionSwitch = SwitchMaterial(this).apply {
            text = getString(R.string.setedit_permission_switch)
            textSize = 12f
            setOnCheckedChangeListener { _, checked ->
                if (!busy) setPermission(checked)
            }
        }
        accessBody.addView(permissionLine)
        accessBody.addView(permissionSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        accessBody.addView(TextView(this).apply {
            text = getString(R.string.setedit_permission_help)
            textSize = 12f
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
            setPadding(0, dp(5), 0, dp(8))
        })
        accessBody.addView(MaterialButton(this@PiperSetEditActivity).apply {
            text = getString(R.string.setedit_open_adb)
            isAllCaps = false
            setOnClickListener { startActivity(android.content.Intent(this@PiperSetEditActivity, com.piperostool.privileged.ui.PiperAdbActivity::class.java)) }
        })
        accessCard.addView(accessBody)
        content.addView(accessCard)

        val choices = listOf(
            PiperSetEditPolicy.Table.SYSTEM, PiperSetEditPolicy.Table.SECURE,
            PiperSetEditPolicy.Table.GLOBAL, PiperSetEditPolicy.Table.ANDROID_PROPERTIES,
            PiperSetEditPolicy.Table.JAVA_PROPERTIES, PiperSetEditPolicy.Table.LINUX_ENVIRONMENT
        )
        tableButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(5))
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tableButtons)
        })
        choices.forEach { section ->
            tableButtons.addView(MaterialButton(this).apply {
                text = section.displayName
                isAllCaps = false
                textSize = 11f
                insetTop = 0
                insetBottom = 0
                setOnClickListener { selectTable(section) }
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(6) })
        }

        search = EditText(this).apply {
            hint = getString(R.string.setedit_search_hint)
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(14), 0, dp(14), 0)
            background = getDrawable(R.drawable.bg_auth_input)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderRows()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(actionButton(R.string.setedit_refresh) { loadRows() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(4) })
        actions.addView(actionButton(R.string.setedit_add_row) { editRow(null, null) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        actions.addView(actionButton(R.string.setedit_backup_all) { backupAllTables() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(actionButton(R.string.setedit_undo) { restoreLatestBackup() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(5) })
        content.addView(actionButton(R.string.setedit_restore_defaults) { restoreSetEditDefaults() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(5) })

        actionStatus = TextView(this).apply {
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperSetEditActivity))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(5))
        }
        content.addView(actionStatus)
        content.addView(TextView(this).apply {
            text = getString(R.string.setedit_risk_note)
            setTextColor(Color.parseColor("#FFCC80"))
            textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(R.drawable.bg_auth_panel)
        })
        rowsAdapter = SetEditRowsAdapter()
        rowsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PiperSetEditActivity)
            adapter = rowsAdapter
            isNestedScrollingEnabled = true
            setHasFixedSize(true)
            itemAnimator = null
            contentDescription = getString(R.string.setedit_search_hint)
        }
        content.addView(rowsList, LinearLayout.LayoutParams(-1, dp(460)).apply { topMargin = dp(10) })
        root.fitsSystemWindows = true
        return root
    }

    private fun selectTable(selected: PiperSetEditPolicy.Table, load: Boolean = true) {
        table = selected
        tableButtons.children().forEachIndexed { index, view ->
            (view as? MaterialButton)?.alpha = if (index == selected.ordinal) 1f else 0.72f
        }
        actionStatus.text = when {
            selected.editable -> getString(R.string.setedit_settings_editable)
            else -> getString(R.string.setedit_process_read_only)
        }
        search.setText("")
        if (load) loadRows()
    }

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
                    val backup = withContext(Dispatchers.IO) {
                        if (!hasShellAccess()) return@withContext null
                        val tables = JSONObject()
                        PiperSetEditPolicy.Table.entries.filter { it.editable }.forEach { section ->
                            val result = client.newProcess("settings list ${section.commandName}", timeoutMs = 15_000L)
                            if (result?.exitCode != 0) return@withContext null
                            val values = JSONObject()
                            PiperSetEditPolicy.parseSettingsList(result.output).forEach { (k, v) -> values.put(k, v) }
                            tables.put(section.commandName, values)
                        }
                        JSONObject().put("kind", "full").put("tables", tables)
                    }
                    if (backup != null) {
                        pushBackup(backup)
                        actionStatus.text = getString(R.string.setedit_backup_saved)
                    } else actionStatus.text = getString(R.string.setedit_adb_unavailable)
                    setBusy(false)
                }
            }
        )
    }

    private fun restoreLatestBackup() {
        val latest = readBackups().lastOrNull()
        if (latest == null) {
            Toast.makeText(this, R.string.setedit_no_backup, Toast.LENGTH_SHORT).show(); return
        }
        PiperDialog.showConfirm(
            context = this,
            title = getString(R.string.setedit_restore_title),
            message = getString(R.string.setedit_restore_message),
            positiveLabel = getString(R.string.setedit_restore),
            destructive = true,
            onConfirm = {
                lifecycleScope.launch {
                    setBusy(true)
                    val restored = withContext(Dispatchers.IO) { restoreBackup(latest) }
                    setBusy(false)
                    actionStatus.text = getString(if (restored) R.string.setedit_restore_done else R.string.setedit_restore_failed)
                    if (restored) loadRows()
                }
            }
        )
    }

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

    private suspend fun restoreBackup(backup: JSONObject): Boolean {
        if (!hasShellAccess()) return false
        return when (backup.optString("kind")) {
            "row" -> {
                val old = backup.opt("oldValue")
                val key = backup.optString("key")
                val tableName = backup.optString("table")
                if (!PiperSetEditPolicy.isValidKey(key) || tableName !in setOf("system", "secure", "global")) return false
                val command = if (old == JSONObject.NULL || old == null) "settings delete $tableName ${PiperSetEditPolicy.shellQuote(key)}"
                else "settings put $tableName ${PiperSetEditPolicy.shellQuote(key)} ${PiperSetEditPolicy.shellQuote(old.toString())}"
                client.newProcess(command, timeoutMs = 15_000L)?.exitCode == 0
            }
            "full" -> {
                val tables = backup.optJSONObject("tables") ?: return false
                var okay = true
                for (tableName in listOf("system", "secure", "global")) {
                    val saved = tables.optJSONObject(tableName) ?: continue
                    val current = client.newProcess("settings list $tableName", timeoutMs = 15_000L)
                    if (current?.exitCode != 0) { okay = false; continue }
                    PiperSetEditPolicy.parseSettingsList(current.output).forEach { (key, _) ->
                        val result = client.newProcess("settings delete $tableName ${PiperSetEditPolicy.shellQuote(key)}", timeoutMs = 15_000L)
                        if (result?.exitCode != 0) okay = false
                    }
                    val keys = saved.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val result = client.newProcess("settings put $tableName ${PiperSetEditPolicy.shellQuote(key)} ${PiperSetEditPolicy.shellQuote(saved.optString(key))}", timeoutMs = 15_000L)
                        if (result?.exitCode != 0) okay = false
                    }
                }
                okay
            }
            else -> false
        }.also { if (it) removeLatestBackup() }
    }

    private fun pushBackup(value: JSONObject) {
        val path = File(filesDir, BACKUP_FILE)
        val backups = readBackups().toMutableList()
        backups += JSONObject(value.toString()).put("created", System.currentTimeMillis())
        val json = JSONArray().apply { backups.takeLast(MAX_BACKUPS).forEach { put(it) } }
        path.writeText(json.toString())
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

    private fun readBackups(): List<JSONObject> = runCatching {
        val path = File(filesDir, BACKUP_FILE)
        if (!path.exists()) return emptyList()
        val array = JSONArray(path.readText())
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun removeLatestBackup() {
        val remaining = readBackups().dropLast(1)
        File(filesDir, BACKUP_FILE).writeText(JSONArray().apply { remaining.forEach { put(it) } }.toString())
    }

    private fun parseProperties(output: String): List<Pair<String, String>> = output.lineSequence().mapNotNull { line ->
        val start = line.indexOf('['); val end = line.indexOf("]: ")
        if (start < 0 || end <= start) null else line.substring(start + 1, end) to line.substring(end + 3).removeSurrounding("[", "]")
    }.sortedBy { it.first.lowercase() }.toList()

    private fun actionButton(label: Int, action: () -> Unit) = MaterialButton(this).apply {
        text = getString(label)
        isAllCaps = false
        textSize = 11f
        insetTop = 0; insetBottom = 0
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

    private fun LinearLayout.children() = (0 until childCount).map { getChildAt(it) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class RestoreOutcome(val restored: Int, val failed: Int)

    private inner class SetEditRowsAdapter : RecyclerView.Adapter<SetEditRowsAdapter.Holder>() {
        private var rows: List<Pair<String, String>> = emptyList()

        inner class Holder(val line: LinearLayout, val key: TextView, val value: TextView) : RecyclerView.ViewHolder(line)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val key = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(PiperModernUi.secondaryTextColor(parent.context))
                setPadding(dp(10), dp(10), dp(8), dp(10))
            }
            val value = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(PiperModernUi.textColor(parent.context))
                setPadding(dp(8), dp(10), dp(10), dp(10))
            }
            val line = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                background = android.util.TypedValue().let { attribute ->
                    parent.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attribute, true)
                    ContextCompat.getDrawable(parent.context, attribute.resourceId)
                }
                isClickable = true
                isFocusable = true
                addView(key, LinearLayout.LayoutParams(0, -2, 0.46f))
                addView(value, LinearLayout.LayoutParams(0, -2, 0.54f))
            }
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(line)
                addView(View(parent.context).apply {
                    setBackgroundColor(PiperModernUi.borderColor(parent.context))
                }, LinearLayout.LayoutParams(-1, dp(1)))
            }
            return Holder(container, key, value).also { holder ->
                line.setOnClickListener {
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
                holder.line.setOnClickListener(null)
                return
            }
            val (key, value) = rows[position]
            holder.key.layoutParams = LinearLayout.LayoutParams(0, -2, 0.46f)
            holder.value.visibility = View.VISIBLE
            holder.key.gravity = Gravity.START
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
        private const val ORIGINAL_VALUES_FILE = "piperos_setedit_original_values.json"
        private const val MAX_BACKUPS = 50
    }
}
