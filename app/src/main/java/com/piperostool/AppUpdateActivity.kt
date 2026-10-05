package com.piperostool

import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppUpdateActivity : AppCompatActivity() {
    private lateinit var headline: TextView
    private lateinit var currentVersion: TextView
    private lateinit var published: TextView
    private lateinit var description: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: MaterialCardView
    private lateinit var actionText: TextView
    private lateinit var cancel: MaterialCardView
    private lateinit var logToggle: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var log: TextView
    private var release: AppRelease? = null
    private var downloadJob: Job? = null
    private var downloadedApk: File? = null
    private var checking = false
    private var verifying = false
    private var installerPending = false
    private var lastLoggedPercent = -10

    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (installerPending && packageManager.canRequestPackageInstalls()) {
            installerPending = false
            launchInstaller()
        } else if (installerPending) {
            installerPending = false
            showError("Chưa cấp quyền cài APK từ PiperOS Tool. Bạn có thể bật quyền và thử lại.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_update)
        PiperModernUi.watch(this)
        headline = findViewById(R.id.updateHeadline)
        currentVersion = findViewById(R.id.updateCurrentVersion)
        published = findViewById(R.id.updatePublished)
        description = findViewById(R.id.updateDescription)
        status = findViewById(R.id.updateStatus)
        progress = findViewById(R.id.updateProgress)
        action = findViewById(R.id.updateAction)
        actionText = findViewById(R.id.updateActionText)
        cancel = findViewById(R.id.updateCancel)
        logToggle = findViewById(R.id.updateLogToggle)
        logScroll = findViewById(R.id.updateLogScroll)
        log = findViewById(R.id.updateLog)
        currentVersion.text = "Đang có trên máy: ${AppVersion.name(this)}"
        findViewById<View>(R.id.updateBack).setOnClickListener { leave() }
        cancel.setOnClickListener { cancelDownload() }
        logToggle.setOnClickListener {
            val expanded = logScroll.visibility != View.VISIBLE
            logScroll.visibility = if (expanded) View.VISIBLE else View.GONE
            logToggle.text = if (expanded) "Ẩn chi tiết tải xuống  ▴" else "Chi tiết tải xuống  ▾"
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        checkRelease()
    }

    private fun leave() {
        if (verifying) {
            Toast.makeText(this, "Đang kiểm tra chữ ký và phiên bản, vui lòng chờ", Toast.LENGTH_SHORT).show()
            return
        }
        if (downloadJob?.isActive == true) cancelDownload()
        finish()
    }

    private fun checkRelease() {
        if (checking) return
        checking = true
        action.visibility = View.GONE
        status.text = "Đang kiểm tra"
        val loading = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(LinearLayout(this@AppUpdateActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(22), dp(24), dp(22))
                setBackgroundColor(Color.rgb(31, 42, 59))
                addView(ProgressBar(this@AppUpdateActivity), LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(TextView(this@AppUpdateActivity).apply {
                    text = "Đang kiểm tra"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(dp(18), 0, 0, 0)
                })
            })
            setCancelable(false)
        }
        loading.show()
        lifecycleScope.launch {
            try {
                val found = AppUpdateRepository.newestRelease()
                release = found
                headline.text = found.title
                published.text = "GitHub Releases · ${found.tag} · ${found.published}"
                val local = AppUpdateRepository.version(AppVersion.name(this@AppUpdateActivity))
                val remote = AppUpdateRepository.version(found.tag)
                val newer = local != null && remote != null && remote > local
                if (newer) {
                    description.text = readableNotes(found.description)
                    status.text = if (found.assetUrl == null) "Có bản mới nhưng chưa có APK để cài" else "Có phiên bản mới ${found.tag}"
                    if (found.assetUrl != null) setAction("Tải xuống và cài đặt") { showInstallConfirmation() }
                } else {
                    if (remote != local) published.text = "Bản công khai gần nhất: ${found.tag}"
                    description.text = if (remote == local) readableNotes(found.description) else
                        "Bản ${AppVersion.name(this@AppUpdateActivity)} gồm màn cập nhật trong ứng dụng, kiểm tra APK và hỗ trợ trình cài đặt hệ thống."
                    headline.text = "PiperOS Tool ${AppVersion.name(this@AppUpdateActivity)}"
                    status.text = "Bản hiện tại là bản mới nhất"
                    action.visibility = View.GONE
                }
            } catch (error: Exception) {
                headline.text = "Không kiểm tra được bản phát hành"
                description.text = "Không thể lấy thông tin từ GitHub. Kiểm tra kết nối mạng rồi thử lại."
                showError(error.message ?: "Lỗi kết nối không xác định")
                setAction("Kiểm tra lại") { checkRelease() }
            } finally {
                checking = false
                loading.dismiss()
            }
        }
    }

    private fun showInstallConfirmation() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.rgb(29, 41, 58))
                cornerRadius = dp(22).toFloat()
            }
        }
        root.addView(TextView(this).apply {
            text = "Cài bản mới nhất?"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Từ ${AppVersion.name(this@AppUpdateActivity)} lên ${release?.tag ?: "bản mới"}. Hãy sao lưu dữ liệu quan trọng trước khi cài. Không đóng app khi đang tải và cài đặt. Nếu đã bật chạy nền, bạn có thể chuyển sang app khác nhưng đừng vuốt tắt PiperOS khỏi màn đa nhiệm."
            textSize = 14f
            setTextColor(Color.rgb(203, 215, 231))
            setPadding(0, dp(14), 0, dp(16))
        })
        root.addView(dialogButton("Cài đặt") { dialog.dismiss(); beginDownload() })
        root.addView(dialogButton("Hủy") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(9)
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dialogButton(label: String, click: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        cornerRadius = dp(18)
        backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@AppUpdateActivity))
        strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this@AppUpdateActivity))
        strokeWidth = dp(1)
        setTextColor(PiperModernUi.textColor(this@AppUpdateActivity))
        isClickable = true
        isFocusable = true
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(-1, dp(48))
        setOnClickListener { click() }
    }

    private fun beginDownload() {
        val target = release ?: return
        if (downloadJob?.isActive == true) return
        downloadedApk = null
        lastLoggedPercent = -10
        log.text = ""
        logToggle.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        cancel.visibility = View.VISIBLE
        action.visibility = View.GONE
        appendLog("Bắt đầu tải ${target.tag} từ GitHub Releases")
        appendLog("Tệp cài đặt: ${AppUpdateRepository.apkFileName(target)}")
        downloadJob = lifecycleScope.launch {
            try {
                val file = AppUpdateRepository.download(this@AppUpdateActivity, target) { bytes, total ->
                    val percent = ((bytes * 100) / total).toInt().coerceIn(0, 100)
                    progress.progress = percent
                    status.text = "Đang tải $percent% · ${bytes / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                    if (percent >= lastLoggedPercent + 10 || percent == 100) {
                        appendLog("Đã tải $percent% ($bytes / $total byte)")
                        lastLoggedPercent = percent
                    }
                }
                cancel.visibility = View.GONE
                verifying = true
                status.text = "Đang kiểm tra chữ ký và phiên bản"
                appendLog("Tải hoàn tất. Đang xác thực APK và so sánh với bản đang cài…")
                withContext(Dispatchers.IO) {
                    AppUpdateRepository.verifyDownloadedApk(this@AppUpdateActivity, target, file)
                }
                downloadedApk = file
                appendLog("Đã xác thực APK ${target.tag}: chữ ký, tên gói và phiên bản hợp lệ")
                status.text = "APK ${target.tag} đã xác thực · sẵn sàng cài đặt"
                setAction("Mở trình cài đặt") { launchInstaller() }
                launchInstaller()
            } catch (cancelled: CancellationException) {
                status.text = "Đã hủy tải xuống"
                appendLog("Người dùng đã hủy tải")
                throw cancelled
            } catch (error: Exception) {
                status.text = "Không thể cài bản cập nhật"
                showError(error.message ?: "Lỗi tải hoặc kiểm tra APK")
                appendLog("Lỗi: ${error.message ?: "Không xác định"}")
                setAction("Thử tải lại") { showInstallConfirmation() }
            } finally {
                verifying = false
                cancel.visibility = View.GONE
            }
        }
    }

    private fun cancelDownload() {
        if (verifying) return
        downloadJob?.cancel()
        cancel.visibility = View.GONE
        progress.visibility = View.GONE
        setAction("Tải xuống và cài đặt") { showInstallConfirmation() }
    }

    private fun launchInstaller() {
        val file = downloadedApk ?: return
        if (!file.isFile) {
            showError("APK đã tải không còn trong bộ nhớ đệm. Hãy tải lại.")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            installerPending = true
            status.text = "Cần cho phép PiperOS Tool cài ứng dụng từ nguồn này"
            try {
                unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            } catch (error: Exception) {
                installerPending = false
                showError("Không mở được quyền cài ứng dụng trên ROM này. Hãy cấp quyền 'Cài ứng dụng không rõ nguồn gốc' cho PiperOS Tool trong Cài đặt: ${error.message}")
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        appendLog("Mở trình cài cho ${release?.tag ?: file.name}: ${file.name}")
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            clipData = ClipData.newRawUri("PiperOS update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(install)
            appendLog("Đã chuyển APK cho trình cài đặt hệ thống")
        } catch (error: Exception) {
            appendLog("Trình cài đặt mặc định không mở được: ${error.message}")
            try {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("PiperOS update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(view, "Chọn trình cài đặt APK"))
                appendLog("Đã mở lựa chọn trình cài đặt APK")
            } catch (fallbackError: Exception) {
                showError("Không tìm thấy trình cài APK: ${fallbackError.message}")
            }
        }
    }

    private fun setAction(label: String, click: () -> Unit) {
        actionText.text = label
        action.visibility = View.VISIBLE
        action.setOnClickListener { click() }
    }

    private fun appendLog(message: String) {
        log.append("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}  $message\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showError(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun readableNotes(markdown: String): String = markdown
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*-\\s+"), "• ")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace("**", "")
        .replace("`", "")
        .trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
