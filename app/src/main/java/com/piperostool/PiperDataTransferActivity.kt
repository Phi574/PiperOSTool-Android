package com.piperostool

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class PiperDataTransferActivity : AppCompatActivity() {
    private data class SelectedFile(val uri: Uri, val name: String, val size: Long, val mime: String)
    private data class RemoteEndpoint(val host: String, val port: Int, val token: ByteArray?, val label: String)
    private data class StagedFile(val file: File, val name: String, val mime: String)
    private data class InstalledBaseApk(val label: String, val packageName: String, val sourcePath: String)

    private companion object {
        const val DISCOVERY_PORT = 37684
        const val DISCOVER_REQUEST = "PDT_DISCOVER_V1"
        const val PAIR_CONFIRM = 1
        const val MANIFEST = 2
        const val READY = 3
        const val FILE_START = 4
        const val FILE_CHUNK = 5
        const val FILE_END = 6
        const val TRANSFER_DONE = 7
        const val TRANSFER_ACK = 8
        const val TRANSFER_ERROR = 9
        const val MAX_FILES = 500
        const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L * 1024L
        const val NORMAL_CHUNK_BYTES = 64 * 1024
        const val TURBO_CHUNK_BYTES = 512 * 1024
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private enum class FlowRole { SEND, RECEIVE }

    private val io = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val approval = AtomicReference<CountDownLatch?>(null)
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var discoverySocket: DatagramSocket? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var activeChannel: PiperDataTransferProtocol.SecureChannel? = null
    private var chosenFiles: List<SelectedFile> = emptyList()
    private var receivedRoot: File? = null
    private var receivedFiles: List<StagedFile> = emptyList()
    private var awaitingInstall: List<File> = emptyList()
    private var installIndex = 0
    private var installingPackage: String? = null
    private var pendingNetworkAction: (() -> Unit)? = null
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var ambientRunnable: Runnable? = null
    private var transferInProgress = false
    private var flowRole: FlowRole? = null
    private var lastProgressUpdateAt = 0L
    private val senderApproval = AtomicReference<CountDownLatch?>(null)
    private val approvedFiles = AtomicReference<List<SelectedFile>?>(null)
    private val turboEnabled = AtomicBoolean(false)
    private val rejectedInviteAttempts = mutableMapOf<String, Int>()
    @Volatile private var activeInviteCode: String? = null

    private lateinit var container: LinearLayout
    private lateinit var rolePanel: LinearLayout
    private lateinit var receivePanel: LinearLayout
    private lateinit var sendPanel: LinearLayout
    private lateinit var pairedPanel: LinearLayout
    private lateinit var senderControls: LinearLayout
    private lateinit var receiverControls: LinearLayout
    private lateinit var pairingPanel: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var qrImage: ImageView
    private lateinit var endpointLabel: TextView
    private lateinit var pairingCode: TextView
    private lateinit var inviteCodeLabel: TextView
    private lateinit var inviteCodeCard: MaterialCardView
    private lateinit var confirmButton: MaterialButton
    private lateinit var hostInput: TextInputEditText
    private lateinit var sessionCodeInput: TextInputEditText
    private lateinit var sessionCodeLayout: TextInputLayout
    private lateinit var selectedFilesLabel: TextView
    private lateinit var startTransferButton: MaterialButton
    private lateinit var turboSwitch: Switch
    private lateinit var receivedCard: LinearLayout
    private lateinit var ambientView: TextView

    private val filesPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val prepared = runCatching { uris.map { uri -> selectedFile(uri) } }
        if (prepared.isFailure) {
            showStatus("Không đọc được danh sách tệp đã chọn: ${prepared.exceptionOrNull()?.localizedMessage}")
            return@registerForActivityResult
        }
        chosenFiles = prepared.getOrThrow()
        val total = knownTotalBytes(chosenFiles.map { it.size })
        if (::selectedFilesLabel.isInitialized) selectedFilesLabel.text = "Đã chọn ${chosenFiles.size} mục • ${formatBytes(total)}"
        if (::startTransferButton.isInitialized) startTransferButton.isEnabled = chosenFiles.isNotEmpty()
        showStatus("Đã chọn ${chosenFiles.size} mục • ${formatBytes(total)}")
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importStagedFiles(uri)
    }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val endpoint = result.contents?.let(::parseInvite)
        if (endpoint == null) {
            if (result.contents != null) PiperDialog.showMessage(this, "Mã QR không hợp lệ", "Đây không phải mã phiên PiperOS Data Transfer.")
        } else connect(endpoint, requireManualCode = false)
    }

    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNetworkAction
        pendingNetworkAction = null
        if (granted) action?.invoke() else showStatus("Bạn chưa cấp quyền truy cập mạng cục bộ.")
    }

    private val nearbyWifiPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestLocalNetwork { startHotspot() } else showStatus("Chưa được cấp quyền tạo điểm phát cục bộ.")
    }

    private val hotspotLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestLocalNetwork { startHotspot() } else showStatus("Android cần quyền vị trí khi tạo điểm phát trên phiên bản này.")
    }

    private val installSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) installNextApk()
        else showStatus("Chưa cấp quyền cài APK từ PiperOS Tool.")
    }

    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val expectedPackage = installingPackage
        val candidate = awaitingInstall.getOrNull(installIndex)
        val requiredVersion = candidate?.let { packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.longVersionCode } ?: 0L
        val installed = expectedPackage?.let { packageName ->
            runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode >= requiredVersion }.getOrDefault(false)
        } == true
        if (!installed) {
            showStatus("Chưa xác nhận cài ${candidate?.name ?: "APK"}. Bạn có thể tiếp tục cài thủ công từng tệp.")
            installIndex++
            installNextApk()
            return@registerForActivityResult
        }
        installIndex++
        installNextApk()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalBrightness = window.attributes.screenBrightness
        buildUi()
        PiperModernUi.watch(this)
        loadCompletedSession()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (serverSocket != null || transferInProgress) {
                    PiperDialog.showConfirm(
                        this@PiperDataTransferActivity,
                        "Dừng phiên chuyển dữ liệu?",
                        "Phiên đang mở sẽ bị ngắt. Các tệp chưa hoàn tất sẽ không được nhập vào máy.",
                        "Dừng phiên"
                    ) { stopSession(); finish() }
                } else finish()
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && transferInProgress) restoreBrightScreen()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        stopSession()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = layoutInflater.inflate(R.layout.activity_piper_data_transfer, null) as FrameLayout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        container = root.findViewById(R.id.pdtContainer)
        root.findViewById<MaterialButton>(R.id.pdtBack).apply {
            cornerRadius = dp(16)
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this@PiperDataTransferActivity))
            backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperDataTransferActivity))
            setTextColor(PiperModernUi.textColor(this@PiperDataTransferActivity))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        root.findViewById<TextView>(R.id.pdtTitle).setTextColor(PiperModernUi.textColor(this))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top + dp(6), bars.right, bars.bottom + dp(10))
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        val introduction = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(text("Chuyển dữ liệu giữa hai thiết bị", 18f, true))
            addView(text("Kết nối và xác minh hai máy trước. Sau đó thiết bị gửi mới chọn nội dung cần chuyển.", 13f, false), top(6))
        }
        container.addView(card(introduction), bottom(12))

        status = text("Sẵn sàng", 13f, true).apply {
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setTextColor(PiperModernUi.accentColor(this@PiperDataTransferActivity))
        }
        container.addView(card(status), bottom(12))

        rolePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        rolePanel.addView(sectionTitle("Bước 1 · Chọn vai trò"), bottom(5))
        rolePanel.addView(text("Chọn thiết bị này sẽ gửi hay nhận dữ liệu.", 13f, false), bottom(12))
        rolePanel.addView(optionCard("↓", "Nhận dữ liệu", "Chuẩn bị mã phiên để thiết bị khác kết nối", PiperModernUi.accentColor(this)) { selectRole(FlowRole.RECEIVE) }, bottom(9))
        rolePanel.addView(optionCard("↑", "Gửi dữ liệu", "Kết nối với thiết bị đang nhận dữ liệu", PiperModernUi.accentColor(this)) { selectRole(FlowRole.SEND) })
        container.addView(card(rolePanel), bottom(12))

        receivePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        receivePanel.addView(sectionTitle("Bước 2 · Thiết bị nhận"), bottom(5))
        receivePanel.addView(text("Chọn cách ghép nối. Mã phiên 6 số sẽ hiện ngay khi bạn mở phiên nhận.", 13f, false), bottom(12))
        receivePanel.addView(button("Tạo mã QR để ghép nối") { requestLocalNetwork { startReceiver(useQrToken = true) } }, bottom(8))
        receivePanel.addView(button("Hiện mã xác minh 6 số") { requestLocalNetwork { startReceiver(useQrToken = false) } }, bottom(8))
        receivePanel.addView(button("Tạo điểm phát Wi‑Fi cục bộ") { requestHotspotPermission() }, bottom(10))
        inviteCodeLabel = text("", 30f, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setTextColor(PiperModernUi.accentColor(this@PiperDataTransferActivity))
            setPadding(dp(8), dp(12), dp(8), dp(8))
            setOnClickListener {
                val code = activeInviteCode ?: return@setOnClickListener
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PiperOS session code", code))
                Toast.makeText(this@PiperDataTransferActivity, "Đã sao chép mã phiên", Toast.LENGTH_SHORT).show()
            }
        }
        val inviteCodeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("MÃ PHIÊN 6 SỐ", 11f, true).apply { gravity = Gravity.CENTER; setTextColor(PiperModernUi.secondaryTextColor(this@PiperDataTransferActivity)) })
            addView(inviteCodeLabel, top(2))
            addView(text("Đưa mã này cho thiết bị gửi · Chạm để sao chép", 11f, false).apply { gravity = Gravity.CENTER }, top(2))
        }
        this.inviteCodeCard = card(inviteCodeCard).apply { visibility = View.GONE }
        receivePanel.addView(this.inviteCodeCard, bottom(10))
        receivePanel.addView(text("Thiết bị gửi có thể quét QR, tìm máy cùng mạng hoặc nhập IP và mã phiên.", 12f, false), bottom(10))
        receivePanel.addView(button("Quay lại chọn vai trò") { resetToRoleSelection() })
        container.addView(card(receivePanel), bottom(12))

        sendPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        sendPanel.addView(sectionTitle("Bước 2 · Thiết bị gửi"), bottom(5))
        sendPanel.addView(text("Nhập mã phiên của thiết bị nhận trước khi tìm hoặc kết nối.", 13f, false), bottom(11))
        sessionCodeLayout = TextInputLayout(this).apply {
            hint = "Mã phiên 6 số"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        sessionCodeInput = TextInputEditText(sessionCodeLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            textSize = 18f
            letterSpacing = 0.12f
        }
        sessionCodeLayout.addView(sessionCodeInput)
        sendPanel.addView(sessionCodeLayout, bottom(9))
        sendPanel.addView(button("Tìm thiết bị nhận cùng mạng") {
            val code = readSessionCode() ?: return@button
            requestLocalNetwork { discoverPeers(code) }
        }, bottom(8))
        sendPanel.addView(button("Quét QR của thiết bị nhận") {
            qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Quét mã PiperOS Data Transfer").setBeepEnabled(false).setCaptureActivity(PiperQrCaptureActivity::class.java))
        }, bottom(9))
        val hostLayout = TextInputLayout(this).apply {
            hint = "IP:port của thiết bị nhận"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        hostInput = TextInputEditText(hostLayout.context).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        hostLayout.addView(hostInput)
        sendPanel.addView(hostLayout, bottom(8))
        sendPanel.addView(button("Kết nối bằng IP và mã phiên") {
            val endpoint = parseManualEndpoint(hostInput.text?.toString()?.trim().orEmpty())
            val code = readSessionCode()
            if (endpoint == null) PiperDialog.showMessage(this, "IP chưa hợp lệ", "Nhập IP:port đang hiển thị trên thiết bị nhận.")
            else if (code != null) requestLocalNetwork { connect(endpoint, requireManualCode = true, invitationCode = code) }
        }, bottom(10))
        sendPanel.addView(button("Quay lại chọn vai trò") { resetToRoleSelection() })
        container.addView(card(sendPanel), bottom(12))

        qrImage = ImageView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(196), dp(196)).apply { gravity = Gravity.CENTER; topMargin = dp(12) }
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        endpointLabel = text("", 12f, false).apply { visibility = View.GONE; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        receivePanel.addView(qrImage)
        receivePanel.addView(endpointLabel, bottom(6))

        pairingPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        pairingPanel.addView(sectionTitle("Xác minh thiết bị"), bottom(5))
        pairingPanel.addView(text("So sánh mã bảo mật trên cả hai máy. Chỉ xác nhận khi các số giống hệt nhau.", 13f, false), bottom(6))
        pairingCode = text("", 30f, true).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(PiperModernUi.accentColor(this@PiperDataTransferActivity))
            setPadding(dp(8), dp(9), dp(8), dp(9))
        }
        pairingPanel.addView(pairingCode, bottom(7))
        confirmButton = button("Mã trùng khớp · Xác nhận thiết bị") { }
        pairingPanel.addView(confirmButton, bottom(6))
        container.addView(card(pairingPanel), bottom(12))

        pairedPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        senderControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        senderControls.addView(sectionTitle("Bước 3 · Chọn dữ liệu"), bottom(5))
        senderControls.addView(text("Chọn tệp sau khi đã xác minh thiết bị. Android yêu cầu bạn tự chọn nội dung.", 13f, false), bottom(10))
        senderControls.addView(button("Chọn tệp hoặc APK") { filesPicker.launch(arrayOf("*/*")) }, bottom(8))
        senderControls.addView(button("Chọn ảnh và video") { filesPicker.launch(arrayOf("image/*", "video/*")) }, bottom(8))
        senderControls.addView(button("Chọn ứng dụng đã cài dưới dạng APK") { chooseInstalledApplication() }, bottom(8))
        selectedFilesLabel = text("Chưa chọn dữ liệu", 12f, false).apply { setPadding(dp(12), dp(11), dp(12), dp(11)) }
        senderControls.addView(card(selectedFilesLabel), bottom(10))
        startTransferButton = button("Bắt đầu chuyển dữ liệu") { approveOutgoingTransfer() }.apply { isEnabled = false }
        senderControls.addView(startTransferButton)
        pairedPanel.addView(senderControls)

        receiverControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        receiverControls.addView(sectionTitle("Đã kết nối an toàn"), bottom(5))
        receiverControls.addView(text("Đang chờ thiết bị gửi chọn dữ liệu và bắt đầu chuyển…", 13f, false))
        pairedPanel.addView(receiverControls)

        turboSwitch = Switch(this).apply {
            text = "PiperOS Data Transfer Turbo"
            isChecked = false
            contentDescription = "Tối ưu tốc độ gửi và nhận dữ liệu trên cả hai thiết bị"
            setOnCheckedChangeListener { _, checked -> turboEnabled.set(checked) }
        }
        pairedPanel.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(turboSwitch)
            addView(text("Tăng kích thước bộ đệm và tối ưu nhịp truyền ở cả hai đầu. Mã hóa luôn được bật.", 12f, false), top(4))
        }), bottom(10))
        pairedPanel.addView(button("Hủy phiên kết nối") { stopSession(); resetToRoleSelection() })
        container.addView(card(pairedPanel), bottom(12))

        container.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            addView(sectionTitle("Nội dung được hỗ trợ"))
            addView(text("APK, tệp, ảnh và video do bạn chọn. Danh bạ, tin nhắn, lịch, mạng Wi‑Fi đã lưu và dữ liệu riêng của ứng dụng khác chưa thể nhập tự động do giới hạn Android.", 12f, false), top(5))
        }), bottom(12))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        progressLabel = text("", 12f, false).apply { visibility = View.GONE; setPadding(dp(4), dp(5), dp(4), dp(5)) }
        container.addView(progress, bottom(3))
        container.addView(progressLabel, bottom(10))
        receivedCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(15), dp(14), dp(15), dp(14)) }
        container.addView(card(receivedCard), bottom(12))

        ambientView = text("", 14f, true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(5, 8, 13))
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        root.addView(ambientView, FrameLayout.LayoutParams(-1, -1))
        ambientView.setOnClickListener { restoreBrightScreen() }
    }
    private fun selectRole(role: FlowRole) {
        flowRole = role
        rolePanel.visibility = View.GONE
        receivePanel.visibility = if (role == FlowRole.RECEIVE) View.VISIBLE else View.GONE
        sendPanel.visibility = if (role == FlowRole.SEND) View.VISIBLE else View.GONE
        pairedPanel.visibility = View.GONE
        pairingPanel.visibility = View.GONE
        showStatus(if (role == FlowRole.RECEIVE) "Chọn cách để thiết bị khác kết nối vào máy này." else "Chọn cách kết nối tới thiết bị nhận.")
    }

    private fun resetToRoleSelection() {
        stopSession()
        flowRole = null
        chosenFiles = emptyList()
        approvedFiles.set(null)
        selectedFilesLabel.text = "Chưa chọn dữ liệu"
        startTransferButton.isEnabled = false
        rolePanel.visibility = View.VISIBLE
        receivePanel.visibility = View.GONE
        sendPanel.visibility = View.GONE
        pairingPanel.visibility = View.GONE
        pairedPanel.visibility = View.GONE
        qrImage.visibility = View.GONE
        endpointLabel.visibility = View.GONE
        pairingCode.visibility = View.GONE
        inviteCodeCard.visibility = View.GONE
        inviteCodeLabel.visibility = View.GONE
        activeInviteCode = null
        sessionCodeInput.text?.clear()
        receivedCard.visibility = if (receivedFiles.isEmpty()) View.GONE else View.VISIBLE
        showStatus("Sẵn sàng")
    }

    private fun showPairedUi(role: FlowRole) {
        val update = {
            rolePanel.visibility = View.GONE
            receivePanel.visibility = View.GONE
            sendPanel.visibility = View.GONE
            pairingPanel.visibility = View.GONE
            pairedPanel.visibility = View.VISIBLE
            senderControls.visibility = if (role == FlowRole.SEND) View.VISIBLE else View.GONE
            receiverControls.visibility = if (role == FlowRole.RECEIVE) View.VISIBLE else View.GONE
            if (role == FlowRole.SEND) {
                selectedFilesLabel.text = if (chosenFiles.isEmpty()) "Chưa chọn dữ liệu" else "Đã chọn ${chosenFiles.size} mục • ${formatBytes(knownTotalBytes(chosenFiles.map { it.size }))}"
                startTransferButton.isEnabled = chosenFiles.isNotEmpty()
                showStatus("Đã xác minh thiết bị. Hãy chọn nội dung muốn gửi.")
            } else {
                showStatus("Đã xác minh thiết bị. Đang chờ bên gửi chọn dữ liệu…")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            val applied = CountDownLatch(1)
            runOnUiThread { update(); applied.countDown() }
            applied.await(3, TimeUnit.SECONDS)
        }
    }

    private fun approveOutgoingTransfer() {
        val files = chosenFiles.toList()
        if (files.isEmpty()) {
            PiperDialog.showMessage(this, "Chưa chọn dữ liệu", "Chọn ít nhất một tệp hoặc APK trước khi bắt đầu chuyển.")
            return
        }
        approvedFiles.set(files)
        startTransferButton.isEnabled = false
        showStatus("Đang bắt đầu chuyển ${files.size} mục…")
        senderApproval.getAndSet(null)?.countDown()
    }

    private fun awaitOutgoingSelection(): List<SelectedFile> {
        val latch = CountDownLatch(1)
        senderApproval.set(latch)
        showPairedUi(FlowRole.SEND)
        if (!latch.await(15, TimeUnit.MINUTES)) throw java.net.SocketTimeoutException("Chờ chọn dữ liệu quá lâu")
        senderApproval.compareAndSet(latch, null)
        return approvedFiles.getAndSet(null)?.takeIf { it.isNotEmpty() }
            ?: throw java.io.IOException("Không có dữ liệu được chọn")
    }

    private fun requestLocalNetwork(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingNetworkAction = action
            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    private fun requestHotspotPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            nearbyWifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else if (Build.VERSION.SDK_INT < 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            hotspotLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else requestLocalNetwork { startHotspot() }
    }

    private fun startHotspot() {
        val nearbyPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, nearbyPermission) != PackageManager.PERMISSION_GRANTED) {
            requestHotspotPermission()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Android chưa cấp quyền quản lý Wi‑Fi cho PiperOS Tool.")
            return
        }
        stopSession()
        val wifi = getSystemService(WifiManager::class.java)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val (ssid, password) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        (config.ssid ?: "Điểm phát cục bộ PiperOS") to config.passphrase.orEmpty()
                    } else {
                        val config = reservation.wifiConfiguration
                        (config?.SSID ?: "Điểm phát cục bộ PiperOS") to config?.preSharedKey?.trim('"').orEmpty()
                    }
                    runOnUiThread {
                        startReceiver(useQrToken = false, keepHotspot = true)
                        PiperDialog.showMessage(
                            this@PiperDataTransferActivity,
                            "Điểm phát đã sẵn sàng",
                            "Trên thiết bị gửi, kết nối Wi‑Fi vào mạng \"$ssid\"${if (password.isNotBlank()) " với mật khẩu $password" else ""}. Sau đó quét thiết bị cùng mạng hoặc nhập IP hiển thị trong phiên nhận."
                        )
                    }
                }

                override fun onFailed(reason: Int) {
                    runOnUiThread { showStatus("Không tạo được điểm phát cục bộ (mã $reason).") }
                }
            }, mainHandler)
        } catch (error: SecurityException) {
            showStatus("Android từ chối quyền tạo điểm phát: ${error.localizedMessage ?: "hãy kiểm tra quyền Wi‑Fi"}")
        } catch (error: Exception) {
            showStatus("Không tạo được điểm phát: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun startReceiver(useQrToken: Boolean, keepHotspot: Boolean = false) {
        flowRole = FlowRole.RECEIVE
        rolePanel.visibility = View.GONE
        sendPanel.visibility = View.GONE
        receivePanel.visibility = View.VISIBLE
        pairedPanel.visibility = View.GONE
        pairingPanel.visibility = View.GONE
        stopNetworkSessionOnly()
        if (!keepHotspot) {
            runCatching { hotspotReservation?.close() }
            hotspotReservation = null
        }
        try {
            val socket = ServerSocket(0).apply { reuseAddress = true }
            serverSocket = socket
            val token = if (useQrToken) PiperDataTransferProtocol.createPairingToken() else null
            activeInviteCode = if (token == null) String.format(Locale.ROOT, "%06d", SecureRandom().nextInt(1_000_000)) else null
            rejectedInviteAttempts.clear()
            val host = currentIpv4Address() ?: ""
            val qrPayload = buildString {
                append("PIPEROS-DATA/1|")
                append(host)
                append('|')
                append(socket.localPort)
                append('|')
                append(token?.let(::encodeToken).orEmpty())
            }
            endpointLabel.text = if (host.isBlank()) "Phiên nhận đang mở ở cổng ${socket.localPort}; hãy dùng quét mạng hoặc nhập IP máy này." else "Thiết bị nhận: $host : ${socket.localPort}\nQuét QR hoặc nhập IP để kết nối."
            endpointLabel.visibility = View.VISIBLE
            if (useQrToken && host.isNotBlank()) {
                qrImage.setImageBitmap(qrBitmap(qrPayload))
                qrImage.visibility = View.VISIBLE
                inviteCodeCard.visibility = View.GONE
            } else {
                qrImage.visibility = View.GONE
                inviteCodeLabel.text = activeInviteCode.orEmpty()
                inviteCodeLabel.visibility = View.VISIBLE
                inviteCodeCard.visibility = View.VISIBLE
            }
            pairingCode.visibility = View.GONE
            confirmButton.visibility = View.GONE
            showStatus(if (activeInviteCode == null) "Mã QR đã sẵn sàng. Đang chờ thiết bị gửi kết nối…" else "Mã phiên đã sẵn sàng. Nhập mã trên thiết bị gửi để kết nối.")
            startDiscoveryResponder(socket.localPort)
            transferInProgress = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scheduleAmbientMode()
            io.execute { acceptAndReceive(socket, token) }
        } catch (error: Exception) {
            showStatus("Không mở được phiên nhận: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun acceptAndReceive(server: ServerSocket, token: ByteArray?) {
        try {
            while (!server.isClosed && !isFinishing) {
                try {
                    val socket = server.accept()
                    applySocketProfile(socket)
                    activeSocket = socket
                    if (!validateSessionPreamble(socket, token)) {
                        runOnUiThread { showStatus("Mã phiên không đúng hoặc phương thức ghép nối không khớp. Đang chờ lại…") }
                        continue
                    }
                    val channel = PiperDataTransferProtocol.server(socket, token)
                    activeChannel = channel
                    if (!confirmPeer(channel, isSender = false, requireManualCode = false)) throw SecurityException("Thiết bị chưa xác nhận ghép nối")
                    showPairedUi(FlowRole.RECEIVE)
                    receiveFiles(channel)
                    return
                } catch (error: Exception) {
                    if (!server.isClosed && !isFinishing) {
                        runOnUiThread {
                            pairingPanel.visibility = View.GONE
                            receivePanel.visibility = View.VISIBLE
                            showStatus("Kết nối bị từ chối hoặc lỗi: ${error.localizedMessage ?: error.javaClass.simpleName}. Đang chờ lại…")
                        }
                    }
                } finally {
                    activeChannel?.close()
                    activeChannel = null
                    runCatching { activeSocket?.close() }
                    activeSocket = null
                }
            }
        } finally {
            stopNetworkSessionOnly()
            runCatching { hotspotReservation?.close() }
            hotspotReservation = null
            activeInviteCode = null
            transferInProgress = false
            runOnUiThread {
                if (::inviteCodeCard.isInitialized) inviteCodeCard.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                restoreBrightScreen()
            }
        }
    }

    private fun writeSessionPreamble(socket: Socket, qrAuthenticated: Boolean, code: String?) {
        val output = DataOutputStream(socket.getOutputStream())
        if (qrAuthenticated) {
            output.writeUTF("PDT_QR/1")
        } else {
            val pin = code?.takeIf { it.matches(Regex("\\d{6}")) }
                ?: throw SecurityException("A six-digit receiver session code is required")
            output.writeUTF("PDT_PIN/1")
            output.writeUTF(pin)
        }
        output.flush()
    }

    private fun validateSessionPreamble(socket: Socket, token: ByteArray?): Boolean {
        socket.soTimeout = 15_000
        val input = DataInputStream(socket.getInputStream())
        val mode = input.readUTF()
        if (mode == "PDT_QR/1") return token != null
        if (mode != "PDT_PIN/1" || token != null) return false
        val host = socket.inetAddress.hostAddress.orEmpty()
        if ((rejectedInviteAttempts[host] ?: 0) >= 5) return false
        val provided = input.readUTF()
        val expected = activeInviteCode ?: return false
        val matches = MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.US_ASCII),
            expected.toByteArray(StandardCharsets.US_ASCII)
        )
        if (matches) rejectedInviteAttempts.remove(host)
        else rejectedInviteAttempts[host] = (rejectedInviteAttempts[host] ?: 0) + 1
        return matches
    }

    private fun readSessionCode(): String? {
        val value = sessionCodeInput.text?.toString()?.trim().orEmpty()
        if (!value.matches(Regex("\\d{6}"))) {
            sessionCodeLayout.error = "Nhập đủ 6 số đang hiển thị trên thiết bị nhận"
            return null
        }
        sessionCodeLayout.error = null
        return value
    }

    private fun connect(endpoint: RemoteEndpoint, requireManualCode: Boolean = false, invitationCode: String? = null) {
        val sessionCode = if (endpoint.token == null) invitationCode?.takeIf { it.matches(Regex("\\d{6}")) } else null
        if (endpoint.token == null && sessionCode == null) {
            PiperDialog.showMessage(this, "Thiếu mã phiên", "Nhập mã 6 số đang hiển thị trên thiết bị nhận rồi thử lại.")
            return
        }
        flowRole = FlowRole.SEND
        rolePanel.visibility = View.GONE
        receivePanel.visibility = View.GONE
        sendPanel.visibility = View.VISIBLE
        pairedPanel.visibility = View.GONE
        pairingPanel.visibility = View.GONE
        requestLocalNetwork {
            stopNetworkSessionOnly()
            transferInProgress = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scheduleAmbientMode()
            showStatus("Đang kết nối với ${endpoint.label}…")
            io.execute {
                try {
                    val socket = Socket()
                    applySocketProfile(socket)
                    activeSocket = socket
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 12_000)
                    writeSessionPreamble(socket, endpoint.token != null, sessionCode)
                    val channel = PiperDataTransferProtocol.client(socket, endpoint.token)
                    activeChannel = channel
                    if (!confirmPeer(channel, isSender = true, requireManualCode = requireManualCode)) throw SecurityException("Không xác nhận được mã ghép nối")
                    showPairedUi(FlowRole.SEND)
                    val files = awaitOutgoingSelection()
                    sendFiles(channel, files, turboEnabled.get())
                } catch (error: Exception) {
                    runOnUiThread {
                        if (serverSocket == null) sendPanel.visibility = View.VISIBLE
                        pairedPanel.visibility = View.GONE
                        pairingPanel.visibility = View.GONE
                        showStatus("Không thể gửi dữ liệu: ${error.localizedMessage ?: error.javaClass.simpleName}")
                    }
                } finally {
                    activeChannel?.close()
                    activeChannel = null
                    runCatching { activeSocket?.close() }
                    activeSocket = null
                    transferInProgress = false
                    runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); restoreBrightScreen() }
                }
            }
        }
    }

    private fun confirmPeer(
        channel: PiperDataTransferProtocol.SecureChannel,
        isSender: Boolean,
        requireManualCode: Boolean
    ): Boolean {
        val latch = CountDownLatch(1)
        approval.set(latch)
        runOnUiThread {
            rolePanel.visibility = View.GONE
            sendPanel.visibility = View.GONE
            receivePanel.visibility = View.GONE
            pairingCode.text = "MÃ XÁC MINH\n${channel.pairingCode()}"
            pairingCode.visibility = View.VISIBLE
            pairingPanel.visibility = View.VISIBLE
            confirmButton.visibility = View.VISIBLE
            confirmButton.text = "Mã trùng khớp · Xác nhận thiết bị"
            confirmButton.setOnClickListener { latch.countDown() }
            endpointLabel.visibility = View.VISIBLE
            showStatus(if (isSender && requireManualCode) "Đã kiểm tra mã phiên. So sánh mã bảo mật trên hai máy trước khi xác nhận." else "So sánh mã bảo mật trên hai máy. Chỉ xác nhận nếu hai mã giống hệt nhau.")
        }
        val locallyApproved = latch.await(120, TimeUnit.SECONDS)
        approval.compareAndSet(latch, null)
        if (!locallyApproved) return false
        channel.send(PAIR_CONFIRM)
        showStatus("Đã xác nhận phía này; đang chờ xác nhận trên thiết bị còn lại…")
        val peer = channel.receive()
        if (peer.type != PAIR_CONFIRM) throw SecurityException("Peer did not confirm pairing")
        runOnUiThread {
            pairingPanel.visibility = View.GONE
            showStatus("Đã xác minh thiết bị an toàn.")
        }
        return true
    }

    private fun sendFiles(channel: PiperDataTransferProtocol.SecureChannel, files: List<SelectedFile>, turbo: Boolean) {
        if (files.size !in 1..MAX_FILES) throw IllegalArgumentException("Số lượng tệp vượt giới hạn phiên")
        val total = knownTotalBytes(files.map { it.size })
        if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("Tổng dữ liệu vượt giới hạn 20 GB")
        val array = JSONArray()
        files.forEach { file ->
            array.put(JSONObject().put("name", file.name).put("size", file.size).put("mime", file.mime))
        }
        channel.send(MANIFEST, JSONObject().put("files", array).put("turbo", turbo).toString().toByteArray(StandardCharsets.UTF_8))
        val response = channel.receive()
        if (response.type == TRANSFER_ERROR) throw IllegalStateException(String(response.payload, StandardCharsets.UTF_8))
        if (response.type != READY) throw SecurityException("Receiver rejected transfer manifest")
        val negotiatedTurbo = turbo && runCatching { JSONObject(String(response.payload, StandardCharsets.UTF_8)).optBoolean("turbo") }.getOrDefault(false)
        val chunkSize = if (negotiatedTurbo) TURBO_CHUNK_BYTES else NORMAL_CHUNK_BYTES
        var sent = 0L
        files.forEachIndexed { index, item ->
            channel.send(FILE_START, ByteBuffer.allocate(4).putInt(index).array())
            contentResolver.openInputStream(item.uri)?.use { raw ->
                BufferedInputStream(raw, if (negotiatedTurbo) TURBO_CHUNK_BYTES else NORMAL_CHUNK_BYTES).use { input ->
                    val buffer = ByteArray(chunkSize)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val payload = ByteBuffer.allocate(4 + read).putInt(index).put(buffer, 0, read).array()
                        channel.send(FILE_CHUNK, payload)
                        sent += read
                        updateProgress(sent, total, "Đang gửi ${index + 1}/${files.size}: ${item.name}")
                    }
                }
            } ?: throw IllegalStateException("Không đọc được ${item.name}")
            channel.send(FILE_END, ByteBuffer.allocate(4).putInt(index).array())
        }
        channel.send(TRANSFER_DONE)
        val completed = channel.receive()
        if (completed.type != TRANSFER_ACK) throw SecurityException("Receiver did not confirm completed transfer")
        showStatus("Đã gửi xong ${files.size} mục. Thiết bị nhận mới có thể nhập dữ liệu.")
    }

    private fun receiveFiles(channel: PiperDataTransferProtocol.SecureChannel) {
        val manifestFrame = channel.receive()
        if (manifestFrame.type != MANIFEST) throw SecurityException("Missing file manifest")
        val json = JSONObject(String(manifestFrame.payload, StandardCharsets.UTF_8))
        val negotiatedTurbo = json.optBoolean("turbo", false) && turboEnabled.get()
        val items = json.optJSONArray("files") ?: throw IllegalArgumentException("Manifest is invalid")
        if (items.length() !in 1..MAX_FILES) throw IllegalArgumentException("Số lượng tệp vượt giới hạn phiên")
        val manifest = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val size = item.optLong("size", -1L).takeIf { it >= 0L } ?: -1L
            val mime = item.optString("mime", "application/octet-stream").takeIf(::isSafeMime)
                ?: "application/octet-stream"
            Triple(safeName(item.optString("name")), size, mime)
        }
        val total = knownTotalBytes(manifest.map { it.second })
        if (total > MAX_TOTAL_BYTES) {
            channel.send(TRANSFER_ERROR, "Tổng dữ liệu vượt giới hạn 20 GB".toByteArray())
            throw IllegalArgumentException("Tổng dữ liệu vượt giới hạn 20 GB")
        }
        val free = filesDir.usableSpace
        if (total > 0 && total > free) {
            channel.send(TRANSFER_ERROR, "Dung lượng trống không đủ trên thiết bị nhận".toByteArray())
            throw IllegalStateException("Không đủ dung lượng trống để nhận dữ liệu")
        }
        val session = File(filesDir, "piper-data-transfer/incoming/${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
        if (!session.mkdirs()) throw IllegalStateException("Không tạo được vùng lưu tạm")
        try {
            channel.send(READY, JSONObject().put("turbo", negotiatedTurbo).toString().toByteArray(StandardCharsets.UTF_8))
            var completedBytes = 0L
            val staged = mutableListOf<StagedFile>()
            manifest.forEachIndexed { index, (name, expectedSize, mime) ->
                val start = channel.receive()
                if (start.type != FILE_START || start.payload.size != 4 || ByteBuffer.wrap(start.payload).int != index) {
                    throw SecurityException("Invalid incoming file order")
                }
                val file = File(session, "${index.toString().padStart(4, '0')}-$name")
                var written = 0L
                BufferedOutputStream(
                    FileOutputStream(file),
                    if (negotiatedTurbo) TURBO_CHUNK_BYTES else NORMAL_CHUNK_BYTES
                ).use { output ->
                    while (true) {
                        val frame = channel.receive()
                        if (frame.type == FILE_END) {
                            if (frame.payload.size != 4 || ByteBuffer.wrap(frame.payload).int != index) throw SecurityException("Invalid file end marker")
                            break
                        }
                        if (frame.type != FILE_CHUNK || frame.payload.size < 5) throw SecurityException("Invalid file chunk")
                        val buffer = ByteBuffer.wrap(frame.payload)
                        if (buffer.int != index) throw SecurityException("File index mismatch")
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        written += bytes.size
                        completedBytes += bytes.size
                        if (completedBytes > MAX_TOTAL_BYTES || (expectedSize >= 0 && written > expectedSize)) throw SecurityException("File size exceeded manifest")
                        output.write(bytes)
                        updateProgress(completedBytes, total, "Đang nhận ${index + 1}/${manifest.size}: $name")
                    }
                }
                if (expectedSize >= 0 && written != expectedSize) throw SecurityException("File length does not match manifest")
                staged += StagedFile(file, name, mime)
            }
            val done = channel.receive()
            if (done.type != TRANSFER_DONE) throw SecurityException("Transfer did not finish cleanly")
            File(session, ".complete").writeText("PiperOS Data Transfer v1\n${staged.size}\n")
            channel.send(TRANSFER_ACK)
            receivedRoot = session
            receivedFiles = staged
            showReceivedFiles(staged)
            showStatus("Đã nhận đủ ${staged.size} mục. Chọn cách nhập sau khi kiểm tra danh sách.")
            updateProgress(total, total, "Truyền hoàn tất")
        } catch (error: Exception) {
            session.deleteRecursively()
            throw error
        }
    }

    private fun showReceivedFiles(files: List<StagedFile>) {
        runOnUiThread {
            receivedCard.removeAllViews()
            receivedCard.visibility = View.VISIBLE
            receivedCard.addView(sectionTitle("Dữ liệu đã nhận • ${files.size} mục"))
            files.take(20).forEach { file -> receivedCard.addView(text("• ${file.name} (${formatBytes(file.file.length())})", 12f, false)) }
            if (files.size > 20) receivedCard.addView(text("Còn ${files.size - 20} mục khác…", 12f, false))
            receivedCard.addView(button("Nhập vào thư mục đã chọn") { folderPicker.launch(null) }, bottom(6))
            val apks = files.map { it.file }.filter { it.extension.equals("apk", true) }
            if (apks.isNotEmpty()) {
                receivedCard.addView(button("Cài tất cả APK lần lượt") { beginApkInstall(apks) }, bottom(6))
                apks.forEach { apk ->
                    receivedCard.addView(button("Cài riêng ${apk.name}") { beginApkInstall(listOf(apk)) }, bottom(5))
                }
            }
            receivedCard.addView(button("Xóa bản đã nhận") { deleteReceived() }, bottom(6))
        }
    }

    private fun importStagedFiles(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.canWrite()) {
            showStatus("Thư mục đã chọn không cho phép ghi tệp.")
            return
        }
        val sessionDirectory = root.createDirectory("PiperOS Transfer ${System.currentTimeMillis()}") ?: root
        io.execute {
            try {
                var copied = 0
                receivedFiles.forEach { item ->
                    val target = sessionDirectory.createFile(item.mime.ifBlank { "application/octet-stream" }, item.name)
                        ?: throw IllegalStateException("Không tạo được tệp ${item.name}")
                    contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                        FileInputStream(item.file).use { it.copyTo(output, 128 * 1024) }
                    } ?: throw IllegalStateException("Không ghi được tệp ${item.name}")
                    copied++
                }
                runOnUiThread { showStatus("Đã nhập $copied tệp vào ${sessionDirectory.name ?: "thư mục đã chọn"}.") }
            } catch (error: Exception) {
                runOnUiThread { showStatus("Không nhập được dữ liệu: ${error.localizedMessage}") }
            }
        }
    }

    private fun beginApkInstall(files: List<File>) {
        if (files.isEmpty()) return
        awaitingInstall = files
        installIndex = 0
        installNextApk()
    }

    private fun installNextApk() {
        if (installIndex >= awaitingInstall.size) {
            showStatus("Đã xử lý xong ${awaitingInstall.size} APK. Kiểm tra trạng thái cài đặt trên thiết bị.")
            return
        }
        val file = awaitingInstall[installIndex]
        val packageName = packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
        if (packageName.isNullOrBlank()) {
            showStatus("Tệp ${file.name} không phải APK hợp lệ; bỏ qua và thử tệp tiếp theo.")
            installIndex++
            installNextApk()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            PiperDialog.showConfirm(
                this,
                "Cho phép cài APK",
                "Android cần bạn cho phép PiperOS Tool mở trình cài đặt cho APK đã nhận. Mỗi ứng dụng vẫn cần được xác nhận trên màn hình hệ thống.",
                "Mở cài đặt"
            ) {
                installSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${applicationContext.packageName}")))
            }
            return
        }
        installingPackage = packageName
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${applicationContext.packageName}.files", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            clipData = android.content.ClipData.newRawUri("PiperOS APK", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        showStatus("Đang mở cài đặt APK ${installIndex + 1}/${awaitingInstall.size}: ${file.name}")
        installLauncher.launch(intent)
    }

    private fun deleteReceived() {
        val root = receivedRoot ?: return
        PiperDialog.showConfirm(this, "Xóa dữ liệu đã nhận?", "Các tệp trong vùng chờ sẽ bị xóa khỏi PiperOS Tool.", "Xóa", destructive = true) {
            root.deleteRecursively()
            receivedRoot = null
            receivedFiles = emptyList()
            receivedCard.visibility = View.GONE
            showStatus("Đã xóa dữ liệu đã nhận.")
        }
    }

    private fun loadCompletedSession() {
        val incoming = File(filesDir, "piper-data-transfer/incoming")
        val latest = incoming.listFiles()?.filter { File(it, ".complete").isFile }?.maxByOrNull(File::lastModified) ?: return
        val files = latest.listFiles()?.filter { it.isFile && it.name != ".complete" }?.map { file ->
            val name = file.name.substringAfter('-', file.name)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            StagedFile(file, name, mime)
        }.orEmpty()
        if (files.isNotEmpty()) {
            receivedRoot = latest
            receivedFiles = files
            showReceivedFiles(files)
            showStatus("Có ${files.size} mục từ phiên trước đang chờ nhập hoặc xóa.")
        }
    }

    private fun discoverPeers(sessionCode: String) {
        showStatus("Đang tìm thiết bị PiperOS trong cùng mạng…")
        io.execute {
            val found = linkedMapOf<String, RemoteEndpoint>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 500
                    val bytes = DISCOVER_REQUEST.toByteArray(StandardCharsets.UTF_8)
                    val request = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                    socket.send(request)
                    val deadline = System.currentTimeMillis() + 3500
                    while (System.currentTimeMillis() < deadline) {
                        val buffer = ByteArray(512)
                        val response = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(response)
                            val parts = String(response.data, 0, response.length, StandardCharsets.UTF_8).split('|')
                            if (parts.size >= 3 && parts[0] == "PDT_DEVICE_V1") {
                                val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
                                val label = parts.drop(2).joinToString("|").take(48)
                                val endpoint = RemoteEndpoint(response.address.hostAddress.orEmpty(), port, null, label)
                                found["${endpoint.host}:$port"] = endpoint
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { showStatus("Quét mạng không thành công: ${error.localizedMessage}") }
                return@execute
            }
            runOnUiThread {
                if (found.isEmpty()) {
                    showStatus("Không tìm thấy thiết bị. Hãy dùng mã QR hoặc nhập IP thủ công.")
                } else {
                    PiperActionSheet.showSearchSelect(
                        this,
                        "Chọn thiết bị nhận",
                        found.values.map { PiperSheetChoice("${it.host}:${it.port}", "${it.label} • ${it.host}") }
                    ) { key -> found[key]?.let { endpoint -> connect(endpoint, requireManualCode = true, invitationCode = sessionCode) } }
                }
            }
        }
    }

    private fun chooseInstalledApplication() {
        val candidates = runCatching {
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != packageName && it.splitSourceDirs.isNullOrEmpty() && it.sourceDir.isNotBlank() }
                .map { info ->
                    InstalledBaseApk(
                        packageManager.getApplicationLabel(info).toString(),
                        info.packageName,
                        info.sourceDir
                    )
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
                .toList()
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            PiperDialog.showMessage(this, "Không có APK cơ sở phù hợp", "Các ứng dụng cài bằng nhiều split APK cần chuyển cả bộ; phiên bản này chưa đóng gói split APK.")
            return
        }
        val byPackage = candidates.associateBy { it.packageName }
        PiperActionSheet.showSearchSelect(
            this,
            "Chọn ứng dụng để gửi",
            candidates.map { PiperSheetChoice(it.packageName, "${it.label} • ${it.packageName}") }
        ) { key -> byPackage[key]?.let(::stageInstalledApk) }
    }

    private fun stageInstalledApk(app: InstalledBaseApk) {
        showStatus("Đang chuẩn bị APK ${app.label}…")
        io.execute {
            try {
                val source = File(app.sourcePath)
                if (!source.isFile || source.length() <= 0L || source.length() > MAX_TOTAL_BYTES) {
                    throw IllegalStateException("Không đọc được APK hoặc tệp vượt quá 20 GB")
                }
                val outgoing = File(cacheDir, "piper-data-transfer/outgoing").apply { mkdirs() }
                val copy = File(outgoing, "${app.packageName}-${UUID.randomUUID().toString().take(8)}.apk")
                FileInputStream(source).use { input -> FileOutputStream(copy).use { input.copyTo(it, 128 * 1024) } }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", copy)
                val selected = SelectedFile(uri, safeName("${app.label}-${app.packageName.substringAfterLast('.')}.apk"), copy.length(), "application/vnd.android.package-archive")
                runOnUiThread {
                    chosenFiles = (chosenFiles + selected).distinctBy { it.uri.toString() }
                    val total = knownTotalBytes(chosenFiles.map { it.size })
                    selectedFilesLabel.text = "Đã chọn ${chosenFiles.size} mục • ${formatBytes(total)}"
                    startTransferButton.isEnabled = chosenFiles.isNotEmpty()
                    showStatus("Đã thêm ${app.label}. Có ${chosenFiles.size} mục để gửi • ${formatBytes(total)}")
                }
            } catch (error: Exception) {
                showStatus("Không chuẩn bị được APK ${app.label}: ${error.localizedMessage ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun startDiscoveryResponder(tcpPort: Int) {
        try {
            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
            discoverySocket = udp
            io.execute {
                while (!udp.isClosed && !isFinishing) {
                    try {
                        val data = ByteArray(256)
                        val request = DatagramPacket(data, data.size)
                        udp.receive(request)
                        if (String(request.data, 0, request.length, StandardCharsets.UTF_8) != DISCOVER_REQUEST) continue
                        val label = "${Build.MANUFACTURER} ${Build.MODEL}".replace('|', ' ').take(48)
                        val answer = "PDT_DEVICE_V1|$tcpPort|$label".toByteArray(StandardCharsets.UTF_8)
                        udp.send(DatagramPacket(answer, answer.size, request.address, request.port))
                    } catch (_: Exception) {
                        if (!udp.isClosed) runCatching { Thread.sleep(200L) }
                    }
                }
            }
        } catch (error: Exception) {
            showStatus("Phiên nhận vẫn mở; quét mạng chưa khả dụng (${error.localizedMessage}). Dùng QR hoặc IP.")
        }
    }

    private fun selectedFile(uri: Uri): SelectedFile {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "transfer-file"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = if (cursor.isNull(it)) -1 else cursor.getLong(it) }
            }
        }
        val mime = contentResolver.getType(uri)?.takeIf(::isSafeMime)
            ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        return SelectedFile(uri, safeName(name), size, mime)
    }

    private fun parseInvite(value: String): RemoteEndpoint? {
        val parts = value.split('|')
        if (parts.size != 4 || parts[0] != "PIPEROS-DATA/1") return null
        val host = parts[1].takeIf(String::isNotBlank) ?: return null
        val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val token = parts[3].takeIf(String::isNotBlank)?.let(::decodeToken)
        if (parts[3].isNotBlank() && token == null) return null
        return RemoteEndpoint(host, port, token, host)
    }

    private fun parseManualEndpoint(value: String): RemoteEndpoint? {
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.lastIndex) return null
        val host = value.substring(0, separator).trim()
        val port = value.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || host.contains('|')) return null
        return RemoteEndpoint(host, port, null, host)
    }

    private fun encodeToken(token: ByteArray): String = android.util.Base64.encodeToString(token, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    private fun decodeToken(encoded: String): ByteArray? = runCatching {
        android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            .takeIf { it.size == 32 }
    }.getOrNull()

    private fun currentIpv4Address(): String? {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { network ->
                    if (network.name.lowercase(Locale.ROOT).let { it.startsWith("wlan") || it.startsWith("ap") || it.contains("softap") }) 0 else 1
                }
                .flatMap { network -> Collections.list(network.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun applySocketProfile(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val bufferBytes = 1024 * 1024
        runCatching { socket.sendBufferSize = bufferBytes }
        runCatching { socket.receiveBufferSize = bufferBytes }
    }

    private fun qrBitmap(value: String): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 500, 500)
        return Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 500) for (y in 0 until 500) setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    private fun scheduleAmbientMode() {
        ambientRunnable?.let(mainHandler::removeCallbacks)
        ambientRunnable = Runnable {
            if (!transferInProgress || isFinishing) return@Runnable
            window.attributes = window.attributes.apply { screenBrightness = 0.03f }
            ambientView.text = "PiperOS DATA TRANSFER\n\n${progressLabel.text}\n\nChạm để xem chi tiết"
            ambientView.visibility = View.VISIBLE
        }.also { mainHandler.postDelayed(it, 10_000L) }
    }

    private fun restoreBrightScreen() {
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        ambientView.visibility = View.GONE
        if (transferInProgress) scheduleAmbientMode()
    }

    @Synchronized
    private fun updateProgress(done: Long, total: Long, label: String) {
        val now = System.currentTimeMillis()
        val isFinal = label == "Truyền hoàn tất"
        if (!isFinal && now - lastProgressUpdateAt < 150L) return
        lastProgressUpdateAt = now
        runOnUiThread {
            progress.visibility = View.VISIBLE
            progressLabel.visibility = View.VISIBLE
            val percent = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
            progress.isIndeterminate = total <= 0 && label != "Truyền hoàn tất"
            if (total > 0) progress.progress = percent else if (label == "Truyền hoàn tất") progress.progress = 100
            progressLabel.text = "$label${if (total > 0) " • $percent%" else ""}"
            if (ambientView.visibility == View.VISIBLE) ambientView.text = "PiperOS DATA TRANSFER\n\n$label\n\nChạm để xem chi tiết"
        }
    }

    private fun showStatus(message: String) = runOnUiThread { status.text = message }

    private fun stopNetworkSessionOnly() {
        runCatching { approval.getAndSet(null)?.countDown() }
        runCatching { senderApproval.getAndSet(null)?.countDown() }
        runCatching { activeChannel?.close() }
        runCatching { activeSocket?.close() }
        runCatching { discoverySocket?.close() }
        runCatching { serverSocket?.close() }
        activeChannel = null
        activeSocket = null
        discoverySocket = null
        serverSocket = null
        activeInviteCode = null
    }

    private fun stopSession() {
        stopNetworkSessionOnly()
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        transferInProgress = false
        ambientRunnable?.let(mainHandler::removeCallbacks)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        restoreBrightScreen()
    }

    private fun safeName(input: String): String = input
        .substringAfterLast('/')
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .take(180)
        .takeIf { it.isNotBlank() && it !in setOf(".", "..") }
        ?: "transfer-file"

    private fun isSafeMime(value: String): Boolean =
        value.length <= 128 && value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9.+-]*/[A-Za-z0-9][A-Za-z0-9.+-]*$"))

    private fun knownTotalBytes(sizes: List<Long>): Long = sizes.fold(0L) { total, size ->
        if (size <= 0L) total
        else if (size > MAX_TOTAL_BYTES - total) MAX_TOTAL_BYTES + 1L
        else total + size
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "kích thước chưa biết"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun showStatusRaw(message: String) { status.text = message }

    private fun sectionTitle(value: String) = text(value, 15f, true)

    private fun optionCard(symbol: String, title: String, description: String, accent: Int, click: () -> Unit) =
        MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            strokeWidth = dp(1)
            strokeColor = PiperModernUi.borderColor(this@PiperDataTransferActivity)
            cardElevation = 0f
            setCardBackgroundColor(PiperModernUi.surfaceColor(this@PiperDataTransferActivity))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
            val body = LinearLayout(this@PiperDataTransferActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(TextView(this@PiperDataTransferActivity).apply {
                    text = symbol
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(accent)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 24))
                    }
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(LinearLayout(this@PiperDataTransferActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 15f, true))
                    addView(text(description, 12f, false), top(4))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
                addView(TextView(this@PiperDataTransferActivity).apply {
                    text = "›"
                    textSize = 24f
                    setTextColor(PiperModernUi.secondaryTextColor(this@PiperDataTransferActivity))
                })
            }
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }

    private fun text(value: String, size: Float, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(PiperModernUi.textColor(this@PiperDataTransferActivity))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(label: String, click: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = dp(50)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(14)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun card(content: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        strokeWidth = dp(1)
        strokeColor = PiperModernUi.borderColor(this@PiperDataTransferActivity)
        cardElevation = 0f
        setCardBackgroundColor(PiperModernUi.surfaceColor(this@PiperDataTransferActivity))
        if (content.parent is android.view.ViewGroup) (content.parent as android.view.ViewGroup).removeView(content)
        addView(content, FrameLayout.LayoutParams(-1, -2))
        if (content !is LinearLayout || content.childCount == 0) content.setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun bottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
    private fun top(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
