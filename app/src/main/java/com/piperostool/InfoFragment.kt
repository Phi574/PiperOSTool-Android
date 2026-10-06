package com.piperostool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InfoFragment : Fragment() {
    private data class InfoRow(val label: String, val value: String)

    private data class InfoSection(
        val title: String,
        val summary: String,
        val icon: Int,
        val color: Int,
        val rows: List<InfoRow>
    )

    private var latestHealthItems: List<InfoHealthItem> = emptyList()
    private var healthCheckRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val sections = createSections(context)
        view.findViewById<TextView>(R.id.tvInfoHeadline).text =
            "PiperOS Tool ${AppVersion.name(context)}"

        val actions = view.findViewById<LinearLayout>(R.id.infoAccountActions)
        addAction(
            actions,
            R.drawable.details,
            getString(R.string.info_account_profile),
            getString(R.string.info_account_profile_summary)
        ) { startActivity(Intent(context, AccountProfileActivity::class.java)) }
        addAction(
            actions,
            R.drawable.devices,
            getString(R.string.info_device_sessions),
            getString(R.string.info_device_sessions_summary)
        ) { startActivity(Intent(context, DeviceSessionsActivity::class.java)) }

        val sectionContainer = view.findViewById<LinearLayout>(R.id.infoSections)
        sections.forEach { section -> addSection(sectionContainer, section) }
        view.findViewById<View>(R.id.btnCopyAllInfo).setOnClickListener {
            copyBasicInformation(sections)
        }
        view.findViewById<MaterialButton>(R.id.btnRefreshInfoHealth).setOnClickListener {
            refreshHealth(view)
        }

        PiperModernUi.apply(view)
        showHealthItems(view, listOf(
            InfoHealthItem("internet", "Internet", "Chạm Kiểm tra để bắt đầu", InfoHealthState.CHECKING),
            InfoHealthItem("github", "GitHub", "Chưa kiểm tra", InfoHealthState.CHECKING),
            InfoHealthItem("firebase", "Firebase Authentication", "Chưa kiểm tra", InfoHealthState.CHECKING)
        ))
        refreshHealth(view)
    }

    private fun addAction(
        container: LinearLayout,
        icon: Int,
        title: String,
        summary: String,
        action: () -> Unit
    ) {
        val item = layoutInflater.inflate(R.layout.item_info_action, container, false)
        item.findViewById<ImageView>(R.id.ivInfoActionIcon).apply {
            setImageResource(icon)
            setColorFilter(PiperModernUi.accentColor(requireContext()))
        }
        item.findViewById<TextView>(R.id.tvInfoActionTitle).text = title
        item.findViewById<TextView>(R.id.tvInfoActionSummary).text = summary
        item.setOnClickListener { action() }
        container.addView(item)
    }

    private fun addSection(container: LinearLayout, section: InfoSection) {
        val item = layoutInflater.inflate(R.layout.item_info_section, container, false)
        item.findViewById<TextView>(R.id.tvInfoSectionTitle).text = section.title
        item.findViewById<TextView>(R.id.tvInfoSectionSummary).text = section.summary
        item.findViewById<ImageView>(R.id.ivInfoSectionIcon).apply {
            setImageResource(section.icon)
            imageTintList = null
            if (section.title == "ỨNG DỤNG") clearColorFilter() else setColorFilter(section.color)
        }
        val rows = item.findViewById<LinearLayout>(R.id.infoSectionRows)
        section.rows.forEachIndexed { index, row ->
            val rowView = layoutInflater.inflate(R.layout.item_device_info, rows, false)
            rowView.findViewById<TextView>(R.id.tvDeviceLabel).text = row.label
            rowView.findViewById<TextView>(R.id.tvDeviceValue).text = row.value
            rows.addView(rowView)
            if (index < section.rows.lastIndex) {
                rows.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        marginStart = dp(14)
                        marginEnd = dp(14)
                    }
                    setBackgroundColor(0x20000000)
                })
            }
        }
        val chevron = item.findViewById<ImageView>(R.id.ivInfoSectionChevron)
        rows.visibility = View.GONE
        item.findViewById<View>(R.id.infoSectionHeader).setOnClickListener {
            val expanded = rows.visibility != View.VISIBLE
            rows.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (expanded) 90f else 0f).setDuration(160L).start()
        }
        container.addView(item)
    }

    private fun createSections(context: Context): List<InfoSection> {
        val manufacturer = BuildLabel.manufacturer()
        val model = listOf(manufacturer, android.os.Build.MODEL)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val debugBuild = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

        return listOf(
            InfoSection(
                title = "THIẾT BỊ",
                summary = "$model • Android ${android.os.Build.VERSION.RELEASE}",
                icon = R.drawable.devices,
                color = color("#38BDF8"),
                rows = listOf(
                    InfoRow("Thiết bị", model),
                    InfoRow("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                )
            ),
            InfoSection(
                title = "ỨNG DỤNG",
                summary = "PiperOS Tool ${AppVersion.name(context)}",
                icon = R.drawable.a3tn,
                color = color("#34D399"),
                rows = listOf(
                    InfoRow("Phiên bản", packageInfo.versionName ?: AppVersion.name(context)),
                    InfoRow("Bản dựng", if (debugBuild) "Debug" else "Release"),
                    InfoRow("Package", context.packageName)
                )
            ),
            InfoSection(
                title = "CÔNG CỤ PIPEROS",
                summary = "Các tính năng chính trong ứng dụng",
                icon = R.drawable.apps,
                color = color("#A78BFA"),
                rows = listOf(
                    InfoRow("PiperOS Browser", "Trình duyệt tích hợp"),
                    InfoRow("PiperOS Media", "Phát nhạc và video"),
                    InfoRow("PiperOS Terminal", "Terminal Android và Linux"),
                    InfoRow("PiperOS Fake Map GPS", "Mô phỏng vị trí và hành trình"),
                    InfoRow("Trình quản lý tệp PiperOS", "Duyệt và quản lý tệp"),
                    InfoRow("PiperOS View Remote", "Xem và điều khiển từ xa"),
                    InfoRow("PiperOS ADB", "Thiết lập và quản lý kết nối ADB"),
                    InfoRow("PiperOS QR", "Tạo và quét mã QR")
                )
            )
        )
    }

    private fun refreshHealth(root: View) {
        if (healthCheckRunning || !isAdded) return
        healthCheckRunning = true
        val button = root.findViewById<MaterialButton>(R.id.btnRefreshInfoHealth)
        button.isEnabled = false
        button.text = "Đang kiểm tra…"
        InfoConnectivityChecker.check(requireContext()) { items ->
            if (!isAdded || view !== root) return@check
            healthCheckRunning = false
            button.isEnabled = true
            button.text = "Kiểm tra lại"
            latestHealthItems = items
            root.findViewById<TextView>(R.id.tvInfoHealthCheckedAt).text =
                "Cập nhật lúc ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
            showHealthItems(root, items)
        }
    }

    private fun showHealthItems(root: View, items: List<InfoHealthItem>) {
        val container = root.findViewById<LinearLayout>(R.id.infoHealthRows)
        container.removeAllViews()
        items.forEach { item ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(stateColor(item.state))
                }
            }
            val labels = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(requireContext()).apply {
                text = item.title
                textSize = 13f
                setTextColor(if (isDarkTheme()) Color.WHITE else color("#17212B"))
            }
            val detail = TextView(requireContext()).apply {
                text = item.detail
                textSize = 11f
                setTextColor(if (isDarkTheme()) color("#AAB8C4") else color("#66737F"))
            }
            labels.addView(title)
            labels.addView(detail)
            val stateLabel = TextView(requireContext()).apply {
                text = when (item.state) {
                    InfoHealthState.CHECKING -> "ĐANG KIỂM TRA"
                    InfoHealthState.HEALTHY -> "BÌNH THƯỜNG"
                    InfoHealthState.SLOW -> "CHẬM"
                    InfoHealthState.WARNING -> "CÓ CẢNH BÁO"
                    InfoHealthState.UNAVAILABLE -> "MẤT KẾT NỐI"
                }
                textSize = 9f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(stateColor(item.state))
            }
            row.addView(dot)
            row.addView(labels)
            row.addView(stateLabel)
            container.addView(row)
        }
    }

    private fun copyBasicInformation(sections: List<InfoSection>) {
        val text = buildString {
            appendLine("PiperOS Tool ${AppVersion.name(requireContext())}")
            sections.forEach { section ->
                appendLine()
                appendLine(section.title)
                section.rows.forEach { appendLine("${it.label}: ${it.value}") }
            }
            if (latestHealthItems.isNotEmpty()) {
                appendLine()
                appendLine("KẾT NỐI")
                latestHealthItems.forEach { appendLine("${it.title}: ${it.detail}") }
            }
        }.trim()
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PiperOS Info", text))
        Toast.makeText(requireContext(), "Đã sao chép thông tin cơ bản", Toast.LENGTH_SHORT).show()
    }

    private fun stateColor(state: InfoHealthState): Int = when (state) {
        InfoHealthState.CHECKING -> color("#94A3B8")
        InfoHealthState.HEALTHY -> color("#22C55E")
        InfoHealthState.SLOW -> color("#F59E0B")
        InfoHealthState.WARNING -> color("#F59E0B")
        InfoHealthState.UNAVAILABLE -> color("#EF4444")
    }

    private fun isDarkTheme(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun color(value: String): Int = Color.parseColor(value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private object BuildLabel {
    fun manufacturer(): String = android.os.Build.MANUFACTURER.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
}
