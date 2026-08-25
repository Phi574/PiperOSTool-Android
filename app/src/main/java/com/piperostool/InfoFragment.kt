package com.piperostool

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.core.content.ContextCompat
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
        val actions = view.findViewById<LinearLayout>(R.id.infoAccountActions)
        addAction(
            actions,
            R.drawable.details,
            getString(R.string.info_account_profile),
            getString(R.string.info_account_profile_summary)
        ) { startActivity(Intent(requireContext(), AccountProfileActivity::class.java)) }
        addAction(
            actions,
            R.drawable.devices,
            getString(R.string.info_device_sessions),
            getString(R.string.info_device_sessions_summary)
        ) { startActivity(Intent(requireContext(), DeviceSessionsActivity::class.java)) }
        val container = view.findViewById<LinearLayout>(R.id.infoSections)
        sections.forEachIndexed { index, section ->
            addSection(container, section, expanded = index == 0)
        }
        view.findViewById<View>(R.id.btnCopyAllInfo).setOnClickListener {
            copyAllInformation(sections)
        }
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
        PiperModernUi.apply(item)
        container.addView(item)
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
            if (section.icon in setOf(R.drawable.a3tn, R.drawable.browser, R.drawable.nhacvideo)) {
                clearColorFilter()
                imageTintList = null
            } else {
                setColorFilter(section.color)
            }
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
        val mockScenario = MockRouteStore.load(context)
        val mockState = MockLocationService.snapshot
        val mockPermissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        val mockAppSelected = MockLocationService.isMockLocationEnabled(context)

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
                title = "INFO APK EDITOR",
                summary = "Duyệt, trích xuất, chỉnh tệp và ký APK",
                icon = R.drawable.apk,
                color = color("#F59E0B"),
                rows = listOf(
                    InfoRow("Nguồn APK", "Ứng dụng đã cài hoặc tệp trên thiết bị"),
                    InfoRow("Giải nén", "Theo tệp, nhóm hoặc toàn bộ archive"),
                    InfoRow("Backup", "Chọn nhiều tệp/thư mục và vị trí lưu"),
                    InfoRow("Xem tệp", "Ảnh, GIF, video, audio, PDF và văn bản"),
                    InfoRow("Manifest", "Báo cáo package và quyền từ PackageManager"),
                    InfoRow("Strings", "Chỉnh XML khi tài nguyên đang ở dạng văn bản"),
                    InfoRow("Xây dựng", "Ghép thay đổi, căn chỉnh ZIP và ký lại APK"),
                    InfoRow("Chữ ký đầu ra", "PiperOS Editor test key (v1/v2/v3)"),
                    InfoRow("Thư mục kết quả", "Downloads/PiperOS_APK_Editor")
                )
            ),
            InfoSection(
                title = "INFO FILE MANAGER",
                summary = "Preview media • Archive đa định dạng • Chạy nền",
                icon = R.drawable.packaget,
                color = color("#38BDF8"),
                rows = listOf(
                    InfoRow("Phạm vi", "Bộ nhớ dùng chung do người dùng cấp quyền"),
                    InfoRow("Thumbnail", "Ảnh, video, APK, app data và thư mục hệ thống"),
                    InfoRow("Gallery", "Vuốt ngang ảnh/video trong cùng thư mục"),
                    InfoRow("Archive", "ZIP, 7Z, TAR, GZIP, BZIP2, XZ, LZ4 và ZSTD"),
                    InfoRow("Mã hóa", "ZIP AES-256 có mật khẩu"),
                    InfoRow("Mức nén", "Nhanh nhất tới Super nén"),
                    InfoRow("Chạy nền", "Foreground service + WakeLock + tiến độ"),
                    InfoRow("Công cụ", "Tìm kiếm, đổi tên, xóa, nén và giải nén"),
                    InfoRow("APK", "Mở trực tiếp bằng PiperOS APK Editor"),
                    InfoRow("Bảo vệ", "Chặn path traversal khi giải nén")
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
            ),
            InfoSection(
                title = "INFO PIPEROS VIEW REMOTE",
                summary = when {
                    PiperRemoteShareService.currentSession != null -> "Đang chia sẻ màn hình"
                    else -> "Sẵn sàng kết nối trong mạng nội bộ"
                },
                icon = R.drawable.ic_remote_view,
                color = color("#34D399"),
                rows = listOf(
                    InfoRow("Phiên bản giao thức", "Piper Remote 3 · JPEG / H.264 / HEVC"),
                    InfoRow("Phương thức", "Wi-Fi nội bộ, QR và mã 6 số"),
                    InfoRow("Xác nhận kết nối", "Bắt buộc phía thiết bị chia sẻ cho phép"),
                    InfoRow("Độ phân giải", "480p / 720p / 1080p / gốc toàn màn hình"),
                    InfoRow("Tốc độ khung hình", "24 / 30 / 60 / tối đa theo thiết bị"),
                    InfoRow("Điều khiển cảm ứng", if (PiperRemoteAccessibilityService.isRunning()) "Đã bật" else "Chưa bật"),
                    InfoRow("Chế độ xem", "Toàn màn hình, giữ đúng tỷ lệ"),
                    InfoRow("Truyền dữ liệu", "Trực tiếp giữa hai thiết bị trong LAN"),
                    InfoRow("Chạy nền khi chia sẻ", "MediaProjection foreground service")
                )
            ),
            InfoSection(
                title = "INFO FAKE MAP GPS",
                summary = fakeMapSummary(mockState, mockScenario, mockAppSelected),
                icon = R.drawable.ic_location_pin,
                color = color("#34D399"),
                rows = listOf(
                    InfoRow("Trạng thái", fakeMapStatus(mockState)),
                    InfoRow(
                        "Chế độ",
                        when (mockScenario?.mode) {
                            MockScenarioMode.FIXED -> "Vị trí cố định"
                            MockScenarioMode.ROUTE -> "Hành trình di chuyển"
                            null -> "Chưa cấu hình"
                        }
                    ),
                    InfoRow(
                        "Ứng dụng vị trí mô phỏng",
                        if (mockAppSelected) "Đã chọn PiperOS Tool" else "Chưa chọn"
                    ),
                    InfoRow(
                        "Quyền vị trí chính xác",
                        if (mockPermissionGranted) "Đã cấp" else "Chưa cấp"
                    ),
                    InfoRow(
                        "Provider",
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "GPS + Network + Fused"
                        } else {
                            "GPS + Network"
                        }
                    ),
                    InfoRow("Chu kỳ cập nhật", "500 ms"),
                    InfoRow("Chạy nền", "Foreground service + WakeLock"),
                    InfoRow("Thông báo", notificationMode()),
                    InfoRow(
                        "Điểm tuyến đã lưu",
                        mockScenario?.points?.size?.toString() ?: "0"
                    ),
                    InfoRow(
                        "Tốc độ cấu hình",
                        mockScenario?.let {
                            String.format(Locale.US, "%.1f km/h", it.speedKmh)
                        } ?: "-"
                    ),
                    InfoRow(
                        "Lặp hành trình",
                        when {
                            mockScenario?.mode != MockScenarioMode.ROUTE -> "-"
                            mockScenario.loop -> "Bật"
                            else -> "Tắt"
                        }
                    )
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

    private fun fakeMapStatus(state: MockLocationService.State): String = when {
        !state.running -> "Đã dừng"
        state.paused -> "Đang tạm dừng"
        state.arrived -> "Đã đến điểm cuối"
        else -> "Đang mô phỏng"
    }

    private fun fakeMapSummary(
        state: MockLocationService.State,
        scenario: MockScenario?,
        mockAppSelected: Boolean
    ): String {
        if (state.running) {
            val mode = if (scenario?.mode == MockScenarioMode.ROUTE) {
                "Hành trình"
            } else {
                "Cố định"
            }
            return "${fakeMapStatus(state)} • $mode"
        }
        return if (mockAppSelected) {
            "Sẵn sàng • Chưa chạy mô phỏng"
        } else {
            "Cần chọn PiperOS Tool làm ứng dụng vị trí mô phỏng"
        }
    }

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
