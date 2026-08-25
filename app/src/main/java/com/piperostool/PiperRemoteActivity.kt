package com.piperostool

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class PiperRemoteActivity : AppCompatActivity(), PiperRemoteClient.Listener {
    private lateinit var sharePanel: View
    private lateinit var connectPanel: View
    private lateinit var credentialPanel: View
    private lateinit var viewerPanel: View
    private lateinit var frameView: PiperRemoteFrameView
    private lateinit var videoSurface: SurfaceView
    private lateinit var viewerHint: TextView
    private lateinit var status: TextView
    private lateinit var toolbarStatus: TextView
    private lateinit var qrImage: ImageView
    private lateinit var codeText: TextView
    private lateinit var shareAddress: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var codeInput: TextInputEditText
    private lateinit var stopToolbar: ImageButton
    private lateinit var enableControl: MaterialButton
    private val io = Executors.newCachedThreadPool()
    private var pendingMethod = PiperRemoteMethod.LAN
    private var pendingPcInvite: PiperRemotePcInvite? = null
    private var receiverRegistered = false
    private var connected = false
    private var shownRequestId: String? = null
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val frameRenderScheduled = AtomicBoolean(false)
    private val client = PiperRemoteClient(this)
    private val videoDecoder = PiperRemoteVideoDecoder()
    private var activeStream = PiperRemoteStream.JPEG
    private var pendingVideoConfig: VideoConfig? = null

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchProjection()
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            showStatus(getString(R.string.remote_projection_denied))
            return@registerForActivityResult
        }
        val service = Intent(this, PiperRemoteShareService::class.java)
            .putExtra(PiperRemoteShareService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(PiperRemoteShareService.EXTRA_RESULT_DATA, data)
            .putExtra(PiperRemoteShareService.EXTRA_METHOD, pendingMethod.name)
            .putExtra(PiperRemoteShareService.EXTRA_PC_PAIR_HOST, pendingPcInvite?.host)
            .putExtra(PiperRemoteShareService.EXTRA_PC_PAIR_PORT, pendingPcInvite?.port ?: -1)
            .putExtra(PiperRemoteShareService.EXTRA_PC_PAIR_TOKEN, pendingPcInvite?.token)
        ContextCompat.startForegroundService(this, service)
        pendingPcInvite = null
        showStatus(getString(R.string.remote_starting_share))
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val endpoint = result.contents?.let(::parseQrEndpoint)
        if (endpoint == null) {
            if (result.contents != null) showStatus(getString(R.string.remote_invalid_qr))
        } else {
            connect(endpoint)
        }
    }

    private val pcQrLauncher = registerForActivityResult(ScanContract()) { result ->
        val invite = result.contents?.let(::parsePcQrInvite)
        if (invite == null) {
            if (result.contents != null) showStatus(getString(R.string.remote_invalid_pc_qr))
        } else {
            pendingPcInvite = invite
            showStatus(getString(R.string.remote_pc_qr_scanned))
            requestShare(PiperRemoteMethod.QR)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(PiperRemoteShareService.EXTRA_ERROR)?.let(::showStatus)
            renderShareState()
            renderPendingRequest()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_piper_remote)
        bindViews()
        applyInsets()
        bindActions()
        PiperModernUi.watch(this)
        renderRole(true)
        renderShareState()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (connected) confirmDisconnect() else finish()
            }
        })
    }

    private fun bindViews() {
        sharePanel = findViewById(R.id.remoteSharePanel)
        connectPanel = findViewById(R.id.remoteConnectPanel)
        credentialPanel = findViewById(R.id.remoteShareCredentialPanel)
        viewerPanel = findViewById(R.id.remoteViewerPanel)
        frameView = findViewById(R.id.remoteFrameView)
        videoSurface = findViewById(R.id.remoteVideoSurface)
        viewerHint = findViewById(R.id.tvRemoteViewerHint)
        status = findViewById(R.id.tvRemoteStatus)
        toolbarStatus = findViewById(R.id.tvRemoteToolbarStatus)
        qrImage = findViewById(R.id.imgRemoteQr)
        codeText = findViewById(R.id.tvRemoteCode)
        shareAddress = findViewById(R.id.tvRemoteShareAddress)
        deviceList = findViewById(R.id.remoteDeviceList)
        codeInput = findViewById(R.id.etRemoteCode)
        stopToolbar = findViewById(R.id.btnRemoteStopToolbar)
        enableControl = findViewById(R.id.btnEnableRemoteControl)
        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { startPendingDecoder() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { videoDecoder.stop() }
        })
    }

    private fun applyInsets() {
        val toolbar = findViewById<View>(R.id.remoteToolbar)
        val scroll = findViewById<View>(R.id.remoteScroll)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.remoteRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, bars.top + dp(10), toolbar.paddingRight, toolbar.paddingBottom)
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, bars.bottom + dp(18))
            insets
        }
    }

    private fun bindActions() {
        findViewById<View>(R.id.btnRemoteBack).setOnClickListener {
            if (connected) confirmDisconnect() else finish()
        }
        stopToolbar.setOnClickListener { stopEverything() }
        findViewById<View>(R.id.btnRemoteStreamSettings).setOnClickListener { showStreamSettings() }
        findViewById<View>(R.id.btnStopRemoteShare).setOnClickListener { PiperRemoteShareService.stop(this) }
        enableControl.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButtonToggleGroup>(R.id.remoteRoleGroup).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) renderRole(checkedId == R.id.btnRoleShare)
        }
        findViewById<View>(R.id.cardShareLan).setOnClickListener { requestShare(PiperRemoteMethod.LAN) }
        findViewById<View>(R.id.cardShareQr).setOnClickListener { chooseQrShare() }
        findViewById<View>(R.id.cardShareCode).setOnClickListener { requestShare(PiperRemoteMethod.CODE) }
        findViewById<View>(R.id.cardShareUsb).setOnClickListener { requestShare(PiperRemoteMethod.USB) }
        findViewById<View>(R.id.cardAppleMirror).setOnClickListener {
            startActivity(Intent(this, PiperAppleMirrorActivity::class.java))
        }
        findViewById<View>(R.id.btnScanLanRemote).setOnClickListener { scanLan() }
        findViewById<View>(R.id.btnScanRemoteQr).setOnClickListener {
            qrLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.remote_scan_qr_prompt))
                    .setBeepEnabled(false)
                    .setCaptureActivity(PiperRemoteQrCaptureActivity::class.java)
                    .setOrientationLocked(true)
            )
        }
        findViewById<View>(R.id.btnConnectRemoteCode).setOnClickListener { resolveCode() }
        findViewById<View>(R.id.btnRemoteExitViewer).setOnClickListener { confirmDisconnect() }
        frameView.touchListener = client::sendTouch
        videoSurface.setOnTouchListener { view, event ->
            client.sendTouch(event.actionMasked, event.x / view.width.coerceAtLeast(1), event.y / view.height.coerceAtLeast(1))
            true
        }
    }

    private fun requestShare(method: PiperRemoteMethod) {
        if (PiperRemoteShareService.currentSession != null) PiperRemoteShareService.stop(this)
        pendingMethod = method
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection()
        }
    }

    private fun chooseQrShare() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_remote_qr_choice, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        dialogView.findViewById<View>(R.id.btnQrOtherDevice).setOnClickListener {
            dialog.dismiss()
                    pendingPcInvite = null
                    requestShare(PiperRemoteMethod.QR)
        }
        dialogView.findViewById<View>(R.id.btnQrPc).setOnClickListener {
            dialog.dismiss()
                    pcQrLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(getString(R.string.remote_scan_pc_qr_prompt))
                            .setBeepEnabled(false)
                            .setCaptureActivity(PiperRemoteQrCaptureActivity::class.java)
                            .setOrientationLocked(true)
                    )
        }
        dialogView.findViewById<View>(R.id.btnQrChoiceCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun launchProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    private fun renderRole(isShare: Boolean) {
        sharePanel.visibility = if (isShare) View.VISIBLE else View.GONE
        connectPanel.visibility = if (isShare) View.GONE else View.VISIBLE
        if (!isShare) showStatus(getString(R.string.remote_private_lan_note))
    }

    private fun renderShareState() {
        val session = PiperRemoteShareService.currentSession
        credentialPanel.visibility = if (session == null) View.GONE else View.VISIBLE
        stopToolbar.visibility = if (session == null && !connected) View.GONE else View.VISIBLE
        enableControl.text = if (PiperRemoteAccessibilityService.isRunning()) {
            getString(R.string.remote_control_enabled)
        } else {
            getString(R.string.remote_enable_control)
        }
        if (session == null) {
            toolbarStatus.text = getString(R.string.remote_ready)
            return
        }
        toolbarStatus.text = getString(R.string.remote_sharing_active)
        qrImage.visibility = if (session.method == PiperRemoteMethod.QR) View.VISIBLE else View.GONE
        codeText.visibility = if (session.method == PiperRemoteMethod.CODE) View.VISIBLE else View.GONE
        if (session.method == PiperRemoteMethod.QR) qrImage.setImageBitmap(createQr(session.qrUri(), 700))
        if (session.method == PiperRemoteMethod.CODE) codeText.text = session.code
        shareAddress.text = when (session.method) {
            PiperRemoteMethod.LAN -> getString(R.string.remote_lan_visible, session.host)
            PiperRemoteMethod.QR -> getString(R.string.remote_qr_ready)
            PiperRemoteMethod.CODE -> getString(R.string.remote_code_ready)
            PiperRemoteMethod.USB -> getString(R.string.remote_usb_ready)
        }
        val actualStream = PiperRemoteShareService.currentStream
        val requestedStream = PiperRemoteShareService.currentRequestedStream
        showStatus(
            when {
                actualStream == null -> getString(R.string.remote_share_protected)
                requestedStream != null && requestedStream != actualStream -> getString(
                    R.string.remote_stream_fallback,
                    streamLabel(requestedStream),
                    streamLabel(actualStream)
                )
                else -> getString(R.string.remote_stream_active, streamLabel(actualStream))
            }
        )
    }

    private fun streamLabel(stream: PiperRemoteStream): String = when (stream) {
        PiperRemoteStream.JPEG -> "JPEG"
        PiperRemoteStream.H264 -> "H.264"
        PiperRemoteStream.HEVC -> "HEVC/H.265"
    }

    private fun renderPendingRequest() {
        val request = PiperRemoteShareService.currentRequest ?: run {
            shownRequestId = null
            return
        }
        if (shownRequestId == request.id || isFinishing || isDestroyed) return
        shownRequestId = request.id
        val dialogView = layoutInflater.inflate(R.layout.dialog_remote_connection_request, null)
        dialogView.findViewById<TextView>(R.id.tvRemoteRequestDevice).text = request.deviceName
        dialogView.findViewById<TextView>(R.id.tvRemoteRequestDetail).text = getString(
            R.string.remote_connection_request_detail, request.deviceName, request.address,
            request.targetWidth, request.targetFps
        )
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).create()
        dialogView.findViewById<View>(R.id.btnRemoteDeny).setOnClickListener { dialog.dismiss(); answerRequest(request.id, false) }
        dialogView.findViewById<View>(R.id.btnRemoteAllow).setOnClickListener { dialog.dismiss(); answerRequest(request.id, true) }
        dialog.setOnCancelListener { answerRequest(request.id, false) }
        dialog.show()
    }

    private fun answerRequest(id: String, allow: Boolean) {
        startService(
            Intent(this, PiperRemoteShareService::class.java)
                .setAction(if (allow) PiperRemoteShareService.ACTION_APPROVE else PiperRemoteShareService.ACTION_DENY)
                .putExtra(PiperRemoteShareService.EXTRA_REQUEST_ID, id)
        )
    }

    private fun scanLan() {
        showStatus(getString(R.string.remote_scanning_lan))
        deviceList.removeAllViews()
        io.execute {
            val devices = runCatching { PiperRemoteDiscovery.scan() }.getOrDefault(emptyList())
            runOnUiThread {
                if (devices.isEmpty()) {
                    showStatus(getString(R.string.remote_no_device))
                } else {
                    showStatus(resources.getQuantityString(R.plurals.remote_devices_found, devices.size, devices.size))
                    devices.forEach(::addDevice)
                }
            }
        }
    }

    private fun addDevice(endpoint: PiperRemoteEndpoint) {
        val card = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@PiperRemoteActivity, R.color.remote_border)
            setCardBackgroundColor(ContextCompat.getColor(this@PiperRemoteActivity, R.color.remote_surface))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        val label = TextView(this).apply {
            text = getString(R.string.remote_device_item, endpoint.name, endpoint.host)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setTextColor(ContextCompat.getColor(this@PiperRemoteActivity, R.color.remote_text_primary))
            textSize = 15f
        }
        card.addView(label)
        card.setOnClickListener { connect(endpoint) }
        deviceList.addView(card)
        PiperModernUi.apply(card)
    }

    private fun resolveCode() {
        val code = codeInput.text?.toString()?.trim().orEmpty()
        if (code.length != 6) {
            codeInput.error = getString(R.string.remote_code_invalid)
            return
        }
        showStatus(getString(R.string.remote_resolving_code))
        io.execute {
            val endpoint = runCatching { PiperRemoteDiscovery.resolveCode(code) }.getOrNull()
            runOnUiThread {
                if (endpoint == null) showStatus(getString(R.string.remote_code_not_found)) else connect(endpoint)
            }
        }
    }

    private fun connect(endpoint: PiperRemoteEndpoint) {
        client.close(false)
        stopToolbar.visibility = View.VISIBLE
        showStatus(getString(R.string.remote_connecting, endpoint.name))
        client.connect(
            endpoint,
            PiperRemoteProtocol.deviceName(this),
            selectedResolution(),
            selectedFps(),
            selectedStream()
        )
    }

    private fun selectedStream(): PiperRemoteStream {
        val value = getSharedPreferences("piperos_remote", MODE_PRIVATE).getInt("stream", PiperRemoteStream.H264.wireValue)
        return PiperRemoteStream.fromWire(value)
    }

    private fun showStreamSettings() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_remote_stream_settings, null)
        val group = dialogView.findViewById<RadioGroup>(R.id.remoteStreamRadioGroup)
        group.check(when (selectedStream()) {
            PiperRemoteStream.JPEG -> R.id.radioRemoteJpeg
            PiperRemoteStream.H264 -> R.id.radioRemoteH264
            PiperRemoteStream.HEVC -> R.id.radioRemoteHevc
        })
        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).create()
        dialogView.findViewById<View>(R.id.btnApplyRemoteStream).setOnClickListener {
            val stream = when (group.checkedRadioButtonId) {
                R.id.radioRemoteJpeg -> PiperRemoteStream.JPEG
                R.id.radioRemoteHevc -> PiperRemoteStream.HEVC
                else -> PiperRemoteStream.H264
            }
            getSharedPreferences("piperos_remote", MODE_PRIVATE).edit().putInt("stream", stream.wireValue).apply()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun selectedResolution(): Int = when (
        findViewById<MaterialButtonToggleGroup>(R.id.remoteResolutionGroup).checkedButtonId
    ) {
        R.id.btnRemote480 -> 854
        R.id.btnRemote1080 -> 1920
        R.id.btnRemoteNative -> 0
        else -> 1280
    }

    private fun selectedFps(): Int = when (
        findViewById<MaterialButtonToggleGroup>(R.id.remoteFpsGroup).checkedButtonId
    ) {
        R.id.btnRemote24Fps -> 24
        R.id.btnRemote60Fps -> 60
        R.id.btnRemoteMaxFps -> 0
        else -> 30
    }

    private fun parseQrEndpoint(value: String): PiperRemoteEndpoint? {
        return runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme != "piperos" || uri.host != "remote") return null
            PiperRemoteEndpoint(
                name = getString(R.string.remote_qr_device),
                host = uri.getQueryParameter("host") ?: return null,
                port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null,
                method = PiperRemoteMethod.QR,
                credential = uri.getQueryParameter("token") ?: return null
            )
        }.getOrNull()
    }

    private fun parsePcQrInvite(value: String): PiperRemotePcInvite? {
        return runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme != "piperos" || uri.host != "remote-pc") return null
            PiperRemotePcInvite(
                host = uri.getQueryParameter("host")?.takeIf { it.isNotBlank() } ?: return null,
                port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
                token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
            )
        }.getOrNull()
    }

    private fun createQr(value: String, size: Int): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (y in 0 until size) for (x in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        return bitmap
    }

    private fun showStatus(message: String) {
        status.text = message
        toolbarStatus.text = message
    }

    private fun stopEverything() {
        client.close(false)
        connected = false
        exitViewer()
        PiperRemoteShareService.stop(this)
        renderShareState()
    }

    override fun onConnected(width: Int, height: Int, stream: PiperRemoteStream) = runOnUiThread {
        connected = true
        activeStream = stream
        enterViewer()
        viewerHint.visibility = View.VISIBLE
        showStatus(getString(R.string.remote_connected_resolution, width, height))
    }

    override fun onVideoConfig(stream: PiperRemoteStream, width: Int, height: Int, fps: Int, codecData: ByteArray) = runOnUiThread {
        pendingVideoConfig = VideoConfig(stream, width, height, fps, codecData)
        activeStream = stream
        videoSurface.visibility = View.VISIBLE
        frameView.visibility = View.GONE
        startPendingDecoder()
    }

    override fun onVideoFrame(flags: Int, presentationTimeUs: Long, sentAtMs: Long, data: ByteArray) {
        videoDecoder.queue(data, presentationTimeUs, flags)
        runOnUiThread { viewerHint.visibility = View.GONE }
    }

    private fun startPendingDecoder() {
        val config = pendingVideoConfig ?: return
        val surface = videoSurface.holder.surface
        if (!surface.isValid) return
        runCatching { videoDecoder.start(config.stream, config.width, config.height, config.codecData, surface) }
            .onFailure { showStatus(getString(R.string.remote_error, it.message ?: "Decoder unavailable")) }
    }

    override fun onFrame(bitmap: Bitmap) {
        if (activeStream != PiperRemoteStream.JPEG) runOnUiThread {
            activeStream = PiperRemoteStream.JPEG
            videoDecoder.stop()
            videoSurface.visibility = View.GONE
            frameView.visibility = View.VISIBLE
        }
        pendingFrame.getAndSet(bitmap)?.recycle()
        scheduleLatestFrame()
    }

    private fun scheduleLatestFrame() {
        if (!frameRenderScheduled.compareAndSet(false, true)) return
        runOnUiThread {
            val bitmap = pendingFrame.getAndSet(null)
            if (bitmap != null) {
                if (isFinishing || isDestroyed || !connected) bitmap.recycle() else {
                    frameView.setFrame(bitmap)
                    viewerHint.visibility = View.GONE
                }
            }
            frameRenderScheduled.set(false)
            if (pendingFrame.get() != null) scheduleLatestFrame()
        }
    }

    override fun onError(message: String) = runOnUiThread { showStatus(getString(R.string.remote_error, message)) }

    override fun onDisconnected() = runOnUiThread {
        connected = false
        exitViewer()
        stopToolbar.visibility = if (PiperRemoteShareService.currentSession == null) View.GONE else View.VISIBLE
        if (!isFinishing) showStatus(getString(R.string.remote_disconnected))
    }

    private fun enterViewer() {
        frameView.clearFrame()
        videoSurface.visibility = if (activeStream == PiperRemoteStream.JPEG) View.GONE else View.VISIBLE
        frameView.visibility = if (activeStream == PiperRemoteStream.JPEG) View.VISIBLE else View.GONE
        findViewById<View>(R.id.remoteToolbar).visibility = View.GONE
        findViewById<View>(R.id.remoteScroll).visibility = View.GONE
        viewerPanel.visibility = View.VISIBLE
        viewerPanel.bringToFront()
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        viewerPanel.requestLayout()
        viewerPanel.post {
            viewerPanel.requestLayout()
            frameView.requestLayout()
            frameView.invalidate()
        }
    }

    private fun exitViewer() {
        videoDecoder.stop()
        pendingVideoConfig = null
        pendingFrame.getAndSet(null)?.recycle()
        frameView.clearFrame()
        viewerPanel.visibility = View.GONE
        findViewById<View>(R.id.remoteToolbar).visibility = View.VISIBLE
        findViewById<View>(R.id.remoteScroll).visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private data class VideoConfig(
        val stream: PiperRemoteStream,
        val width: Int,
        val height: Int,
        val fps: Int,
        val codecData: ByteArray
    )

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_disconnect_title)
            .setMessage(R.string.remote_disconnect_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remote_disconnect) { _, _ ->
                client.close(false)
                connected = false
                exitViewer()
                showStatus(getString(R.string.remote_disconnected))
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(PiperRemoteShareService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        renderShareState()
        renderPendingRequest()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        client.close(false)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
