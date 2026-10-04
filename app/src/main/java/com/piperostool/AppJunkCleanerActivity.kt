package com.piperostool

import android.os.Bundle
import android.content.res.Configuration
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppJunkCleanerActivity : AppCompatActivity() {
    private var items = emptyList<AppJunkItem>()
    private val selected = mutableSetOf<String>()
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private lateinit var deleteButton: MaterialButton
    private lateinit var list: RecyclerView
    private lateinit var backdrop: PiperLiquidGlassBackgroundView
    private val adapter = JunkAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val screen = FrameLayout(this).apply { tag = "piper_glass_content" }
        backdrop = PiperLiquidGlassBackgroundView(this).apply {
            tag = "piper_classic_liquid_glass_background"
            darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        screen.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            tag = "piper_glass_content"
        }
        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(MaterialButton(this).apply {
            text = "‹"
            textSize = 26f
            contentDescription = "Quay lại"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(54), dp(50)))
        header.addView(TextView(this).apply {
            text = "Xóa rác ứng dụng"
            textSize = 22f
            setTextColor(PiperModernUi.textColor(this@AppJunkCleanerActivity))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        intro.addView(header)
        intro.addView(TextView(this).apply {
            text = "Quét bộ nhớ đệm, nhật ký cũ và thiết lập tính năng đã gỡ. Dữ liệu tài khoản, trình duyệt, tệp tải về, bản sao lưu và dữ liệu Terminal không nằm trong danh sách."
            textSize = 13f
            setTextColor(PiperModernUi.secondaryTextColor(this@AppJunkCleanerActivity))
            setPadding(dp(5), dp(8), dp(5), dp(12))
        })
        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(PiperModernUi.textColor(this@AppJunkCleanerActivity))
        }
        intro.addView(summary)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        intro.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this).apply {
            text = "Chọn tất cả"
            isAllCaps = false
            setOnClickListener {
                selected.clear()
                selected.addAll(items.map(AppJunkItem::id))
                adapter.notifyDataSetChanged()
                updateSummary()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(MaterialButton(this).apply {
            text = "Bỏ chọn"
            isAllCaps = false
            setOnClickListener {
                selected.clear()
                adapter.notifyDataSetChanged()
                updateSummary()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        intro.addView(actions)
        root.addView(glassPanel(intro, 23f, true), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppJunkCleanerActivity)
            adapter = this@AppJunkCleanerActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        deleteButton = MaterialButton(this).apply {
            text = "Xóa mục đã chọn"
            isAllCaps = false
            setOnClickListener { confirmDelete() }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        footer.addView(deleteButton, LinearLayout.LayoutParams(-1, dp(52)))
        footer.addView(MaterialButton(this).apply {
            text = "Quét lại"
            isAllCaps = false
            setOnClickListener { scan() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(glassPanel(footer, 22f, true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        screen.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(screen)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(16), dp(16) + bars.top, dp(16), dp(16) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        PiperModernUi.apply(root)
        root.background = null
        scan()
    }

    private fun glassPanel(content: View, radiusDp: Float, dynamic: Boolean): LiquidGlassView =
        LiquidGlassView(this).apply {
            material = GlassMaterial.CLEAR
            cornerRadius = dp(radiusDp)
            refractionHeight = dp(30).toFloat()
            bevelWidth = dp(9).toFloat()
            dispersionStrength = 0.08f
            enableSensorHighlight = true
            enableDynamicBackground = dynamic
            backdropSource = backdrop
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }

    private fun scan() {
        progress.visibility = View.VISIBLE
        summary.text = "Đang quét tệp rác…"
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { AppJunkScanner.scan(this@AppJunkCleanerActivity) }
            items = found
            selected.clear()
            selected.addAll(found.map(AppJunkItem::id))
            adapter.notifyDataSetChanged()
            progress.visibility = View.GONE
            updateSummary()
        }
    }

    private fun updateSummary() {
        val chosen = items.filter { it.id in selected }
        val bytes = chosen.sumOf { it.bytes }
        summary.text = "Đã quét ${items.size} mục · chọn ${chosen.size} mục (${Formatter.formatShortFileSize(this, bytes)})"
        deleteButton.isEnabled = chosen.isNotEmpty() && progress.visibility != View.VISIBLE
    }

    private fun confirmDelete() {
        val chosen = items.filter { it.id in selected }
        if (chosen.isEmpty()) return
        PiperDialog.showConfirm(this, "Xóa rác đã chọn",
            "Xóa ${chosen.size} mục đã chọn?", "Xóa", destructive = true) {
            progress.visibility = View.VISIBLE
            deleteButton.isEnabled = false
            lifecycleScope.launch {
                val removed = withContext(Dispatchers.IO) {
                    chosen.count { AppJunkScanner.delete(this@AppJunkCleanerActivity, it) }
                }
                scan()
                summary.text = "Đã xóa $removed/${chosen.size} mục. Đang quét lại…"
            }
        }
    }

    private inner class JunkAdapter : RecyclerView.Adapter<JunkAdapter.Holder>() {
        inner class Holder(val card: LiquidGlassView, val check: CheckBox, val detail: TextView) :
            RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val column = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(7), dp(12), dp(9))
            }
            val check = CheckBox(parent.context).apply { textSize = 14f }
            val detail = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(PiperModernUi.secondaryTextColor(this@AppJunkCleanerActivity))
                setPadding(dp(4), 0, dp(4), 0)
            }
            column.addView(check)
            column.addView(detail)
            val card = glassPanel(column, 16f, false).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            return Holder(card, check, detail)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val path = item.id
            holder.check.setOnCheckedChangeListener(null)
            holder.check.text = "${item.category} · ${item.label}"
            holder.check.isChecked = path in selected
            holder.detail.text = "${item.detail} · ${Formatter.formatShortFileSize(this@AppJunkCleanerActivity, item.bytes)}"
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(path) else selected.remove(path)
                updateSummary()
            }
            holder.card.setOnClickListener { holder.check.isChecked = !holder.check.isChecked }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
