package com.piperostool

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.roundToInt

class PiperAppleMirrorActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var configPanel: View
    private lateinit var viewerPanel: View
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var nameInput: TextInputEditText
    private var service: PiperAppleMirrorService? = null
    private var bound = false
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PiperAppleMirrorService.LocalBinder)?.service
            bound = service != null
            if (surfaceView.holder.surface.isValid) service?.attachSurface(surfaceView.holder.surface)
            renderCurrentState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sourceWidth = intent?.getIntExtra(PiperAppleMirrorService.EXTRA_WIDTH, 0) ?: 0
            sourceHeight = intent?.getIntExtra(PiperAppleMirrorService.EXTRA_HEIGHT, 0) ?: 0
            val error = intent?.getStringExtra(PiperAppleMirrorService.EXTRA_ERROR)
            when (intent?.getStringExtra(PiperAppleMirrorService.EXTRA_STATE)) {
                PiperAppleMirrorService.STATE_RUNNING -> showWaiting()
                PiperAppleMirrorService.STATE_CONNECTED -> showStatus(getString(R.string.apple_mirror_connected))
                PiperAppleMirrorService.STATE_MIRRORING -> enterViewer()
                PiperAppleMirrorService.STATE_STOPPED -> leaveViewer()
                PiperAppleMirrorService.STATE_ERROR -> {
                    leaveViewer()
                    showStatus(getString(R.string.apple_mirror_error, error.orEmpty()))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        setContentView(R.layout.activity_piper_apple_mirror)
        PiperModernUi.watch(this)
        bindViews()
        applyInsets()
        bindActions()
        restoreSettings()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewerPanel.visibility == View.VISIBLE || PiperAppleMirrorService.isRunning) confirmStop() else finish()
            }
        })
    }

    private fun bindViews() {
        configPanel = findViewById(R.id.appleMirrorConfigPanel)
        viewerPanel = findViewById(R.id.appleMirrorViewer)
        surfaceView = findViewById(R.id.appleMirrorSurface)
        status = findViewById(R.id.tvAppleMirrorStatus)
        startButton = findViewById(R.id.btnAppleMirrorStart)
        nameInput = findViewById(R.id.etAppleMirrorName)
        surfaceView.holder.addCallback(this)
        viewerPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
    }

    private fun applyInsets() {
        val toolbar = findViewById<View>(R.id.appleMirrorToolbar)
        val content = findViewById<View>(R.id.appleMirrorScroll)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appleMirrorRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, bars.top + dp(8), toolbar.paddingRight, toolbar.paddingBottom)
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bars.bottom + dp(18))
            insets
        }
    }

    private fun bindActions() {
        findViewById<View>(R.id.btnAppleMirrorBack).setOnClickListener {
            if (PiperAppleMirrorService.isRunning) confirmStop() else finish()
        }
        findViewById<View>(R.id.btnAppleMirrorStop).setOnClickListener { stopReceiver() }
        findViewById<View>(R.id.btnAppleMirrorExitViewer).setOnClickListener { confirmStop() }
        startButton.setOnClickListener {
            if (PiperAppleMirrorService.isRunning) stopReceiver() else startReceiver()
        }
    }

    private fun restoreSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        nameInput.setText(prefs.getString(KEY_NAME, getString(R.string.apple_mirror_default_name)))
        findViewById<MaterialButtonToggleGroup>(R.id.appleMirrorResolutionGroup)
            .check(prefs.getInt(KEY_RESOLUTION_BUTTON, R.id.btnAppleMirror1080))
        findViewById<MaterialButtonToggleGroup>(R.id.appleMirrorFpsGroup)
            .check(prefs.getInt(KEY_FPS_BUTTON, R.id.btnAppleMirror60Fps))
    }

    private fun startReceiver() {
        val name = nameInput.text?.toString().orEmpty().trim().ifBlank { getString(R.string.apple_mirror_default_name) }
        val resolutionId = findViewById<MaterialButtonToggleGroup>(R.id.appleMirrorResolutionGroup).checkedButtonId
        val fpsId = findViewById<MaterialButtonToggleGroup>(R.id.appleMirrorFpsGroup).checkedButtonId
        val (width, height) = when (resolutionId) {
            R.id.btnAppleMirror720 -> 1280 to 720
            R.id.btnAppleMirror1440 -> 2560 to 1440
            R.id.btnAppleMirrorNative -> resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
            else -> 1920 to 1080
        }
        val fps = when (fpsId) {
            R.id.btnAppleMirror30Fps -> 30
            R.id.btnAppleMirrorMaxFps -> maximumDisplayFps()
            else -> 60
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_NAME, name)
            .putInt(KEY_RESOLUTION_BUTTON, resolutionId)
            .putInt(KEY_FPS_BUTTON, fpsId)
            .apply()
        showStatus(getString(R.string.apple_mirror_starting))
        startButton.isEnabled = false
        PiperAppleMirrorService.start(this, name, width, height, fps)
        bindReceiverService()
    }

    private fun stopReceiver() {
        PiperAppleMirrorService.stop(this)
        leaveViewer()
        showStatus(getString(R.string.apple_mirror_stopped))
    }

    private fun renderCurrentState() {
        when {
            PiperAppleMirrorService.isMirroring -> enterViewer()
            PiperAppleMirrorService.isRunning -> showWaiting()
            else -> leaveViewer()
        }
    }

    private fun showWaiting() {
        startButton.isEnabled = true
        startButton.text = getString(R.string.apple_mirror_stop)
        showStatus(getString(R.string.apple_mirror_waiting, PiperAppleMirrorService.currentName))
    }

    private fun enterViewer() {
        startButton.isEnabled = true
        startButton.text = getString(R.string.apple_mirror_stop)
        configPanel.visibility = View.GONE
        findViewById<View>(R.id.appleMirrorToolbar).visibility = View.GONE
        viewerPanel.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        fitSurface()
    }

    private fun leaveViewer() {
        viewerPanel.visibility = View.GONE
        findViewById<View>(R.id.appleMirrorToolbar).visibility = View.VISIBLE
        configPanel.visibility = View.VISIBLE
        startButton.isEnabled = true
        startButton.text = getString(R.string.apple_mirror_start)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun fitSurface() {
        if (viewerPanel.width <= 0 || viewerPanel.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val hostAspect = viewerPanel.width.toFloat() / viewerPanel.height
        val width: Int
        val height: Int
        if (sourceAspect > hostAspect) {
            width = viewerPanel.width
            height = (width / sourceAspect).roundToInt()
        } else {
            height = viewerPanel.height
            width = (height * sourceAspect).roundToInt()
        }
        surfaceView.layoutParams = surfaceView.layoutParams.apply {
            this.width = width
            this.height = height
        }
    }

    private fun showStatus(message: String) { status.text = message }

    private fun confirmStop() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apple_mirror_stop_title)
            .setMessage(R.string.apple_mirror_stop_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remote_stop) { _, _ -> stopReceiver() }
            .show()
    }

    private fun bindReceiverService() {
        if (!bound) bindService(Intent(this, PiperAppleMirrorService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(PiperAppleMirrorService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (PiperAppleMirrorService.isRunning) bindReceiverService()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        if (bound) {
            service?.let { current -> if (surfaceView.holder.surface.isValid) current.detachSurface(surfaceView.holder.surface) }
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { service?.attachSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { service?.attachSurface(holder.surface) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { service?.detachSurface(holder.surface) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun maximumDisplayFps(): Int = getSystemService(DisplayManager::class.java)
        .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        ?.mode
        ?.refreshRate
        ?.roundToInt()
        ?.coerceIn(24, 120)
        ?: 60

    companion object {
        private const val PREFS = "piperos_airplay_ui"
        private const val KEY_NAME = "name"
        private const val KEY_RESOLUTION_BUTTON = "resolution_button"
        private const val KEY_FPS_BUTTON = "fps_button"
    }
}
