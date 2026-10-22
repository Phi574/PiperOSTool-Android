package com.piperostool

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class PiperDataTransferProgressActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SESSION_ID = "pdt.progress.session"
        const val EXTRA_ROLE = "pdt.progress.role"
        const val EXTRA_NAMES = "pdt.progress.names"
        const val EXTRA_MIMES = "pdt.progress.mimes"
        const val EXTRA_SIZES = "pdt.progress.sizes"
        const val ROLE_SEND = "send"
        const val ROLE_RECEIVE = "receive"

        fun createIntent(
            context: android.content.Context,
            sessionId: String,
            role: String,
            items: List<PiperDataTransferProgressState.Item>
        ) = android.content.Intent(context, PiperDataTransferProgressActivity::class.java).apply {
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_ROLE, role)
            putExtra(EXTRA_NAMES, items.map { it.name }.toTypedArray())
            putExtra(EXTRA_MIMES, items.map { it.mime }.toTypedArray())
            putExtra(EXTRA_SIZES, items.map { it.size }.toLongArray())
        }
    }

    private data class ItemRow(val card: MaterialCardView, val title: TextView, val detail: TextView, val progress: ProgressBar, var last: PiperDataTransferProgressState.Item? = null)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionId = ""
    private var role = ROLE_SEND
    private var observer: AutoCloseable? = null
    private var ambient = false
    private var finishingToReceived = false
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var ambientRunnable: Runnable? = null
    private lateinit var root: FrameLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var overall: ProgressBar
    private lateinit var overallLabel: TextView
    private lateinit var currentLabel: TextView
    private lateinit var rows: LinearLayout
    private lateinit var doneButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var ambientView: TextView
    private val itemRows = mutableListOf<ItemRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        role = intent.getStringExtra(EXTRA_ROLE) ?: ROLE_SEND
        originalBrightness = window.attributes.screenBrightness
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = layoutInflater.inflate(R.layout.activity_piper_data_transfer_progress, null) as FrameLayout
        title = root.findViewById(R.id.pdtProgressTitle)
        subtitle = root.findViewById(R.id.pdtProgressSubtitle)
        overall = root.findViewById(R.id.pdtOverallProgress)
        overallLabel = root.findViewById(R.id.pdtOverallLabel)
        currentLabel = root.findViewById(R.id.pdtCurrentItem)
        rows = root.findViewById(R.id.pdtProgressItems)
        doneButton = root.findViewById(R.id.pdtProgressDone)
        cancelButton = root.findViewById(R.id.pdtProgressCancel)
        cancelButton.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(190, 52, 60))
            setTextColor(Color.WHITE)
            setOnClickListener {
                PiperDialog.showConfirm(
                    this@PiperDataTransferProgressActivity,
                    "Hủy phiên chuyển dữ liệu?",
                    "Phiên sẽ dừng ở cả hai thiết bị. Những dữ liệu chưa truyền xong sẽ không được giữ lại.",
                    "Hủy phiên",
                    destructive = true
                ) {
                    if (!PiperDataTransferProgressState.requestCancel(sessionId)) {
                        PiperDialog.showMessage(this@PiperDataTransferProgressActivity, "Không thể hủy phiên", "Kết nối đã kết thúc hoặc không còn điều khiển được.")
                    }
                }
            }
        }
        doneButton.backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.accentColor(this))
        doneButton.setTextColor(Color.WHITE)
        root.findViewById<MaterialButton>(R.id.pdtProgressBack).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperDataTransferProgressActivity))
            setTextColor(PiperModernUi.textColor(this@PiperDataTransferProgressActivity))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this@PiperDataTransferProgressActivity))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        doneButton.setOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top + dp(4), bars.right, bars.bottom + dp(8))
            insets
        }
        setContentView(root)
        PiperModernUi.watch(this)
        addAmbientLayer()
        render(PiperDataTransferProgressState.snapshot(sessionId) ?: initialSnapshot())
        observer = PiperDataTransferProgressState.observe(sessionId) { snapshot -> runOnUiThread { render(snapshot) } }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (PiperDataTransferProgressState.snapshot(sessionId)?.complete == true) finish()
                else PiperDialog.showConfirm(
                    this@PiperDataTransferProgressActivity,
                    "Rời màn hình truyền?",
                    "Dữ liệu vẫn tiếp tục truyền trong phiên hiện tại. Bạn có thể mở lại tiến trình khi quay về.",
                    "Rời màn hình"
                ) { finish() }
            }
        })
        scheduleAmbientMode()
    }

    private fun initialSnapshot(): PiperDataTransferProgressState.Snapshot {
        val names = intent.getStringArrayExtra(EXTRA_NAMES).orEmpty()
        val mimes = intent.getStringArrayExtra(EXTRA_MIMES).orEmpty()
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: longArrayOf()
        val items = names.indices.map { index ->
            PiperDataTransferProgressState.Item(names[index], mimes.getOrElse(index) { "application/octet-stream" }, sizes.getOrElse(index) { -1L })
        }
        return PiperDataTransferProgressState.Snapshot(sessionId, role, items, 0, items.sumOf { it.size.coerceAtLeast(0L) }, 0)
    }

    private fun render(snapshot: PiperDataTransferProgressState.Snapshot) {
        if (isFinishing) return
        if (snapshot.role == ROLE_RECEIVE && snapshot.complete && !snapshot.receivedDirectory.isNullOrBlank() && !finishingToReceived) {
            finishingToReceived = true
            mainHandler.postDelayed({ if (!isFinishing) showReceived(snapshot.receivedDirectory!!) }, 700)
        }
        if (ambient) return
        title.text = if (snapshot.role == ROLE_RECEIVE) "Nhận dữ liệu" else "Đang gửi dữ liệu"
        val count = snapshot.items.size
        subtitle.text = "$count mục • ${formatBytes(snapshot.total)}"
        val percent = if (snapshot.total > 0) ((snapshot.transferred * 100L) / snapshot.total).toInt().coerceIn(0, 100) else 0
        overall.isIndeterminate = snapshot.total <= 0 && !snapshot.complete
        if (!overall.isIndeterminate) overall.progress = if (snapshot.complete || snapshot.awaitingConfirmation) 100 else percent
        overallLabel.text = when {
            snapshot.complete -> "Hoàn tất • ${formatBytes(snapshot.transferred)} / ${formatBytes(snapshot.total)}"
            snapshot.awaitingConfirmation -> "Đã gửi đủ dữ liệu • đang chờ thiết bị nhận xác nhận"
            else -> "${formatBytes(snapshot.transferred)} / ${formatBytes(snapshot.total)} • ${if (snapshot.total > 0) "$percent%" else "Đang tính"}"
        }
        val active = snapshot.items.getOrNull(snapshot.currentIndex)
        currentLabel.text = when {
            snapshot.error != null -> "Đã dừng: ${snapshot.error}"
            snapshot.complete -> "Đã truyền xong tất cả dữ liệu"
            snapshot.awaitingConfirmation -> "Đang chờ xác nhận kết thúc từ thiết bị nhận…"
            active != null -> "${if (snapshot.role == ROLE_RECEIVE) "Đang nhận" else "Đang gửi"}: ${active.name}"
            else -> "Đang chuẩn bị dữ liệu…"
        }
        ensureRows(snapshot.items)
        snapshot.items.forEachIndexed { index, item -> updateRow(itemRows[index], item) }
        doneButton.text = if (snapshot.role == ROLE_RECEIVE && snapshot.complete) "Mở dữ liệu đã nhận" else if (snapshot.complete || snapshot.error != null) "Đóng" else "Ẩn tiến trình"
        cancelButton.visibility = if (snapshot.complete || snapshot.error != null) View.GONE else View.VISIBLE
        doneButton.setOnClickListener {
            val latest = PiperDataTransferProgressState.snapshot(sessionId)
            if (latest?.role == ROLE_RECEIVE && latest.complete && !latest.receivedDirectory.isNullOrBlank()) {
                showReceived(latest.receivedDirectory!!)
            } else finish()
        }
    }

    private fun ensureRows(items: List<PiperDataTransferProgressState.Item>) {
        while (itemRows.size > items.size) {
            rows.removeView(itemRows.removeAt(itemRows.lastIndex).card)
        }
        while (itemRows.size < items.size) itemRows += createRow().also { rows.addView(it.card, bottom(8)) }
    }

    private fun createRow(): ItemRow {
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)) }
        val icon = TextView(this).apply {
            text = "•"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(PiperModernUi.accentColor(this@PiperDataTransferProgressActivity))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(PiperModernUi.accentColor(this@PiperDataTransferProgressActivity).let { androidx.core.graphics.ColorUtils.setAlphaComponent(it, 24) }) }
        }
        body.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
        val title = TextView(this).apply { textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(PiperModernUi.textColor(this@PiperDataTransferProgressActivity)); maxLines = 2 }
        val detail = TextView(this).apply { textSize = 12f; setTextColor(PiperModernUi.secondaryTextColor(this@PiperDataTransferProgressActivity)) }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(detail, top(3)); addView(progress, top(7)) }
        body.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
            strokeColor = PiperModernUi.borderColor(this@PiperDataTransferProgressActivity)
            setCardBackgroundColor(PiperModernUi.surfaceColor(this@PiperDataTransferProgressActivity))
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        return ItemRow(card, title, detail, progress)
    }

    private fun updateRow(row: ItemRow, item: PiperDataTransferProgressState.Item) {
        if (row.last == item) return
        row.last = item
        row.title.text = item.name
        val percent = if (item.size > 0L) ((item.transferred * 100L) / item.size).toInt().coerceIn(0, 100) else 0
        row.detail.text = when (item.state) {
            PiperDataTransferProgressState.ItemState.COMPLETE -> "Hoàn tất • ${formatBytes(item.size)}"
            PiperDataTransferProgressState.ItemState.ACTIVE -> "${formatBytes(item.transferred)} / ${formatBytes(item.size)}${if (item.size > 0) " • $percent%" else ""}"
            PiperDataTransferProgressState.ItemState.WAITING -> "Đang chờ • ${formatBytes(item.size)}"
        }
        row.progress.isIndeterminate = item.state == PiperDataTransferProgressState.ItemState.ACTIVE && item.size <= 0
        if (!row.progress.isIndeterminate) row.progress.progress = if (item.state == PiperDataTransferProgressState.ItemState.COMPLETE) 100 else percent
    }

    private fun addAmbientLayer() {
        ambientView = TextView(this).apply {
            text = "PiperOS Data Transfer"
            textSize = 15f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setTag(R.id.piper_preserve_modern_colors, true)
            contentDescription = "PiperOS Data Transfer"
        }
        root.addView(ambientView, FrameLayout.LayoutParams(-1, -1))
        ambientView.setOnClickListener { restoreBrightScreen() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (ambient) restoreBrightScreen() else scheduleAmbientMode()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scheduleAmbientMode() {
        ambientRunnable?.let(mainHandler::removeCallbacks)
        ambientRunnable = Runnable {
            if (!isFinishing && !PiperDataTransferProgressState.snapshot(sessionId)?.complete.orFalse()) {
                ambient = true
                ambientView.visibility = View.VISIBLE
                window.attributes = window.attributes.apply { screenBrightness = 0.02f }
                root.setBackgroundColor(Color.BLACK)
                WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.systemBars())
            }
        }.also { mainHandler.postDelayed(it, 10_000L) }
    }

    private fun Boolean?.orFalse() = this ?: false

    private fun restoreBrightScreen() {
        ambientRunnable?.let(mainHandler::removeCallbacks)
        if (!ambient) { scheduleAmbientMode(); return }
        ambient = false
        ambientView.visibility = View.GONE
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.remote_page_background))
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        PiperDataTransferProgressState.snapshot(sessionId)?.let(::render)
        scheduleAmbientMode()
    }

    private fun showReceived(path: String) {
        startActivity(android.content.Intent(this, PiperDataTransferReceivedActivity::class.java).putExtra(PiperDataTransferReceivedActivity.EXTRA_SESSION_DIR, path))
        finish()
    }

    override fun onDestroy() {
        observer?.close()
        ambientRunnable?.let(mainHandler::removeCallbacks)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ambient) window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        super.onDestroy()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "Chưa rõ dung lượng"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun bottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
    private fun top(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
}
