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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
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
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class PiperDataTransferActivity : AppCompatActivity() {
    private data class SelectedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String,
        val sourceAppPackage: String? = null
    )
    private data class RemoteEndpoint(val host: String, val port: Int, val token: ByteArray?, val label: String)
    private data class InstalledBaseApk(val label: String, val packageName: String, val sourcePath: String)
    private class PeerCancelledException(message: String) : java.io.IOException(message)

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
        const val TRANSFER_CANCEL = 10
        const val MAX_FILES = 500
        const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L * 1024L
        const val NORMAL_CHUNK_BYTES = 64 * 1024
        const val TURBO_CHUNK_BYTES = 512 * 1024
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private enum class FlowRole { SEND, RECEIVE }
    private enum class ActionButtonStyle { PRIMARY, CONTINUE, BACK, DESTRUCTIVE }

    private val io = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val approval = AtomicReference<CountDownLatch?>(null)
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var discoverySocket: DatagramSocket? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var activeChannel: PiperDataTransferProtocol.SecureChannel? = null
    private var chosenFiles: List<SelectedFile> = emptyList()
    private var pendingNetworkAction: (() -> Unit)? = null
    private var transferInProgress = false
    private var flowRole: FlowRole? = null
    @Volatile private var activeProgressId: String? = null
    private val cancellationStarted = AtomicBoolean(false)
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
    private lateinit var selectedFilesList: ListView
    private lateinit var selectedFilesCard: MaterialCardView
    private lateinit var startTransferButton: MaterialButton
    private lateinit var currentProgressButton: MaterialButton
    private lateinit var deleteProgressButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var turboSwitch: Switch
    private val filesPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val prepared = runCatching { uris.map { uri -> selectedFile(uri) } }
        if (prepared.isFailure) {
            showStatus("Không đọc được danh sách tệp đã chọn: ${prepared.exceptionOrNull()?.localizedMessage}")
            return@registerForActivityResult
        }
        appendChosenFiles(prepared.getOrThrow())
    }

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(500)) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val prepared = runCatching { uris.map { uri -> selectedFile(uri) } }
        if (prepared.isFailure) {
            showStatus("Không đọc được ảnh/video đã chọn: ${prepared.exceptionOrNull()?.localizedMessage}")
            return@registerForActivityResult
        }
        appendChosenFiles(prepared.getOrThrow())
    }

    private fun appendChosenFiles(items: List<SelectedFile>) {
        val merged = (chosenFiles + items).distinctBy { it.uri.toString() }
        val exceeded = merged.size > MAX_FILES
        chosenFiles = merged.take(MAX_FILES)
        refreshChosenSelection()
        val total = knownTotalBytes(chosenFiles.map { it.size })
        showStatus(if (exceeded) "Đã đạt giới hạn $MAX_FILES mục. Các mục đầu tiên đã được giữ lại." else "Đã chọn ${chosenFiles.size} mục • ${formatBytes(total)}")
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        PiperModernUi.watch(this)
        loadCompletedSession()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeChannel != null || transferInProgress && flowRole == FlowRole.SEND) {
                    PiperDialog.showConfirm(
                        this@PiperDataTransferActivity,
                        "Dừng phiên chuyển dữ liệu?",
                        "Phiên sẽ bị hủy ở cả hai thiết bị. Sau đó bạn có thể quay lại chọn vai trò.",
                        "Hủy phiên",
                        destructive = true
                    ) { cancelCurrentSession("Bạn đã hủy phiên chuyển dữ liệu.") }
                } else if (flowRole != null || serverSocket != null) {
                    resetToRoleSelection()
                } else finish()
            }
        })
    }

    override fun onDestroy() {
        stopSession()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::currentProgressButton.isInitialized) refreshProgressActions()
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
            styleActionButton(this, ActionButtonStyle.BACK)
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

        val progressActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            currentProgressButton = button("Mở tiến trình hiện tại") { openCurrentProgress() }.apply {
                visibility = View.GONE
                styleActionButton(this, ActionButtonStyle.PRIMARY)
            }
            historyButton = button("Lịch sử truyền") { showTransferHistory() }.apply {
                styleActionButton(this, ActionButtonStyle.BACK)
            }
            addView(currentProgressButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(historyButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }
        container.addView(card(progressActions), bottom(12))
        deleteProgressButton = button("Xóa tiến trình gần nhất") { confirmDeleteLatestProgress() }.apply {
            visibility = View.GONE
            styleActionButton(this, ActionButtonStyle.DESTRUCTIVE)
        }
        container.addView(deleteProgressButton, bottom(12))

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
        receivePanel.addView(button("Quay lại chọn vai trò") { resetToRoleSelection() }.apply {
            styleActionButton(this, ActionButtonStyle.BACK)
        })
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
        sendPanel.addView(button("Quay lại chọn vai trò") { resetToRoleSelection() }.apply {
            styleActionButton(this, ActionButtonStyle.BACK)
        })
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
        confirmButton = button("Mã trùng khớp · Xác nhận thiết bị") { }.apply {
            styleActionButton(this, ActionButtonStyle.CONTINUE)
        }
        pairingPanel.addView(confirmButton, bottom(6))
        container.addView(card(pairingPanel), bottom(12))

        pairedPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        senderControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        senderControls.addView(sectionTitle("Bước 3 · Chọn dữ liệu"), bottom(5))
        senderControls.addView(text("Chọn tệp sau khi đã xác minh thiết bị. Android yêu cầu bạn tự chọn nội dung.", 13f, false), bottom(10))
        senderControls.addView(button("Chọn tệp hoặc APK") { filesPicker.launch(arrayOf("*/*")) }, bottom(8))
        senderControls.addView(button("Chọn ảnh và video") {
            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }, bottom(8))
        senderControls.addView(button("Chọn ứng dụng đã cài dưới dạng APK") { chooseInstalledApplication() }, bottom(8))
        selectedFilesLabel = text("Chưa chọn dữ liệu", 12f, false).apply { setPadding(dp(12), dp(11), dp(12), dp(11)) }
        senderControls.addView(card(selectedFilesLabel), bottom(10))
        senderControls.addView(sectionTitle("Danh sách trước khi gửi"), bottom(5))
        senderControls.addView(text("Chạm Xem trước để kiểm tra nội dung hoặc Xóa để bỏ từng mục.", 12f, false), bottom(7))
        selectedFilesList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(PiperModernUi.borderColor(this@PiperDataTransferActivity))
            dividerHeight = dp(1)
            isNestedScrollingEnabled = true
            adapter = selectedFilesAdapter()
        }
        selectedFilesCard = card(selectedFilesList).apply { selectedFilesList.layoutParams = FrameLayout.LayoutParams(-1, dp(220)) }
        senderControls.addView(selectedFilesCard, bottom(10))
        selectedFilesCard.visibility = View.GONE
        startTransferButton = button("Bắt đầu chuyển dữ liệu") { approveOutgoingTransfer() }.apply {
            isEnabled = false
            styleActionButton(this, ActionButtonStyle.CONTINUE)
        }
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
        pairedPanel.addView(button("Hủy phiên kết nối") { confirmCancelSession() }.apply {
            styleActionButton(this, ActionButtonStyle.DESTRUCTIVE)
        })
        container.addView(card(pairedPanel), bottom(12))

        container.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            addView(sectionTitle("Nội dung được hỗ trợ"))
            addView(text("APK, tệp, ảnh và video do bạn chọn. Danh bạ, tin nhắn, lịch, mạng Wi‑Fi đã lưu và dữ liệu riêng của ứng dụng khác chưa thể nhập tự động do giới hạn Android.", 12f, false), top(5))
        }), bottom(12))

    }
    private fun selectRole(role: FlowRole) {
        cancellationStarted.set(false)
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
        if (::selectedFilesList.isInitialized) (selectedFilesList.adapter as? BaseAdapter)?.notifyDataSetChanged()
        if (::selectedFilesCard.isInitialized) selectedFilesCard.visibility = View.GONE
        startTransferButton.isEnabled = false
        styleActionButton(startTransferButton, ActionButtonStyle.CONTINUE)
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
                styleActionButton(startTransferButton, ActionButtonStyle.CONTINUE)
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
        styleActionButton(startTransferButton, ActionButtonStyle.CONTINUE)
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
        cancellationStarted.set(false)
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
            io.execute { acceptAndReceive(socket, token) }
        } catch (error: Exception) {
            showStatus("Không mở được phiên nhận: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun acceptAndReceive(server: ServerSocket, token: ByteArray?) {
        try {
            while (!server.isClosed && !isFinishing) {
                var paired = false
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
                    paired = true
                    showPairedUi(FlowRole.RECEIVE)
                    receiveFiles(channel)
                    return
                } catch (error: Exception) {
                    if (paired || error is PeerCancelledException || cancellationStarted.get()) {
                        runOnUiThread {
                            pairingPanel.visibility = View.GONE
                            pairedPanel.visibility = View.GONE
                            showStatus(if (error is PeerCancelledException) "Thiết bị kia đã hủy phiên chuyển dữ liệu." else "Phiên chuyển dữ liệu đã dừng: ${error.localizedMessage ?: error.javaClass.simpleName}")
                        }
                        break
                    }
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
        cancellationStarted.set(false)
        flowRole = FlowRole.SEND
        rolePanel.visibility = View.GONE
        receivePanel.visibility = View.GONE
        sendPanel.visibility = View.VISIBLE
        pairedPanel.visibility = View.GONE
        pairingPanel.visibility = View.GONE
        requestLocalNetwork {
            stopNetworkSessionOnly()
            transferInProgress = true
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
                        showStatus(if (error is PeerCancelledException) "Thiết bị kia đã hủy phiên chuyển dữ liệu." else "Không thể gửi dữ liệu: ${error.localizedMessage ?: error.javaClass.simpleName}")
                    }
                } finally {
                    activeChannel?.close()
                    activeChannel = null
                    runCatching { activeSocket?.close() }
                    activeSocket = null
                    transferInProgress = false
                    runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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
        if (cancellationStarted.get()) throw PeerCancelledException("Phiên đã bị hủy")
        if (!locallyApproved) return false
        channel.send(PAIR_CONFIRM)
        showStatus("Đã xác nhận phía này; đang chờ xác nhận trên thiết bị còn lại…")
        val peer = receivePeerFrame(channel)
        if (peer.type != PAIR_CONFIRM) throw SecurityException("Peer did not confirm pairing")
        runOnUiThread {
            pairingPanel.visibility = View.GONE
            showStatus("Đã xác minh thiết bị an toàn.")
        }
        return true
    }

    private fun receivePeerFrame(channel: PiperDataTransferProtocol.SecureChannel): PiperDataTransferProtocol.Frame {
        val frame = channel.receive()
        when (frame.type) {
            TRANSFER_CANCEL -> throw PeerCancelledException("Thiết bị kia đã hủy phiên chuyển dữ liệu")
            TRANSFER_ERROR -> throw java.io.IOException(String(frame.payload, StandardCharsets.UTF_8).ifBlank { "Thiết bị nhận từ chối dữ liệu" })
        }
        return frame
    }

    private fun sendFiles(channel: PiperDataTransferProtocol.SecureChannel, files: List<SelectedFile>, turbo: Boolean) {
        if (files.size !in 1..MAX_FILES) throw IllegalArgumentException("Số lượng tệp vượt giới hạn phiên")
        val total = knownTotalBytes(files.map { it.size })
        if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("Tổng dữ liệu vượt giới hạn 20 GB")
        val progressTotal = if (files.any { it.size < 0L }) -1L else total
        val array = JSONArray()
        files.forEach { file ->
            array.put(JSONObject().put("name", file.name).put("size", file.size).put("mime", file.mime))
        }
        channel.send(MANIFEST, JSONObject().put("files", array).put("turbo", turbo).toString().toByteArray(StandardCharsets.UTF_8))
        val response = receivePeerFrame(channel)
        if (response.type == TRANSFER_ERROR) throw IllegalStateException(String(response.payload, StandardCharsets.UTF_8))
        if (response.type != READY) throw SecurityException("Receiver rejected transfer manifest")
        val negotiatedTurbo = turbo && runCatching { JSONObject(String(response.payload, StandardCharsets.UTF_8)).optBoolean("turbo") }.getOrDefault(false)
        val chunkSize = if (negotiatedTurbo) TURBO_CHUNK_BYTES else NORMAL_CHUNK_BYTES
        val progressId = UUID.randomUUID().toString()
        val progressItems = files.map { PiperDataTransferProgressState.Item(it.name, it.mime, it.size) }
        PiperDataTransferProgressState.begin(progressId, PiperDataTransferProgressActivity.ROLE_SEND, progressItems, progressTotal)
        activeProgressId = progressId
        getSharedPreferences("piper_data_transfer_progress", MODE_PRIVATE).edit().putString("active_session_id", progressId).apply()
        PiperDataTransferProgressState.setCancelHandler(progressId) {
            cancelCurrentSession("Bạn đã hủy phiên chuyển dữ liệu.", progressId)
        }
        refreshProgressActions()
        runOnUiThread { startActivity(PiperDataTransferProgressActivity.createIntent(this, progressId, PiperDataTransferProgressActivity.ROLE_SEND, progressItems)) }
        var sent = 0L
        try {
            files.forEachIndexed { index, item ->
                channel.send(FILE_START, ByteBuffer.allocate(4).putInt(index).array())
                var sentItem = 0L
                PiperDataTransferProgressState.update(progressId, index, sentItem, sent, progressTotal)
                contentResolver.openInputStream(item.uri)?.use { raw ->
                    BufferedInputStream(raw, if (negotiatedTurbo) TURBO_CHUNK_BYTES else NORMAL_CHUNK_BYTES).use { input ->
                        val buffer = ByteArray(chunkSize)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            val payload = ByteBuffer.allocate(4 + read).putInt(index).put(buffer, 0, read).array()
                            channel.send(FILE_CHUNK, payload)
                            sent += read
                            sentItem += read
                            if (sent > MAX_TOTAL_BYTES) throw IllegalArgumentException("Tổng dữ liệu vượt giới hạn 20 GB")
                            PiperDataTransferProgressState.update(progressId, index, sentItem, sent, progressTotal)
                        }
                    }
                } ?: throw IllegalStateException("Không đọc được ${item.name}")
                channel.send(FILE_END, ByteBuffer.allocate(4).putInt(index).array())
                PiperDataTransferProgressState.update(progressId, index + 1, 0, sent, progressTotal)
            }
            PiperDataTransferProgressState.update(progressId, files.size, 0, sent, progressTotal)
            PiperDataTransferProgressState.awaitingConfirmation(progressId)
            channel.send(TRANSFER_DONE)
            channel.setReadTimeout(30_000)
            val completed = receivePeerFrame(channel)
            if (completed.type != TRANSFER_ACK) throw SecurityException("Receiver did not confirm completed transfer")
            PiperDataTransferProgressState.complete(progressId, sent, progressTotal)
            recordProgress(progressId)
            refreshProgressActions()
            showStatus("Đã gửi xong ${files.size} mục. Thiết bị nhận mới có thể nhập dữ liệu.")
        } catch (error: Exception) {
            PiperDataTransferProgressState.fail(progressId, error.localizedMessage ?: "Kết nối bị gián đoạn")
            recordProgress(progressId)
            refreshProgressActions()
            throw error
        }
    }

    private fun receiveFiles(channel: PiperDataTransferProtocol.SecureChannel) {
        val manifestFrame = receivePeerFrame(channel)
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
        val progressTotal = if (manifest.any { it.second < 0L }) -1L else total
        val free = filesDir.usableSpace
        if (total > 0 && total > free) {
            channel.send(TRANSFER_ERROR, "Dung lượng trống không đủ trên thiết bị nhận".toByteArray())
            throw IllegalStateException("Không đủ dung lượng trống để nhận dữ liệu")
        }
        val session = File(filesDir, "piper-data-transfer/incoming/${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
        if (!session.mkdirs()) throw IllegalStateException("Không tạo được vùng lưu tạm")
        var progressId: String? = null
        try {
            val id = UUID.randomUUID().toString()
            progressId = id
            val progressItems = manifest.map { (name, size, mime) -> PiperDataTransferProgressState.Item(name, mime, size) }
            PiperDataTransferProgressState.begin(id, PiperDataTransferProgressActivity.ROLE_RECEIVE, progressItems, progressTotal)
            activeProgressId = id
            getSharedPreferences("piper_data_transfer_progress", MODE_PRIVATE).edit().putString("active_session_id", id).apply()
            PiperDataTransferProgressState.setCancelHandler(id) {
                cancelCurrentSession("Thiết bị này đã hủy phiên chuyển dữ liệu.", id)
            }
            refreshProgressActions()
            runOnUiThread { startActivity(PiperDataTransferProgressActivity.createIntent(this, id, PiperDataTransferProgressActivity.ROLE_RECEIVE, progressItems)) }
            channel.send(READY, JSONObject().put("turbo", negotiatedTurbo).toString().toByteArray(StandardCharsets.UTF_8))
            var completedBytes = 0L
            manifest.forEachIndexed { index, (name, expectedSize, mime) ->
                val start = receivePeerFrame(channel)
                if (start.type != FILE_START || start.payload.size != 4 || ByteBuffer.wrap(start.payload).int != index) {
                    throw SecurityException("Invalid incoming file order")
                }
                val file = File(session, "${index.toString().padStart(4, '0')}-$name")
                var written = 0L
                PiperDataTransferProgressState.update(id, index, 0, completedBytes, progressTotal)
                BufferedOutputStream(
                    FileOutputStream(file),
                    if (negotiatedTurbo) TURBO_CHUNK_BYTES else NORMAL_CHUNK_BYTES
                ).use { output ->
                    while (true) {
                        val frame = receivePeerFrame(channel)
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
                        PiperDataTransferProgressState.update(id, index, written, completedBytes, progressTotal)
                    }
                }
                if (expectedSize >= 0 && written != expectedSize) throw SecurityException("File length does not match manifest")
                PiperDataTransferProgressState.update(id, index + 1, 0, completedBytes, progressTotal)
            }
            val done = receivePeerFrame(channel)
            if (done.type != TRANSFER_DONE) throw SecurityException("Transfer did not finish cleanly")
            File(session, ".complete").writeText("PiperOS Data Transfer v1\n${manifest.size}\n")
            channel.send(TRANSFER_ACK)
            showStatus("Đã nhận đủ ${manifest.size} mục. Đang mở danh sách dữ liệu đã nhận.")
            PiperDataTransferProgressState.complete(id, completedBytes, progressTotal, session.absolutePath)
            recordProgress(id)
            refreshProgressActions()
        } catch (error: Exception) {
            progressId?.let {
                PiperDataTransferProgressState.fail(it, error.localizedMessage ?: "Kết nối bị gián đoạn")
                recordProgress(it)
                refreshProgressActions()
            }
            session.deleteRecursively()
            throw error
        }
    }

    private fun loadCompletedSession() {
        val incoming = File(filesDir, "piper-data-transfer/incoming")
        val latest = incoming.listFiles()?.filter { File(it, ".complete").isFile }?.maxByOrNull(File::lastModified) ?: return
        if (latest.listFiles()?.any { it.isFile && it.name != ".complete" } == true) {
            startActivity(Intent(this, PiperDataTransferReceivedActivity::class.java)
                .putExtra(PiperDataTransferReceivedActivity.EXTRA_SESSION_DIR, latest.absolutePath))
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
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        content.addView(sectionTitle("Chọn ứng dụng để gửi"), bottom(5))
        content.addView(text("Icon, tên gói và dung lượng APK được hiển thị trước khi thêm.", 12f, false), bottom(9))
        val search = EditText(this).apply {
            hint = "Tìm ứng dụng"
            isSingleLine = true
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(PiperModernUi.surfaceColor(this@PiperDataTransferActivity))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), PiperModernUi.borderColor(this@PiperDataTransferActivity))
            }
            setTextColor(PiperModernUi.textColor(this@PiperDataTransferActivity))
            setHintTextColor(PiperModernUi.secondaryTextColor(this@PiperDataTransferActivity))
        }
        content.addView(search, bottom(9))
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(PiperModernUi.borderColor(this@PiperDataTransferActivity))
            dividerHeight = dp(1)
            setBackgroundColor(Color.TRANSPARENT)
            isNestedScrollingEnabled = true
        }
        content.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.48f).toInt()))

        val adapter = object : BaseAdapter() {
            private var visibleItems = candidates
            fun filter(query: String) {
                val needle = query.trim().lowercase(Locale.ROOT)
                visibleItems = if (needle.isEmpty()) candidates else candidates.filter {
                    it.label.lowercase(Locale.ROOT).contains(needle) || it.packageName.lowercase(Locale.ROOT).contains(needle)
                }
                notifyDataSetChanged()
            }
            override fun getCount(): Int = visibleItems.size
            override fun getItem(position: Int): InstalledBaseApk = visibleItems[position]
            override fun getItemId(position: Int): Long = visibleItems[position].packageName.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val item = getItem(position)
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@PiperDataTransferActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    addView(ImageView(this@PiperDataTransferActivity).apply {
                        tag = "app-icon"
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(LinearLayout(this@PiperDataTransferActivity).apply {
                        tag = "app-text"
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        addView(TextView(this@PiperDataTransferActivity).apply { tag = "app-name"; textSize = 14f; maxLines = 1; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(PiperModernUi.textColor(this@PiperDataTransferActivity)) })
                        addView(TextView(this@PiperDataTransferActivity).apply { tag = "app-detail"; textSize = 11f; maxLines = 2; setTextColor(PiperModernUi.secondaryTextColor(this@PiperDataTransferActivity)) }, top(3))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                val icon = row.getChildAt(0) as ImageView
                val textColumn = row.getChildAt(1) as LinearLayout
                val name = textColumn.findViewWithTag<TextView>("app-name")
                val detail = textColumn.findViewWithTag<TextView>("app-detail")
                icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(item.packageName) }.getOrNull() ?: packageManager.defaultActivityIcon)
                name.text = item.label
                detail.text = "${item.packageName}\n${formatBytes(File(item.sourcePath).length())} APK"
                return row
            }
        }
        list.adapter = adapter
        val dialog = PiperDialog.createContent(this, content).apply { show() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { adapter.filter(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val selected = adapter.getItem(position)
            dialog.dismiss()
            stageInstalledApk(selected)
        }
    }

    private fun selectedFilesAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount(): Int = chosenFiles.size
        override fun getItem(position: Int): SelectedFile = chosenFiles[position]
        override fun getItemId(position: Int): Long = getItem(position).uri.toString().hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val item = getItem(position)
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@PiperDataTransferActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(5), dp(7))
                val details = LinearLayout(this@PiperDataTransferActivity).apply {
                    tag = "selected-details"
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@PiperDataTransferActivity).apply {
                        tag = "selected-name"; textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(PiperModernUi.textColor(this@PiperDataTransferActivity))
                    })
                    addView(TextView(this@PiperDataTransferActivity).apply {
                        tag = "selected-meta"; textSize = 10f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(PiperModernUi.secondaryTextColor(this@PiperDataTransferActivity))
                    }, top(3))
                }
                addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                addView(MaterialButton(this@PiperDataTransferActivity).apply {
                    tag = "selected-preview"; text = "Xem"; isAllCaps = false; textSize = 10f
                    minWidth = dp(48); minHeight = dp(36); insetTop = 0; insetBottom = 0; cornerRadius = dp(10)
                }, LinearLayout.LayoutParams(dp(55), dp(38)).apply { marginStart = dp(4) })
                addView(MaterialButton(this@PiperDataTransferActivity).apply {
                    tag = "selected-remove"; text = "Xóa"; isAllCaps = false; textSize = 10f
                    minWidth = dp(48); minHeight = dp(36); insetTop = 0; insetBottom = 0; cornerRadius = dp(10)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperDataTransferActivity))
                    setTextColor(PiperModernUi.secondaryTextColor(this@PiperDataTransferActivity))
                    strokeWidth = dp(1); strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this@PiperDataTransferActivity))
                }, LinearLayout.LayoutParams(dp(55), dp(38)).apply { marginStart = dp(3) })
            }
            val details = row.findViewWithTag<LinearLayout>("selected-details")
            details.findViewWithTag<TextView>("selected-name").text = item.name
            details.findViewWithTag<TextView>("selected-meta").text = "${item.mime} • ${formatBytes(item.size)}"
            row.findViewWithTag<MaterialButton>("selected-preview").setOnClickListener { showSelectedPreview(item) }
            row.findViewWithTag<MaterialButton>("selected-remove").setOnClickListener {
                chosenFiles = chosenFiles.filterNot { it.uri == item.uri }
                refreshChosenSelection("Đã bỏ ${item.name} khỏi danh sách truyền.")
            }
            return row
        }
    }

    private fun refreshChosenSelection(statusMessage: String? = null) {
        val total = knownTotalBytes(chosenFiles.map { it.size })
        selectedFilesLabel.text = if (chosenFiles.isEmpty()) "Chưa chọn dữ liệu" else "Đã chọn ${chosenFiles.size} mục • ${formatBytes(total)}"
        (selectedFilesList.adapter as? BaseAdapter)?.notifyDataSetChanged()
        if (::selectedFilesCard.isInitialized) selectedFilesCard.visibility = if (chosenFiles.isEmpty()) View.GONE else View.VISIBLE
        startTransferButton.isEnabled = chosenFiles.isNotEmpty()
        styleActionButton(startTransferButton, ActionButtonStyle.CONTINUE)
        statusMessage?.let(::showStatus)
    }

    private fun showSelectedPreview(item: SelectedFile) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        content.addView(text(item.name, 16f, true), bottom(4))
        content.addView(text("${item.mime} • ${formatBytes(item.size)}", 12f, false), bottom(10))
        var previewPlayer: ExoPlayer? = null
        when {
            item.mime == "application/vnd.android.package-archive" -> {
                val application = item.sourceAppPackage?.let { appId ->
                    runCatching { packageManager.getApplicationInfo(appId, 0) }.getOrNull()
                }
                val apkHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    addView(ImageView(this@PiperDataTransferActivity).apply {
                        setImageDrawable(application?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }
                            ?: packageManager.defaultActivityIcon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(dp(56), dp(56)))
                    val appLabel = application?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
                    addView(LinearLayout(this@PiperDataTransferActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(appLabel ?: "Gói cài đặt APK", 15f, true))
                        addView(text(item.sourceAppPackage ?: "APK sẽ được kiểm tra trên thiết bị nhận", 12f, false), top(4))
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                }
                content.addView(card(apkHeader), bottom(10))
                content.addView(text(
                    "Tệp APK này sẽ được gửi nguyên vẹn. Trên thiết bị nhận, Android sẽ mở trình cài đặt để bạn xác nhận.",
                    13f, false
                ), bottom(10))
            }
            item.mime.startsWith("image/") -> {
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; maxHeight = dp(360) }
                content.addView(image, LinearLayout.LayoutParams(-1, dp(320)))
                Glide.with(this).load(item.uri).fitCenter().into(image)
            }
            item.mime.startsWith("video/") -> {
                val playerView = PlayerView(this).apply { useController = true; setBackgroundColor(Color.BLACK) }
                content.addView(playerView, LinearLayout.LayoutParams(-1, dp(230)))
                previewPlayer = ExoPlayer.Builder(this).build().also { player ->
                    playerView.player = player
                    player.setMediaItem(MediaItem.fromUri(item.uri))
                    player.prepare()
                    player.playWhenReady = false
                }
            }
            else -> {
                content.addView(text(
                    "Đã chọn đúng tệp. Mở trong ứng dụng phù hợp để xem nội dung trước khi gửi.",
                    13f, false
                ), bottom(10))
                content.addView(button("Mở bằng ứng dụng phù hợp") {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(item.uri, item.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }.onFailure { Toast.makeText(this, "Không tìm thấy ứng dụng xem loại tệp này.", Toast.LENGTH_SHORT).show() }
                })
            }
        }
        PiperDialog.createContent(this, content).apply {
            setOnDismissListener { previewPlayer?.release() }
            show()
        }
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
                val selected = SelectedFile(
                    uri,
                    safeName("${app.label}-${app.packageName.substringAfterLast('.')}.apk"),
                    copy.length(),
                    "application/vnd.android.package-archive",
                    app.packageName
                )
                runOnUiThread {
                    appendChosenFiles(listOf(selected))
                    showStatus("Đã thêm ${app.label}. Có ${chosenFiles.size} mục để gửi • ${formatBytes(knownTotalBytes(chosenFiles.map { it.size }))}")
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

    private fun showStatus(message: String) = runOnUiThread { status.text = message }

    private fun confirmCancelSession() {
        PiperDialog.showConfirm(
            this,
            "Hủy phiên chuyển dữ liệu?",
            "Phiên sẽ dừng ở cả hai thiết bị. Tệp chưa nhận đủ sẽ bị loại bỏ.",
            "Hủy phiên",
            destructive = true
        ) { cancelCurrentSession("Bạn đã hủy phiên chuyển dữ liệu.") }
    }

    private fun cancelCurrentSession(message: String, progressId: String? = activeProgressId) {
        if (!cancellationStarted.compareAndSet(false, true)) return
        approval.getAndSet(null)?.countDown()
        senderApproval.getAndSet(null)?.countDown()
        approvedFiles.set(null)
        progressId?.let { id ->
            PiperDataTransferProgressState.fail(id, message)
            recordProgress(id)
            refreshProgressActions()
        }
        val channel = activeChannel
        io.execute {
            runCatching { channel?.send(TRANSFER_CANCEL, message.toByteArray(StandardCharsets.UTF_8)) }
            stopSession()
            runOnUiThread {
                if (!isFinishing) {
                    resetToRoleSelection()
                    showStatus(message)
                }
            }
        }
    }

    private fun recordProgress(sessionId: String) {
        PiperDataTransferProgressState.snapshot(sessionId)?.let { PiperDataTransferHistoryStore.record(this, it) }
    }

    private fun refreshProgressActions() {
        if (!::currentProgressButton.isInitialized) return
        runOnUiThread {
            val preferences = getSharedPreferences("piper_data_transfer_progress", MODE_PRIVATE)
            val latestEntry = PiperDataTransferHistoryStore.read(this).firstOrNull()
            val preferenceId = preferences.getString("active_session_id", null)
            val rememberedId = activeProgressId?.takeIf { PiperDataTransferProgressState.snapshot(it) != null }
                ?: preferenceId?.takeIf { id ->
                    PiperDataTransferProgressState.snapshot(id) != null || PiperDataTransferHistoryStore.read(this).any { it.snapshot.sessionId == id }
                }
                ?: latestEntry?.snapshot?.sessionId
            val snapshot = rememberedId?.let(PiperDataTransferProgressState::snapshot)
            val historyMatch = rememberedId?.let { id ->
                PiperDataTransferHistoryStore.read(this).firstOrNull { it.snapshot.sessionId == id }
            }
            val hasProgress = snapshot != null || historyMatch != null
            currentProgressButton.visibility = if (hasProgress) View.VISIBLE else View.GONE
            currentProgressButton.text = when {
                snapshot != null && !snapshot.complete && snapshot.error == null -> "Mở tiến trình hiện tại"
                hasProgress -> "Xem tiến trình gần nhất"
                else -> "Mở tiến trình hiện tại"
            }
            val canDelete = snapshot?.let { it.complete || it.error != null } == true || historyMatch != null
            deleteProgressButton.visibility = if (canDelete) View.VISIBLE else View.GONE
            historyButton.visibility = View.VISIBLE
        }
    }

    private fun confirmDeleteLatestProgress() {
        val entries = PiperDataTransferHistoryStore.read(this)
        val preferences = getSharedPreferences("piper_data_transfer_progress", MODE_PRIVATE)
        val rememberedId = activeProgressId ?: preferences.getString("active_session_id", null)
        val entry = rememberedId?.let { id -> entries.firstOrNull { it.snapshot.sessionId == id } } ?: entries.firstOrNull()
        val inMemory = rememberedId?.let(PiperDataTransferProgressState::snapshot)
        if (inMemory != null && !inMemory.complete && inMemory.error == null) {
            PiperDialog.showMessage(this, "Tiến trình đang chạy", "Hãy đợi phiên hoàn tất hoặc hủy phiên trước khi xóa tiến trình.")
            return
        }
        val sessionId = entry?.snapshot?.sessionId ?: inMemory?.sessionId
        if (sessionId == null) {
            PiperDialog.showMessage(this, "Không có tiến trình để xóa", "Chưa có tiến trình đã hoàn tất hoặc đã dừng.")
            return
        }
        PiperDialog.showConfirm(
            this,
            "Xóa tiến trình gần nhất?",
            "Thông tin tiến trình sẽ bị xóa khỏi lịch sử. Dữ liệu đã nhận sẽ được giữ nguyên.",
            "Xóa",
            destructive = true
        ) {
            forgetProgress(sessionId)
            refreshProgressActions()
            showStatus("Đã xóa tiến trình gần nhất.")
        }
    }

    private fun forgetProgress(sessionId: String) {
        PiperDataTransferHistoryStore.delete(this, sessionId)
        PiperDataTransferProgressState.remove(sessionId)
        if (activeProgressId == sessionId) activeProgressId = null
        val preferences = getSharedPreferences("piper_data_transfer_progress", MODE_PRIVATE)
        if (preferences.getString("active_session_id", null) == sessionId) {
            val nextId = PiperDataTransferHistoryStore.read(this).firstOrNull()?.snapshot?.sessionId
            preferences.edit().putString("active_session_id", nextId).apply()
        }
    }

    private fun openCurrentProgress() {
        val prefs = getSharedPreferences("piper_data_transfer_progress", MODE_PRIVATE)
        val entries = PiperDataTransferHistoryStore.read(this)
        val entry = entries.firstOrNull()
        val preferenceId = prefs.getString("active_session_id", null)
        val id = activeProgressId?.takeIf { PiperDataTransferProgressState.snapshot(it) != null }
            ?: preferenceId?.takeIf { id -> PiperDataTransferProgressState.snapshot(id) != null || entries.any { it.snapshot.sessionId == id } }
            ?: entry?.snapshot?.sessionId
        val snapshot = id?.let(PiperDataTransferProgressState::snapshot)
        if (snapshot != null) {
            startActivity(PiperDataTransferProgressActivity.createIntent(this, snapshot.sessionId, snapshot.role, snapshot.items))
            return
        }
        if (entry != null) showHistoryDetails(entry)
        else PiperDialog.showMessage(this, "Chưa có tiến trình", "Tiến trình chuyển dữ liệu sẽ xuất hiện ở đây sau khi bắt đầu gửi hoặc nhận.")
    }

    private fun showTransferHistory() {
        val entries = PiperDataTransferHistoryStore.read(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(sectionTitle("Lịch sử truyền dữ liệu"), bottom(5))
            addView(text("Chạm vào một phiên để xem trạng thái và danh sách tệp.", 12f, false), bottom(10))
        }
        val dialog = PiperDialog.createContent(this, content)
        if (entries.isNotEmpty()) {
            content.addView(button("Xóa toàn bộ lịch sử") {
                PiperDialog.showConfirm(
                    this,
                    "Xóa toàn bộ lịch sử?",
                    "Toàn bộ bản ghi tiến trình sẽ bị xóa. Dữ liệu đã nhận vẫn được giữ nguyên.",
                    "Xóa lịch sử",
                    destructive = true
                ) {
                    entries.forEach { entry -> forgetProgress(entry.snapshot.sessionId) }
                    PiperDataTransferHistoryStore.clear(this)
                    activeProgressId?.let { id ->
                        val active = PiperDataTransferProgressState.snapshot(id)
                        if (active?.complete == true || active?.error != null) PiperDataTransferProgressState.remove(id)
                    }
                    activeProgressId = PiperDataTransferProgressState.snapshot(activeProgressId.orEmpty())?.sessionId
                    getSharedPreferences("piper_data_transfer_progress", MODE_PRIVATE).edit()
                        .putString("active_session_id", activeProgressId).apply()
                    dialog.dismiss()
                    refreshProgressActions()
                    showTransferHistory()
                }
            }.apply { styleActionButton(this, ActionButtonStyle.DESTRUCTIVE) }, bottom(10))
        }
        if (entries.isEmpty()) {
            content.addView(text("Chưa có phiên truyền nào được lưu.", 13f, false))
        } else entries.forEach { entry ->
            val snapshot = entry.snapshot
            val result = when {
                snapshot.complete -> "Hoàn tất"
                snapshot.error != null -> "Đã dừng: ${snapshot.error}"
                else -> "Đã lưu tiến trình"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                addView(text(if (snapshot.role == PiperDataTransferProgressActivity.ROLE_SEND) "Gửi dữ liệu" else "Nhận dữ liệu", 14f, true))
                addView(text("${snapshot.items.size} mục • ${formatBytes(snapshot.transferred)} • $result", 12f, false), top(4))
                addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.savedAt)), 11f, false), top(4))
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val detailCard = card(row).apply {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showHistoryDetails(entry) }
                }
                addView(detailCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(7) })
                addView(button("Xóa") {
                    PiperDialog.showConfirm(
                        this@PiperDataTransferActivity,
                        "Xóa phiên này?",
                        "Chỉ bản ghi lịch sử bị xóa; dữ liệu đã nhận vẫn được giữ nguyên.",
                        "Xóa",
                        destructive = true
                    ) {
                        forgetProgress(snapshot.sessionId)
                        dialog.dismiss()
                        refreshProgressActions()
                        showTransferHistory()
                    }
                }.apply { styleActionButton(this, ActionButtonStyle.DESTRUCTIVE) }, LinearLayout.LayoutParams(dp(78), -2))
            }
            content.addView(actions, bottom(8))
        }
        dialog.show()
    }

    private fun showHistoryDetails(entry: PiperDataTransferHistoryStore.Entry) {
        val snapshot = entry.snapshot
        val result = when {
            snapshot.complete -> "Hoàn tất"
            snapshot.error != null -> "Đã dừng: ${snapshot.error}"
            else -> "Tiến trình đã lưu"
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(sectionTitle(if (snapshot.role == PiperDataTransferProgressActivity.ROLE_SEND) "Chi tiết phiên gửi" else "Chi tiết phiên nhận"), bottom(5))
            addView(text(
                "$result\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.savedAt))}\n${formatBytes(snapshot.transferred)} / ${formatBytes(snapshot.total)}",
                12f, false
            ), bottom(10))
        }
        snapshot.items.forEach { item ->
            val detail = when (item.state) {
                PiperDataTransferProgressState.ItemState.COMPLETE -> "Hoàn tất • ${formatBytes(item.size)}"
                PiperDataTransferProgressState.ItemState.ACTIVE -> "${formatBytes(item.transferred)} / ${formatBytes(item.size)}"
                PiperDataTransferProgressState.ItemState.WAITING -> "Chưa truyền • ${formatBytes(item.size)}"
            }
            content.addView(card(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(text(item.name, 13f, true))
                addView(text("${item.mime} • $detail", 11f, false), top(4))
            }), bottom(6))
        }
        snapshot.receivedDirectory?.let { path ->
            val directory = File(path)
            if (directory.isDirectory && File(directory, ".complete").isFile) {
                content.addView(button("Mở dữ liệu đã nhận") {
                    startActivity(Intent(this, PiperDataTransferReceivedActivity::class.java)
                        .putExtra(PiperDataTransferReceivedActivity.EXTRA_SESSION_DIR, path))
                }, top(8))
            }
        }
        PiperDialog.createContent(this, content).show()
    }

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

    private fun styleActionButton(button: MaterialButton, style: ActionButtonStyle) {
        val fill = if (!button.isEnabled && style != ActionButtonStyle.BACK) {
            Color.rgb(145, 151, 160)
        } else when (style) {
            ActionButtonStyle.PRIMARY -> PiperModernUi.accentColor(this)
            ActionButtonStyle.CONTINUE -> Color.rgb(34, 142, 87)
            ActionButtonStyle.BACK -> PiperModernUi.surfaceColor(this)
            ActionButtonStyle.DESTRUCTIVE -> Color.rgb(190, 52, 60)
        }
        val textColor = if (style == ActionButtonStyle.BACK) {
            PiperModernUi.textColor(this)
        } else Color.WHITE
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(fill)
        button.setTextColor(textColor)
        button.strokeWidth = if (style == ActionButtonStyle.BACK) dp(1) else 0
        button.strokeColor = android.content.res.ColorStateList.valueOf(
            if (style == ActionButtonStyle.BACK) PiperModernUi.borderColor(this) else fill
        )
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
