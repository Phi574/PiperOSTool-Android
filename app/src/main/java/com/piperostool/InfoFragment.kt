package com.piperostool

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sections = createSections(requireContext())
        view.findViewById<TextView>(R.id.tvInfoHeadline).text =
            "PiperOS Tool ${AppVersion.name(requireContext())}"
        val container = view.findViewById<LinearLayout>(R.id.infoSections)
        sections.forEachIndexed { index, section ->
            addSection(container, section, expanded = index == 0)
        }
        view.findViewById<View>(R.id.btnCopyAllInfo).setOnClickListener {
            copyAllInformation(sections)
        }
    }

    private fun addSection(
        container: LinearLayout,
        section: InfoSection,
        expanded: Boolean
    ) {
        val item = layoutInflater.inflate(R.layout.item_info_section, container, false)
        item.findViewById<TextView>(R.id.tvInfoSectionTitle).text = section.title
        item.findViewById<TextView>(R.id.tvInfoSectionSummary).text = section.summary
        item.findViewById<ImageView>(R.id.ivInfoSectionIcon).apply {
            setImageResource(section.icon)
            setColorFilter(section.color)
        }
        val rows = item.findViewById<LinearLayout>(R.id.infoSectionRows)
        val chevron = item.findViewById<ImageView>(R.id.ivInfoSectionChevron)
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
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    }
                    setBackgroundColor(0x20FFFFFF)
                })
            }
        }
        fun setExpanded(value: Boolean) {
            rows.visibility = if (value) View.VISIBLE else View.GONE
            chevron.rotation = if (value) 90f else 0f
        }
        setExpanded(expanded)
        item.findViewById<View>(R.id.infoSectionHeader).setOnClickListener {
            setExpanded(rows.visibility != View.VISIBLE)
        }
        container.addView(item)
    }

    private fun createSections(context: Context): List<InfoSection> {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val model = "$manufacturer ${Build.MODEL}".trim()
        val runtime = TerminalRuntime.inspect(context)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val applicationInfo = context.applicationInfo
        val webViewPackage = currentWebViewPackage(context)
        val codecs = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos }
            .getOrDefault(emptyArray())
        val videoDecoders = codecs.count {
            !it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") }
        }
        val audioDecoders = codecs.count {
            !it.isEncoder && it.supportedTypes.any { type -> type.startsWith("audio/") }
        }
        val sessions = TerminalSessionManager.listSessions()

        return listOf(
            InfoSection(
                title = "INFO DEVICE",
                summary = "$model • Android ${Build.VERSION.RELEASE}",
                icon = R.drawable.devices,
                color = color("#7DD3FC"),
                rows = listOf(
                    InfoRow("Thiết bị", model),
                    InfoRow("Android", Build.VERSION.RELEASE),
                    InfoRow("API Level", Build.VERSION.SDK_INT.toString()),
                    InfoRow("Kiến trúc", Build.SUPPORTED_ABIS.joinToString()),
                    InfoRow("Phần cứng", Build.HARDWARE),
                    InfoRow("CPU", "${Runtime.getRuntime().availableProcessors()} nhân"),
                    InfoRow("RAM", totalRam(context)),
                    InfoRow("Kernel", System.getProperty("os.version") ?: "Không xác định")
                )
            ),
            InfoSection(
                title = "INFO APP",
                summary = "PiperOS Tool ${AppVersion.name(context)}",
                icon = R.drawable.a3tn,
                color = color("#8DFFB0"),
                rows = listOf(
                    InfoRow("Tên ứng dụng", "PiperOS Tool"),
                    InfoRow("Phiên bản", packageInfo.versionName ?: "-"),
                    InfoRow(
                        "Version code",
                        PackageInfoCompat.getLongVersionCode(packageInfo).toString()
                    ),
                    InfoRow("Package", context.packageName),
                    InfoRow("Target SDK", applicationInfo.targetSdkVersion.toString()),
                    InfoRow("Minimum SDK", applicationInfo.minSdkVersion.toString()),
                    InfoRow(
                        "Kiểu bản dựng",
                        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                            "Debug"
                        } else {
                            "Release"
                        }
                    ),
                    InfoRow("Nguồn cài đặt", installerSource(context))
                )
            ),
            InfoSection(
                title = "INFO TRÌNH DUYỆT",
                summary = webViewPackage?.versionName ?: "Android System WebView",
                icon = R.drawable.browser,
                color = color("#C4B5FD"),
                rows = listOf(
                    InfoRow("Engine", "Android WebView"),
                    InfoRow("WebView package", webViewPackage?.packageName ?: "Không xác định"),
                    InfoRow("WebView version", webViewPackage?.versionName ?: "Không xác định"),
                    InfoRow(
                        "Cookie phiên",
                        if (CookieManager.getInstance().hasCookies()) "Đang có dữ liệu" else "Trống"
                    ),
                    InfoRow("Tab ẩn danh", "Hỗ trợ, xóa dữ liệu khi đóng"),
                    InfoRow("Tải xuống", "DownloadManager + nhận dạng MIME"),
                    InfoRow("Thông báo", notificationMode())
                )
            ),
            InfoSection(
                title = "INFO TRÌNH MEDIA",
                summary = "$videoDecoders video decoder • $audioDecoders audio decoder",
                icon = R.drawable.nhacvideo,
                color = color("#FB7185"),
                rows = listOf(
                    InfoRow("Playback engine", "AndroidX Media3"),
                    InfoRow("Video decoder", videoDecoders.toString()),
                    InfoRow("Audio decoder", audioDecoders.toString()),
                    InfoRow(
                        "Picture-in-Picture",
                        if (
                            context.packageManager.hasSystemFeature(
                                PackageManager.FEATURE_PICTURE_IN_PICTURE
                            )
                        ) "Hỗ trợ" else "Không hỗ trợ"
                    ),
                    InfoRow("Phát nền", "Hỗ trợ"),
                    InfoRow("Nguồn media", "MediaStore thiết bị"),
                    InfoRow("Thông báo", notificationMode())
                )
            ),
            InfoSection(
                title = "INFO TRÌNH TERMINAL",
                summary = if (runtime.installed) {
                    "Linux ${runtime.installedVersion ?: "không rõ phiên bản"}"
                } else {
                    "Android Shell • Runtime chưa cài"
                },
                icon = R.drawable.ic_terminal,
                color = color("#FFFFC46B"),
                rows = listOf(
                    InfoRow(
                        "Runtime",
                        if (runtime.installed) "Đã cài" else "Chưa cài"
                    ),
                    InfoRow("Runtime hiện tại", runtime.installedVersion ?: "-"),
                    InfoRow("Runtime mục tiêu", TerminalRuntime.RUNTIME_VERSION),
                    InfoRow(
                        "Cập nhật",
                        if (runtime.updateAvailable) "Có bản mới" else "Đã mới nhất"
                    ),
                    InfoRow("Phiên đang mở", sessions.size.toString()),
                    InfoRow(
                        "Linux / Shell",
                        "${sessions.count { it.mode == TerminalSessionManager.SessionMode.LINUX }} / " +
                            sessions.count {
                                it.mode == TerminalSessionManager.SessionMode.ANDROID_SHELL
                            }
                    ),
                    InfoRow("PREFIX", runtime.prefixDirectory.absolutePath),
                    InfoRow("HOME", runtime.homeDirectory.absolutePath)
                )
            )
        )
    }

    private fun currentWebViewPackage(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            runCatching {
                context.packageManager.getPackageInfo("com.google.android.webview", 0)
            }.getOrNull()
        }

    private fun installerSource(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName)
                .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull() ?: "Cài thủ công / ADB"

    private fun totalRam(context: Context): String {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return String.format(Locale.US, "%.2f GB", info.totalMem / 1073741824.0)
    }

    private fun notificationMode(): String =
        if (Build.VERSION.SDK_INT >= 36) "Android 16 Live Update" else "Thông báo tiêu chuẩn"

    private fun copyAllInformation(sections: List<InfoSection>) {
        val text = buildString {
            appendLine("PiperOS Info")
            sections.forEach { section ->
                appendLine()
                appendLine(section.title)
                section.rows.forEach { appendLine("${it.label}: ${it.value}") }
            }
        }.trim()
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PiperOS Info", text))
        Toast.makeText(requireContext(), "Đã sao chép thông tin", Toast.LENGTH_SHORT).show()
    }

    private fun color(value: String): Int = android.graphics.Color.parseColor(value)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
