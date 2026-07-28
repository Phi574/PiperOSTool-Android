package com.piperostool

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshOutput = Runnable { renderOutput() }
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0
    private var activeSessionId = 0L

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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RUNTIME_PROJECT_URL)))
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

        findViewById<View>(R.id.btnTerminalCtrlC).setOnClickListener {
            TerminalSessionManager.restartSession(this, activeSessionId)
            Toast.makeText(this, R.string.terminal_session_interrupted, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnTerminalTabKey).setOnClickListener {
            insertAtCursor("\t")
        }
        findViewById<View>(R.id.btnTerminalEsc).setOnClickListener {
            commandInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(commandInput.windowToken, 0)
        }
        findViewById<View>(R.id.btnTerminalHistoryUp).setOnClickListener {
            moveInHistory(-1)
        }
        findViewById<View>(R.id.btnTerminalHistoryDown).setOnClickListener {
            moveInHistory(1)
        }

        renderTabs()
        renderRuntimeStatus()
        renderOutput()
    }

    override fun onResume() {
        super.onResume()
        renderRuntimeStatus()
    }

    override fun onStart() {
        super.onStart()
        TerminalSessionManager.addListener(this)
        renderTabs()
        renderOutput()
    }

    override fun onStop() {
        TerminalSessionManager.removeListener(this)
        super.onStop()
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
        runtimeStatusView.isSelected = runtime.installed
        runtimeModeView.setText(
            if (runtime.installed) {
                R.string.terminal_runtime_linux_mode
            } else {
                R.string.terminal_runtime_android_mode
            }
        )
        runtimeDetailView.setText(
            if (runtime.installed) {
                R.string.terminal_runtime_ready
            } else {
                R.string.terminal_runtime_missing
            }
        )
        runtimeModeView.setTextColor(
            if (runtime.installed) 0xFF8DFFB0.toInt() else 0xFFFFD38A.toInt()
        )
        runtimeVersionView.text = getString(
            R.string.terminal_runtime_version,
            AppVersion.name(this)
        )
    }

    private fun moveInHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + direction).coerceIn(0, commandHistory.size)
        val value = commandHistory.getOrNull(historyIndex).orEmpty()
        commandInput.setText(value)
        commandInput.setSelection(value.length)
    }

    private fun insertAtCursor(text: String) {
        val position = commandInput.selectionStart.coerceAtLeast(0)
        commandInput.text?.insert(position, text)
        commandInput.requestFocus()
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
        private const val RUNTIME_PROJECT_URL =
            "https://github.com/Phi574/Piperos_termux"
        private val ANSI_ESCAPE = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
    }
}
