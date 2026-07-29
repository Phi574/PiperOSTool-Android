package com.piperostool

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray

class PiperTerminalActivity : AppCompatActivity(), TerminalSessionManager.Listener {
    private lateinit var tabsContainer: LinearLayout
    private lateinit var outputView: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var statusView: TextView
    private lateinit var runtimeStatusView: View
    private lateinit var runtimeModeView: TextView
    private lateinit var runtimeDetailView: TextView
    private lateinit var runtimeVersionView: TextView
    private lateinit var keyboardController: TerminalKeyboardController

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshOutput = Runnable { renderOutput() }
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0
    private var activeSessionId = 0L
    private var runtimeReceiverRegistered = false
    private val runtimeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderRuntimeStatus()
            when (intent?.getStringExtra(TerminalRuntimeInstallService.EXTRA_PHASE)) {
                TerminalRuntimeInstallService.Phase.COMPLETE.name -> {
                    activeSessionId = TerminalSessionManager.ensureSession(
                        this@PiperTerminalActivity
                    ).id
                    renderTabs()
                    renderOutput()
                    Toast.makeText(
                        this@PiperTerminalActivity,
                        R.string.terminal_runtime_complete,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                TerminalRuntimeInstallService.Phase.ERROR.name -> Toast.makeText(
                    this@PiperTerminalActivity,
                    intent.getStringExtra(TerminalRuntimeInstallService.EXTRA_MESSAGE),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_piper_terminal)

        tabsContainer = findViewById(R.id.terminalTabs)
        outputView = findViewById(R.id.tvTerminalOutput)
        outputScroll = findViewById(R.id.terminalScroll)
        commandInput = findViewById(R.id.etTerminalCommand)
        statusView = findViewById(R.id.tvTerminalStatus)
        runtimeStatusView = findViewById(R.id.terminalRuntimeStatus)
        runtimeModeView = findViewById(R.id.tvTerminalRuntimeMode)
        runtimeDetailView = findViewById(R.id.tvTerminalRuntimeDetail)
        runtimeVersionView = findViewById(R.id.tvTerminalRuntimeVersion)
        outputView.setTag(R.id.piper_auto_font_ignore, true)
        commandInput.setTag(R.id.piper_auto_font_ignore, true)

        loadHistory()
        val firstSession = TerminalSessionManager.ensureSession(this)
        activeSessionId = savedInstanceState
            ?.getLong(STATE_ACTIVE_SESSION)
            ?.takeIf { id -> TerminalSessionManager.listSessions().any { it.id == id } }
            ?: firstSession.id

        ContextCompat.startForegroundService(
            this,
            Intent(this, PiperTerminalService::class.java)
        )

        findViewById<View>(R.id.btnTerminalBack).setOnClickListener { finish() }
        runtimeStatusView.setOnClickListener {
            requestRuntimeInstall()
        }
        findViewById<View>(R.id.btnTerminalClear).setOnClickListener {
            TerminalSessionManager.clearOutput(activeSessionId)
        }
        findViewById<View>(R.id.btnNewTerminalTab).setOnClickListener {
            createNewSession()
        }
        findViewById<View>(R.id.btnCloseTerminalTab).setOnClickListener {
            closeCurrentSession()
        }
        findViewById<View>(R.id.btnTerminalSend).setOnClickListener {
            submitCommand()
        }
        commandInput.setOnEditorActionListener { _, _, _ ->
            submitCommand()
            true
        }

        keyboardController = TerminalKeyboardController(
            context = this,
            input = commandInput,
            panel = findViewById(R.id.terminalCustomKeyboard),
            rowsContainer = findViewById(R.id.terminalKeyboardRows),
            functionKeys = findViewById(R.id.terminalFunctionKeys),
            modeButton = findViewById(R.id.btnTerminalKeyboardMode),
            clipboardButton = findViewById(R.id.btnTerminalClipboard),
            onSubmit = ::submitCommand,
            onInterrupt = {
                TerminalSessionManager.restartSession(this, activeSessionId)
                Toast.makeText(
                    this,
                    R.string.terminal_session_interrupted,
                    Toast.LENGTH_SHORT
                ).show()
            },
            onHistory = ::moveInHistory,
            onPageScroll = { direction ->
                outputScroll.pageScroll(
                    if (direction < 0) View.FOCUS_UP else View.FOCUS_DOWN
                )
            },
            onRawInput = { value ->
                TerminalSessionManager.sendRaw(activeSessionId, value)
            }
        )

        renderTabs()
        renderRuntimeStatus()
        renderOutput()
        updateTerminalLayout()
    }

    override fun onResume() {
        super.onResume()
        renderRuntimeStatus()
    }

    override fun onStart() {
        super.onStart()
        if (!runtimeReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                runtimeStateReceiver,
                IntentFilter(TerminalRuntimeInstallService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            runtimeReceiverRegistered = true
        }
        TerminalSessionManager.addListener(this)
        keyboardController.start()
        renderTabs()
        renderOutput()
    }

    override fun onStop() {
        keyboardController.stop()
        TerminalSessionManager.removeListener(this)
        if (runtimeReceiverRegistered) {
            unregisterReceiver(runtimeStateReceiver)
            runtimeReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardController.applyConfiguration()
        updateTerminalLayout()
    }

    private fun updateTerminalLayout() {
        runtimeStatusView.visibility =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_ACTIVE_SESSION, activeSessionId)
        super.onSaveInstanceState(outState)
    }

    override fun onTerminalOutput(sessionId: Long) {
        if (sessionId != activeSessionId) return
        mainHandler.removeCallbacks(refreshOutput)
        mainHandler.postDelayed(refreshOutput, OUTPUT_REFRESH_DELAY_MS)
    }

    override fun onTerminalSessionsChanged() {
        mainHandler.post {
            val sessions = TerminalSessionManager.listSessions()
            if (sessions.isEmpty()) {
                finish()
                return@post
            }
            if (sessions.none { it.id == activeSessionId }) {
                activeSessionId = sessions.first().id
            }
            renderTabs()
            renderOutput()
        }
    }

    private fun submitCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isEmpty()) return

        if (TerminalSessionManager.sendCommand(activeSessionId, command)) {
            rememberCommand(command)
            commandInput.text?.clear()
            historyIndex = commandHistory.size
        } else {
            Toast.makeText(this, R.string.terminal_shell_not_running, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNewSession() {
        if (TerminalSessionManager.sessionCount() >= MAX_SESSIONS) {
            Toast.makeText(this, R.string.terminal_session_limit, Toast.LENGTH_SHORT).show()
            return
        }
        activeSessionId = TerminalSessionManager.createSession(this).id
        renderTabs()
        renderOutput()
        commandInput.requestFocus()
    }

    private fun closeCurrentSession() {
        TerminalSessionManager.closeSession(activeSessionId)
        val sessions = TerminalSessionManager.listSessions()
        if (sessions.isEmpty()) {
            activeSessionId = TerminalSessionManager.createSession(this).id
            ContextCompat.startForegroundService(
                this,
                Intent(this, PiperTerminalService::class.java)
            )
        } else {
            activeSessionId = sessions.last().id
        }
        renderTabs()
        renderOutput()
    }

    private fun renderTabs() {
        val sessions = TerminalSessionManager.listSessions()
        tabsContainer.removeAllViews()
        sessions.forEach { session ->
            val tab = TextView(this).apply {
                text = session.title
                textSize = 12f
                setTextColor(if (session.id == activeSessionId) Color.WHITE else 0xAFFFFFFF.toInt())
                gravity = Gravity.CENTER
                isSelected = session.id == activeSessionId
                background = ContextCompat.getDrawable(
                    this@PiperTerminalActivity,
                    R.drawable.bg_terminal_tab
                )
                minWidth = dp(82)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    activeSessionId = session.id
                    renderTabs()
                    renderOutput()
                }
            }
            tabsContainer.addView(
                tab,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply { marginEnd = dp(7) }
            )
        }
        val current = sessions.firstOrNull { it.id == activeSessionId }
        statusView.text = if (current?.running == true) {
            getString(R.string.terminal_status_running, current.title)
        } else {
            getString(R.string.terminal_status_stopped)
        }
    }

    private fun renderOutput() {
        val plainOutput = ANSI_ESCAPE.replace(
            TerminalSessionManager.output(activeSessionId),
            ""
        )
        outputView.text = plainOutput
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderRuntimeStatus() {
        val runtime = TerminalRuntime.inspect(this)
        val installState = TerminalRuntimeInstallService.currentState
        runtimeStatusView.isSelected = runtime.installed || installState.running
        runtimeModeView.setText(
            if (installState.running) {
                R.string.terminal_runtime_installing_mode
            } else if (runtime.installed) {
                R.string.terminal_runtime_linux_mode
            } else {
                R.string.terminal_runtime_android_mode
            }
        )
        runtimeDetailView.text = when {
            installState.running -> installState.message
            installState.phase == TerminalRuntimeInstallService.Phase.ERROR ->
                installState.message
            runtime.updateAvailable -> getString(R.string.terminal_runtime_update_available)
            runtime.installed -> getString(R.string.terminal_runtime_ready)
            else -> getString(R.string.terminal_runtime_missing)
        }
        runtimeModeView.setTextColor(
            when {
                installState.running -> 0xFF8AD6FF.toInt()
                runtime.installed -> 0xFF8DFFB0.toInt()
                else -> 0xFFFFD38A.toInt()
            }
        )
        runtimeVersionView.text = getString(
            R.string.terminal_runtime_version,
            runtime.installedVersion ?: TerminalRuntime.RUNTIME_VERSION
        )
    }

    private fun requestRuntimeInstall() {
        val state = TerminalRuntimeInstallService.currentState
        if (state.running) {
            Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
            return
        }
        val runtime = TerminalRuntime.inspect(this)
        if (runtime.installed && !runtime.updateAvailable) {
            Toast.makeText(this, R.string.terminal_runtime_already_current, Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, TerminalRuntimeInstallService::class.java)
                .setAction(TerminalRuntimeInstallService.ACTION_INSTALL)
        )
        mainHandler.postDelayed({ renderRuntimeStatus() }, 150)
    }

    private fun moveInHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + direction).coerceIn(0, commandHistory.size)
        val value = commandHistory.getOrNull(historyIndex).orEmpty()
        commandInput.setText(value)
        commandInput.setSelection(value.length)
    }

    private fun rememberCommand(command: String) {
        commandHistory.remove(command)
        commandHistory += command
        while (commandHistory.size > MAX_HISTORY) commandHistory.removeAt(0)
        saveHistory()
    }

    private fun loadHistory() {
        val encoded = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return
        runCatching {
            val values = JSONArray(encoded)
            repeat(values.length()) { index ->
                commandHistory += values.optString(index)
            }
            historyIndex = commandHistory.size
        }
    }

    private fun saveHistory() {
        val values = JSONArray()
        commandHistory.forEach(values::put)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, values.toString())
            .apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_ACTIVE_SESSION = "active_terminal_session"
        private const val PREFS_NAME = "PiperTerminalPrefs"
        private const val KEY_HISTORY = "command_history"
        private const val MAX_HISTORY = 100
        private const val MAX_SESSIONS = 6
        private const val OUTPUT_REFRESH_DELAY_MS = 45L
        private val ANSI_ESCAPE = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
    }
}
