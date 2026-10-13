package com.piperostool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/** TOTP authenticator for third-party services, synced to the signed-in Firebase account. */
class BetaTwoFactorActivity : AppCompatActivity() {
    private data class Token(
        val id: String,
        val label: String,
        val secret: String,
        val algorithm: String = "SHA1",
        val digits: Int = 6,
        val period: Int = 30,
        val autoRefresh: Boolean = true,
        val type: String = "TOTP",
        val counter: Long = 0
    )

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())
    private val tokens = mutableListOf<Token>()
    private val codeViews = mutableMapOf<String, TextView>()
    private val countdownViews = mutableMapOf<String, TextView>()
    private val currentCodes = mutableMapOf<String, String>()
    private val codeSteps = mutableMapOf<String, Long>()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var addBubble: MaterialButton
    private lateinit var searchInput: EditText
    private var searchQuery = ""
    private var loading = false
    private var listener: com.google.firebase.firestore.ListenerRegistration? = null
    private val migrationChunks = mutableMapOf<Int, List<GoogleAuthMigrationCodec.Entry>>()
    private var migrationBatchId: Long? = null
    private var expectedMigrationChunks = 0

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleScannedQr(contents)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val data = exportJson().toString(2)
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(data) }
                ?: error("Không mở được tệp để ghi")
        }.onSuccess { PiperDialog.showMessage(this, "Xuất 2FA thành công", "Đã lưu ${tokens.size} tài khoản. Hãy giữ tệp JSON ở nơi an toàn vì tệp chứa khóa bí mật.") }
            .onFailure { fail("Không xuất được dữ liệu: ${it.localizedMessage}") }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val parsed = runCatching {
            val source = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Không đọc được tệp")
            parseImportJson(source)
        }.getOrElse {
            PiperDialog.showMessage(this, "Không đọc được tệp 2FA", it.localizedMessage ?: "Định dạng JSON không hợp lệ")
            return@registerForActivityResult
        }
        if (parsed.isEmpty()) {
            PiperDialog.showMessage(this, "Tệp 2FA trống", "Không tìm thấy tài khoản hợp lệ trong tệp đã chọn.")
            return@registerForActivityResult
        }
        PiperDialog.showConfirm(
            this,
            "Nhập tài khoản 2FA",
            "Sẽ thêm ${parsed.size} tài khoản vào Firebase của tài khoản đang đăng nhập. Các mục hiện có sẽ được giữ nguyên.",
            "Nhập"
        ) { importTokens(parsed) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshTokenCodes()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        title = "PiperOS 2FA"

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(13, 31, 44)) }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(100))
        }
        scroll.addView(root)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        addBubble = MaterialButton(this).apply {
            text = "+"
            textSize = 30f
            setTextColor(Color.WHITE)
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(32)
            minimumWidth = dp(62)
            minimumHeight = dp(62)
            setPadding(0, 0, 0, dp(3))
            contentDescription = "Thêm tài khoản 2FA"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 190, 170))
            elevation = dp(8).toFloat()
            setOnClickListener { showAddChoices() }
        }
        frame.addView(addBubble, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20)
            bottomMargin = dp(20)
        })
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(addBubble) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = bars.bottom + dp(20)
                view.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        ViewCompat.requestApplyInsets(addBubble)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("PiperOS 2FA", 24, true), LinearLayout.LayoutParams(0, -2, 1f))
        val searchButton = MaterialButton(this).apply {
            setIconResource(android.R.drawable.ic_menu_search)
            iconSize = dp(24)
            iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Tìm kiếm tài khoản 2FA"
            isAllCaps = false
            minWidth = dp(44)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { toggleSearch() }
        }
        header.addView(searchButton)
        header.addView(MaterialButton(this).apply {
            text = "Nhập"
            isAllCaps = false
            setOnClickListener { showImportChoices() }
        })
        header.addView(MaterialButton(this).apply {
            text = "Xuất"
            isAllCaps = false
            setOnClickListener { showExportChoices() }
        })
        root.addView(header)
        searchInput = EditText(this).apply {
            hint = "Tìm theo tên dịch vụ hoặc tài khoản"
            isSingleLine = true
            visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty().trim()
                    renderTokens()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        root.addView(searchInput)
        root.addView(text("Mã xác minh cho Facebook và các dịch vụ hỗ trợ ứng dụng xác thực. Khóa 2FA được lưu trên Firebase để đồng bộ theo tài khoản PiperOS; hãy bảo vệ tài khoản đăng nhập như bảo vệ mật khẩu.", 14, false, 12))
        status = text("Đang tải tài khoản 2FA…", 13, false, 18).also { root.addView(it) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        val uid = auth.currentUser?.uid
        if (uid == null) {
            status.text = "Hãy đăng nhập PiperOS để đồng bộ tài khoản 2FA."
            addBubble.isEnabled = false
        } else {
            listener = firestore.collection("users").document(uid).collection("totpAccounts")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        status.text = "Không tải được tài khoản 2FA: ${error.localizedMessage}"
                        return@addSnapshotListener
                    }
                    tokens.clear()
                    snapshot?.documents?.forEach { doc ->
                        val secret = doc.getString("secret") ?: return@forEach
                        val label = doc.getString("label") ?: "Tài khoản 2FA"
                        tokens += Token(
                            id = doc.id,
                            label = label,
                            secret = secret,
                            algorithm = doc.getString("algorithm") ?: "SHA1",
                            digits = doc.getLong("digits")?.toInt() ?: 6,
                            period = doc.getLong("period")?.toInt() ?: 30,
                            autoRefresh = doc.getBoolean("autoRefresh") ?: true,
                            type = doc.getString("type") ?: "TOTP",
                            counter = doc.getLong("counter") ?: 0L
                        )
                    }
                    status.text = if (tokens.isEmpty()) "Chưa có tài khoản 2FA. Nhấn + để thêm." else "${tokens.size} tài khoản · đã đồng bộ với Firebase"
                    renderTokens()
                }
        }
    }

    override fun onResume() { super.onResume(); handler.removeCallbacks(ticker); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { listener?.remove(); super.onDestroy() }

    private fun showAddChoices() {
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null
        fun option(title: String, description: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.rgb(21, 49, 68), Color.rgb(61, 91, 110), 14)
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog?.dismiss(); action() }
            }
            row.addView(text(title, 16, true))
            row.addView(text(description, 12, false, 4))
            choices.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        option("Thêm thủ công", "Nhập tên dịch vụ và khóa thiết lập 2FA") { showTokenEditor() }
        option("Quét mã QR", "Quét QR otpauth bằng trình quét PiperOS") { startQrScan() }
        dialog = PiperDialog.showCustom(
            context = this,
            title = "Thêm tài khoản 2FA",
            content = choices,
            positiveLabel = "Đóng",
            negativeLabel = null,
            onPositive = { true }
        )
    }

    private fun startQrScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Quét mã QR xác thực 2FA")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(PiperQrCaptureActivity::class.java)
        )
    }

    private fun showImportChoices() {
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null
        fun option(title: String, description: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.rgb(21, 49, 68), Color.rgb(61, 91, 110), 14)
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog?.dismiss(); action() }
            }
            row.addView(text(title, 16, true))
            row.addView(text(description, 12, false, 4))
            choices.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        option("Quét QR Google Authenticator", "Quét một hoặc nhiều QR trong mục Chuyển tài khoản → Xuất tài khoản") { startQrScan() }
        option("Nhập tệp PiperOS JSON", "Chọn bản sao lưu .json đã xuất từ PiperOS 2FA") {
            importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        dialog = PiperDialog.showCustom(this, "Nhập tài khoản 2FA", content = choices, positiveLabel = "Đóng", negativeLabel = null, onPositive = { true })
    }

    private fun showExportChoices() {
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null
        fun option(title: String, description: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.rgb(21, 49, 68), Color.rgb(61, 91, 110), 14)
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog?.dismiss(); action() }
            }
            row.addView(text(title, 16, true))
            row.addView(text(description, 12, false, 4))
            choices.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        option("Hiển thị QR Google Authenticator", "Quét QR để chuyển tài khoản sang Google Authenticator hoặc ứng dụng tương thích") { showGoogleQrExport() }
        option("Xuất tệp PiperOS JSON", "Tạo tệp sao lưu có thể nhập lại trong PiperOS") { confirmExport() }
        dialog = PiperDialog.showCustom(this, "Xuất tài khoản 2FA", content = choices, positiveLabel = "Đóng", negativeLabel = null, onPositive = { true })
    }

    private fun handleScannedQr(contents: String) {
        if (contents.startsWith("otpauth-migration://", true)) {
            val payload = runCatching { GoogleAuthMigrationCodec.decode(contents) }.getOrElse {
                PiperDialog.showMessage(this, "Không đọc được QR", it.localizedMessage ?: "QR Google Authenticator không hợp lệ")
                return
            }
            runCatching { addMigrationChunk(payload) }.onFailure {
                clearMigrationBatch()
                PiperDialog.showMessage(this, "QR không hợp lệ", it.localizedMessage ?: "Không thể đọc đủ các phần QR.")
            }
        } else if (contents.startsWith("otpauth://", true)) {
            showTokenEditor(seed = contents, label = "")
        } else {
            PiperDialog.showMessage(this, "QR không được hỗ trợ", "Hãy quét QR xuất tài khoản của Google Authenticator hoặc mã thiết lập otpauth://totp.")
        }
    }

    private fun addMigrationChunk(payload: GoogleAuthMigrationCodec.Payload) {
        val currentId = migrationBatchId
        if (currentId != payload.batchId) {
            migrationChunks.clear()
            migrationBatchId = payload.batchId
            expectedMigrationChunks = payload.batchSize
        }
        require(payload.batchSize == expectedMigrationChunks) { "Số phần của QR không đồng nhất" }
        migrationChunks[payload.batchIndex] = payload.entries
        val collected = migrationChunks.values.sumOf { it.size }
        if (migrationChunks.size == expectedMigrationChunks) {
            val entries = (0 until expectedMigrationChunks).flatMap { migrationChunks[it].orEmpty() }
            clearMigrationBatch()
            confirmMigrationImport(entries)
            return
        }
        val remaining = expectedMigrationChunks - migrationChunks.size
        PiperDialog.showCustom(
            context = this,
            title = "Đã quét $collected tài khoản",
            message = "Google chia dữ liệu thành ${expectedMigrationChunks} QR. Đã nhận ${migrationChunks.size}/${expectedMigrationChunks} phần; hãy mở QR tiếp theo trên màn hình Google Authenticator.",
            positiveLabel = "Quét QR tiếp",
            negativeLabel = "Nhập $collected mã",
            onPositive = { startQrScan(); true },
            onNegative = {
                val entries = migrationChunks.toSortedMap().values.flatten()
                clearMigrationBatch()
                confirmMigrationImport(entries)
            }
        )
    }

    private fun clearMigrationBatch() {
        migrationChunks.clear()
        migrationBatchId = null
        expectedMigrationChunks = 0
    }

    private fun confirmMigrationImport(entries: List<GoogleAuthMigrationCodec.Entry>) {
        val imported = entries.map { entry ->
            require(entry.digits in 6..8 && entry.algorithm in setOf("SHA1", "SHA256", "SHA512")) { "Tài khoản ${entry.label} có thông số không hỗ trợ" }
            val label = if (entry.issuer.isNotBlank() && !entry.label.startsWith("${entry.issuer}:")) "${entry.issuer}: ${entry.label}" else entry.label
            Token(UUID.randomUUID().toString(), label, entry.secret, entry.algorithm, entry.digits, 30, autoRefresh = entry.type == 2, type = if (entry.type == 1) "HOTP" else "TOTP", counter = entry.counter)
        }
        confirmImport(imported, "QR Google Authenticator")
    }

    private fun confirmImport(imported: List<Token>, source: String = "tệp 2FA") {
        if (imported.isEmpty()) {
            PiperDialog.showMessage(this, "Không có tài khoản", "Không tìm thấy tài khoản TOTP trong $source.")
            return
        }
        PiperDialog.showConfirm(
            this,
            "Nhập tài khoản 2FA",
            "Tìm thấy ${imported.size} tài khoản từ $source. Các tài khoản hiện có sẽ được giữ nguyên.",
            "Nhập"
        ) { importTokens(imported) }
    }

    private fun showGoogleQrExport() {
        if (tokens.isEmpty()) {
            PiperDialog.showMessage(this, "Chưa có tài khoản 2FA", "Thêm ít nhất một tài khoản trước khi xuất QR.")
            return
        }
        val eligible = tokens.filter { it.type in setOf("TOTP", "HOTP") && (it.type == "HOTP" || it.period == 30) && it.digits in setOf(6, 8) && it.algorithm in setOf("SHA1", "SHA256", "SHA512") }
        if (eligible.isEmpty()) {
            PiperDialog.showMessage(this, "Không có tài khoản tương thích", "QR Google Authenticator hỗ trợ mã TOTP SHA1/SHA256/SHA512 có 6 hoặc 8 số và chu kỳ 30 giây.")
            return
        }
        val excluded = tokens.size - eligible.size
        val batchId = (System.currentTimeMillis() and 0x7fffffff).coerceAtLeast(1)
        val pages = eligible.chunked(5).mapIndexed { index, chunk ->
            val entries = chunk.map { token ->
                val issuer = token.label.substringBefore(':', "")
                val name = if (issuer.isNotBlank()) token.label.substringAfter(':').trim() else token.label
                GoogleAuthMigrationCodec.Entry(name, issuer, token.secret, token.algorithm, token.digits, if (token.type == "HOTP") 1 else 2, token.counter)
            }
            GoogleAuthMigrationCodec.encode(entries, (eligible.size + 4) / 5, index, batchId)
        }
        val message = buildString {
            append("Quét từng QR theo thứ tự trên Google Authenticator: Chuyển tài khoản → Nhập tài khoản. Hỗ trợ TOTP/HOTP; QR chứa khóa bí mật.")
            if (pages.size > 1) append(" Có ${pages.size} QR, hãy quét hết các phần.")
            if (excluded > 0) append(" $excluded tài khoản (Steam Guard hoặc chu kỳ/số chữ số riêng) không tương thích QR Google nên bị bỏ qua.")
        }
        PiperDialog.showConfirm(this, "Xuất QR Google Authenticator", message, "Hiển thị QR") {
            showGoogleQrPage(pages, 0)
        }
    }

    private fun showGoogleQrPage(pages: List<String>, initialPage: Int) {
        var page = initialPage
        var dialog: android.app.Dialog? = null
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = text("", 14, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val previous = MaterialButton(this).apply { text = "‹ Trước"; isAllCaps = false }
        val next = MaterialButton(this).apply { text = "Tiếp ›"; isAllCaps = false }
        controls.addView(previous)
        controls.addView(next)
        content.addView(image, LinearLayout.LayoutParams(dp(260), dp(260)))
        content.addView(label)
        content.addView(controls)
        fun render() {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(pages[page], BarcodeFormat.QR_CODE, 900, 900, hints)
            image.setImageBitmap(Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888).also { bitmap ->
                for (y in 0 until 900) for (x in 0 until 900) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            })
            label.text = "QR ${page + 1}/${pages.size} · quét lần lượt tất cả các phần"
            previous.isEnabled = page > 0
            next.text = if (page == pages.lastIndex) "Xong" else "Tiếp ›"
        }
        previous.setOnClickListener { if (page > 0) { page--; render() } }
        next.setOnClickListener {
            if (page < pages.lastIndex) { page++; render() } else dialog?.dismiss()
        }
        render()
        dialog = PiperDialog.showCustom(this, "QR Google Authenticator", message = "Giữ QR trên màn hình và quét bằng Google Authenticator.", content = content, positiveLabel = "Đóng", negativeLabel = null, onPositive = { true })
    }

    private fun toggleSearch() {
        if (searchInput.visibility == View.VISIBLE) {
            if (searchQuery.isNotEmpty()) searchInput.text.clear()
            else searchInput.visibility = View.GONE
        } else {
            searchInput.visibility = View.VISIBLE
            searchInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun confirmExport() {
        if (tokens.isEmpty()) {
            PiperDialog.showMessage(this, "Chưa có tài khoản 2FA", "Thêm ít nhất một tài khoản trước khi xuất tệp.")
            return
        }
        PiperDialog.showConfirm(
            this,
            "Xuất bản sao 2FA",
            "Tệp xuất chứa khóa bí mật có thể tạo mã đăng nhập. Chỉ lưu hoặc chia sẻ tệp với người bạn tin cậy.",
            "Tiếp tục"
        ) { exportLauncher.launch("piperos-2fa.json") }
    }

    private fun exportJson(): JSONObject = JSONObject().put("format", "piperos-2fa").put("version", 1).put(
        "accounts",
        JSONArray().apply {
            tokens.forEach { token ->
                put(JSONObject()
                    .put("label", token.label)
                    .put("secret", token.secret)
                    .put("algorithm", token.algorithm)
                    .put("digits", token.digits)
                    .put("period", token.period)
                    .put("autoRefresh", token.autoRefresh)
                    .put("type", token.type)
                    .put("counter", token.counter))
            }
        }
    )

    private fun parseImportJson(source: String): List<Token> {
        val root = JSONObject(source)
        require(root.optString("format") == "piperos-2fa" && root.optInt("version") == 1) { "Tệp không đúng định dạng PiperOS 2FA" }
        val accounts = root.optJSONArray("accounts") ?: error("Thiếu danh sách tài khoản")
        require(accounts.length() in 1..500) { "Tệp phải có từ 1 đến 500 tài khoản" }
        return (0 until accounts.length()).map { index ->
            val item = accounts.getJSONObject(index)
            val parsed = parseToken(item.optString("label"), item.optString("secret"))
            parsed.copy(
                id = UUID.randomUUID().toString(),
                algorithm = item.optString("algorithm", "SHA1"),
                digits = item.optInt("digits", 6),
                period = item.optInt("period", 30),
                autoRefresh = item.optBoolean("autoRefresh", true),
                type = item.optString("type", "TOTP"),
                counter = item.optLong("counter", 0L)
            ).also { require(it.algorithm in setOf("SHA1", "SHA256", "SHA512") && it.digits in 5..8 && it.period in 15..120 && it.type in setOf("TOTP", "HOTP", "STEAM") && it.counter >= 0) { "Thông số tài khoản ${index + 1} không hợp lệ" } }
        }
    }

    private fun importTokens(imported: List<Token>) {
        val uid = auth.currentUser?.uid ?: run { fail("Hãy đăng nhập PiperOS trước."); return }
        loading = true
        addBubble.isEnabled = false
        status.text = "Đang nhập ${imported.size} tài khoản 2FA…"
        val collection = firestore.collection("users").document(uid).collection("totpAccounts")
        val batches = imported.chunked(400)
        fun commitBatch(index: Int) {
            if (index >= batches.size) {
                loading = false
                addBubble.isEnabled = true
                status.text = "Đã nhập ${imported.size} tài khoản 2FA"
                return
            }
            val batch = firestore.batch()
            batches[index].forEach { token ->
                batch.set(collection.document(token.id), mapOf(
                    "label" to token.label,
                    "secret" to token.secret,
                    "algorithm" to token.algorithm,
                    "digits" to token.digits,
                    "period" to token.period,
                    "autoRefresh" to token.autoRefresh,
                    "type" to token.type,
                    "counter" to token.counter,
                    "updatedAt" to System.currentTimeMillis()
                ))
            }
            batch.commit().addOnSuccessListener { commitBatch(index + 1) }
                .addOnFailureListener { loading = false; addBubble.isEnabled = true; fail("Nhập bị dừng ở lô ${index + 1}: ${it.localizedMessage}") }
        }
        commitBatch(0)
    }

    private fun showTokenEditor(seed: String = "", label: String = "") {
        val parsed = runCatching { parseToken(label, seed) }.getOrNull()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), 0) }
        val labelInput = EditText(this).apply { hint = "Tên dịch vụ / tài khoản (Facebook cá nhân)"; setText(parsed?.label ?: label) }
        val secretInput = EditText(this).apply {
            hint = "Khóa 2FA (ví dụ: HCSFO 2SFFF SFFVC GJS52) hoặc otpauth://"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(parsed?.secret ?: seed)
        }
        box.addView(labelInput)
        box.addView(secretInput)
        box.addView(text("Loại mã", 14, true, 12))
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val timeBased = RadioButton(this).apply { id = View.generateViewId(); text = "TOTP · tự đổi theo thời gian" }
        val counterBased = RadioButton(this).apply { id = View.generateViewId(); text = "HOTP · tăng bộ đếm mỗi lần tạo mã" }
        val steam = RadioButton(this).apply { id = View.generateViewId(); text = "Steam Guard · mã 5 ký tự" }
        typeGroup.addView(timeBased)
        typeGroup.addView(counterBased)
        typeGroup.addView(steam)
        when (parsed?.type) { "HOTP" -> counterBased.isChecked = true; "STEAM" -> steam.isChecked = true; else -> timeBased.isChecked = true }
        box.addView(typeGroup)
        val counterInput = EditText(this).apply {
            hint = "Bộ đếm bắt đầu (mặc định 0)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(parsed?.counter?.toString() ?: "0")
            visibility = if (parsed?.type == "HOTP") View.VISIBLE else View.GONE
        }
        box.addView(counterInput)
        box.addView(text("Chọn cách làm mới mã", 14, true, 12))
        val refreshMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val automatic = RadioButton(this).apply { id = View.generateViewId(); text = "Tự động đổi mã mỗi 30 giây"; isChecked = true }
        val manual = RadioButton(this).apply { id = View.generateViewId(); text = "Chỉ đổi khi bấm nút làm mới" }
        refreshMode.addView(automatic)
        refreshMode.addView(manual)
        refreshMode.visibility = if (parsed?.type == "HOTP") View.GONE else View.VISIBLE
        box.addView(refreshMode)
        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            counterInput.visibility = if (checkedId == counterBased.id) View.VISIBLE else View.GONE
            refreshMode.visibility = if (checkedId == counterBased.id) View.GONE else View.VISIBLE
        }
        PiperDialog.showCustom(
            context = this,
            title = "Thêm tài khoản 2FA",
            message = "Nhập khóa thiết lập hoặc quét mã QR từ dịch vụ. Khóa có thể được nhập cách nhau bằng dấu cách.",
            content = box,
            positiveLabel = "Lưu",
            negativeLabel = "Hủy",
            onPositive = {
                val token = runCatching { parseToken(labelInput.text.toString(), secretInput.text.toString()) }
                    .getOrElse { secretInput.error = it.localizedMessage ?: "Khóa không hợp lệ"; return@showCustom false }
                val metadata = parsed ?: token
                val selectedType = when (typeGroup.checkedRadioButtonId) {
                    counterBased.id -> "HOTP"
                    steam.id -> "STEAM"
                    else -> "TOTP"
                }
                val counter = counterInput.text.toString().toLongOrNull() ?: 0L
                if (counter < 0) { counterInput.error = "Bộ đếm không được âm"; return@showCustom false }
                if (selectedType == "STEAM" && metadata.algorithm != "SHA1") { secretInput.error = "Steam Guard sử dụng SHA1"; return@showCustom false }
                if (selectedType == "STEAM" && metadata.period != 30) { secretInput.error = "Steam Guard sử dụng chu kỳ 30 giây"; return@showCustom false }
                saveToken(token.copy(
                    autoRefresh = selectedType != "HOTP" && automatic.isChecked,
                    type = selectedType,
                    digits = if (selectedType == "STEAM") 5 else metadata.digits,
                    period = if (selectedType == "STEAM") 30 else metadata.period,
                    algorithm = if (selectedType == "STEAM") "SHA1" else metadata.algorithm,
                    counter = counter
                ))
                true
            }
        )
    }

    private fun parseToken(inputLabel: String, raw: String): Token {
        var label = inputLabel.trim()
        var secret = raw.trim()
        var algorithm = "SHA1"
        var digits = 6
        var period = 30
        var type = "TOTP"
        var counter = 0L
        if (secret.startsWith("otpauth://", true)) {
            val uri = URI(secret.replace(" ", "%20"))
            require(uri.scheme.equals("otpauth", true) && uri.host.lowercase() in setOf("totp", "hotp")) { "Chỉ hỗ trợ mã TOTP hoặc HOTP" }
            type = uri.host.uppercase()
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val bits = part.split('=', limit = 2)
                if (bits.size == 2) URLDecoder.decode(bits[0], "UTF-8") to URLDecoder.decode(bits[1], "UTF-8") else null
            }.toMap()
            secret = params["secret"].orEmpty()
            if (label.isBlank()) label = URLDecoder.decode(uri.path.removePrefix("/"), "UTF-8")
            algorithm = params["algorithm"]?.uppercase() ?: "SHA1"
            digits = params["digits"]?.toIntOrNull() ?: 6
            period = params["period"]?.toIntOrNull() ?: 30
            counter = params["counter"]?.toLongOrNull() ?: 0L
            require(type != "HOTP" || params.containsKey("counter")) { "Mã HOTP cần có bộ đếm ban đầu" }
        }
        secret = secret.uppercase().replace(Regex("[\\s-]"), "")
        require(label.isNotBlank()) { "Nhập tên tài khoản" }
        require(secret.matches(Regex("[A-Z2-7]+=*")) && secret.length >= 16) { "Khóa Base32 không hợp lệ" }
        require(algorithm in setOf("SHA1", "SHA256", "SHA512") && digits in 6..8 && period in 15..120 && counter >= 0) { "Thông số OTP không được hỗ trợ" }
        return Token(UUID.randomUUID().toString(), label, secret, algorithm, digits, period, autoRefresh = type != "HOTP", type = type, counter = counter)
    }

    private fun saveToken(token: Token) {
        val uid = auth.currentUser?.uid ?: run { fail("Hãy đăng nhập PiperOS trước."); return }
        loading = true
        addBubble.isEnabled = false
        status.text = "Đang đồng bộ tài khoản 2FA…"
        firestore.collection("users").document(uid).collection("totpAccounts").document(token.id)
            .set(mapOf(
                "label" to token.label,
                "secret" to token.secret,
                "algorithm" to token.algorithm,
                "digits" to token.digits,
                "period" to token.period,
                "autoRefresh" to token.autoRefresh,
                "type" to token.type,
                "counter" to token.counter,
                "updatedAt" to System.currentTimeMillis()
            ))
            .addOnSuccessListener { loading = false; addBubble.isEnabled = true; status.text = "Đã đồng bộ ${token.label}" }
            .addOnFailureListener { loading = false; addBubble.isEnabled = true; fail("Không đồng bộ được: ${it.localizedMessage}") }
    }

    private fun deleteToken(token: Token) {
        val uid = auth.currentUser?.uid ?: return
        PiperDialog.showConfirm(this, "Xóa tài khoản 2FA", "Xóa ${token.label} khỏi tài khoản PiperOS?", "Xóa", destructive = true) {
            firestore.collection("users").document(uid).collection("totpAccounts").document(token.id).delete()
                .addOnFailureListener { fail("Không xóa được: ${it.localizedMessage}") }
        }
    }

    private fun renderTokens() {
        list.removeAllViews()
        codeViews.clear()
        countdownViews.clear()
        val visibleTokens = tokens.filter { it.label.contains(searchQuery, ignoreCase = true) }
        if (visibleTokens.isEmpty() && searchQuery.isNotEmpty()) {
            list.addView(text("Không tìm thấy tài khoản phù hợp.", 14, false, 16))
            return
        }
        visibleTokens.forEach { token ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = rounded(Color.rgb(21, 49, 68), Color.rgb(48, 81, 101), 20)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            }
            card.addView(text(token.label, 17, true))
            card.addView(text(when (token.type) { "HOTP" -> "HOTP · bộ đếm ${token.counter}"; "STEAM" -> "Steam Guard · 5 ký tự"; else -> "TOTP · ${token.period} giây" }, 12, false, 4))
            val code = TextView(this).apply {
                textSize = 34f
                setTextColor(Color.rgb(119, 220, 255))
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
                setOnClickListener { copyCode(token.id) }
            }
            codeViews[token.id] = code
            card.addView(code)
            val countdown = text("", 12, false)
            countdownViews[token.id] = countdown
            card.addView(countdown)
            if (token.type == "HOTP" || !token.autoRefresh) {
                val refresh = MaterialButton(this).apply {
                    text = if (token.type == "HOTP") "Tạo mã HOTP kế tiếp" else "⟳  Làm mới mã"
                    isAllCaps = false
                    setOnClickListener {
                        if (token.type == "HOTP") generateHotpNext(token, this)
                        else {
                            currentCodes[token.id] = codeFor(token)
                            codeSteps[token.id] = System.currentTimeMillis() / 1000 / token.period
                            code.text = currentCodes[token.id]
                            countdown.text = "Đã làm mới thủ công · chạm mã để sao chép"
                        }
                    }
                }
                card.addView(refresh)
            }
            val remove = MaterialButton(this).apply { text = "Xóa tài khoản"; isAllCaps = false; setOnClickListener { deleteToken(token) } }
            card.addView(remove)
            list.addView(card)
        }
        refreshTokenCodes()
    }

    private fun refreshTokenCodes() {
        val now = System.currentTimeMillis() / 1000
        tokens.forEach { token ->
            val left = token.period - (now % token.period).toInt()
            val code = when {
                token.type == "HOTP" -> currentCodes[token.id] ?: "------"
                token.autoRefresh -> codeFor(token).also { currentCodes[token.id] = it }
                else -> currentCodes.getOrPut(token.id) {
                    codeSteps[token.id] = now / token.period
                    codeFor(token)
                }
            }
            codeViews[token.id]?.text = code
            countdownViews[token.id]?.text = when {
                token.type == "HOTP" -> "Bấm tạo mã để dùng bộ đếm ${token.counter} · mỗi lần tạo sẽ tăng bộ đếm"
                token.autoRefresh -> "Mã mới sau $left giây · chạm mã để sao chép"
                codeSteps[token.id] != now / token.period -> "Mã đã hết hạn · bấm Làm mới để lấy mã mới"
                else -> "Tự làm mới · còn $left giây trong chu kỳ · chạm mã để sao chép"
            }
        }
    }

    private fun codeFor(token: Token): String = runCatching {
        when (token.type) {
            "HOTP" -> TotpCodeGenerator.generateHotp(token.secret, token.algorithm, token.digits, token.counter)
            "STEAM" -> TotpCodeGenerator.generateSteam(token.secret, System.currentTimeMillis() / 1000, token.period)
            else -> TotpCodeGenerator.generate(token.secret, token.algorithm, token.digits, token.period, System.currentTimeMillis() / 1000)
        }
    }.getOrDefault("------")

    private fun generateHotpNext(token: Token, button: MaterialButton) {
        val uid = auth.currentUser?.uid ?: return
        button.isEnabled = false
        val reference = firestore.collection("users").document(uid).collection("totpAccounts").document(token.id)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(reference)
            val counter = snapshot.getLong("counter") ?: token.counter
            require(counter < Long.MAX_VALUE) { "Bộ đếm đã đạt giới hạn" }
            val code = TotpCodeGenerator.generateHotp(token.secret, token.algorithm, token.digits, counter)
            transaction.update(reference, "counter", counter + 1)
            code to counter + 1
        }.addOnSuccessListener { result ->
            button.isEnabled = true
            currentCodes[token.id] = result.first
            val index = tokens.indexOfFirst { it.id == token.id }
            if (index >= 0) tokens[index] = token.copy(counter = result.second)
            renderTokens()
            Toast.makeText(this, "Đã tạo mã HOTP", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            button.isEnabled = true
            fail("Không tạo được mã HOTP: ${it.localizedMessage}")
        }
    }

    private fun copyCode(id: String) {
        val code = codeViews[id]?.text?.toString() ?: return
        if (code == "------") {
            Toast.makeText(this, "Bấm tạo mã HOTP trước", Toast.LENGTH_SHORT).show()
            return
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("2FA", code))
        Toast.makeText(this, "Đã sao chép mã", Toast.LENGTH_SHORT).show()
    }

    private fun fail(message: String) { status.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun text(value: String, size: Int, bold: Boolean, top: Int = 0) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (top > 0) setPadding(0, dp(top), 0, 0)
    }
    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
