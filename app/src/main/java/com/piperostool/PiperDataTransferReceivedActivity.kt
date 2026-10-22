package com.piperostool

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.Executors

class PiperDataTransferReceivedActivity : AppCompatActivity() {
    companion object { const val EXTRA_SESSION_DIR = "pdt.received.session_dir" }
    private data class Staged(val file: File, val name: String, val mime: String)

    private val io = Executors.newSingleThreadExecutor()
    private var receivedRoot: File? = null
    private var files: List<Staged> = emptyList()
    private var awaitingInstall: List<File> = emptyList()
    private var installIndex = 0
    private var installingPackage: String? = null
    private lateinit var container: LinearLayout
    private lateinit var status: TextView

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(::importStagedFiles) }
    private val installSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) installNextApk() else showStatus("Chưa cấp quyền cài APK từ PiperOS Tool.")
    }
    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val expectedPackage = installingPackage
        val candidate = awaitingInstall.getOrNull(installIndex)
        val requiredVersion = candidate?.let { packageManager.getPackageArchiveInfo(it.absolutePath, 0)?.longVersionCode } ?: 0L
        val installed = expectedPackage?.let { packageName ->
            runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode >= requiredVersion }.getOrDefault(false)
        } == true
        if (installed) {
            showStatus("Đã cài ${candidate?.name ?: "APK"}.")
            installIndex++
            installNextApk()
        } else {
            showStatus("Android chưa xác nhận cài ${candidate?.name ?: "APK"}.")
            PiperDialog.showCustom(
                this,
                "Chưa cài xong",
                "Bạn có thể mở lại trình cài đặt cho APK này hoặc bỏ qua và tiếp tục danh sách.",
                positiveLabel = "Thử lại",
                negativeLabel = "Bỏ qua",
                onPositive = { installNextApk(); true },
                onNegative = { installIndex++; installNextApk() }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = layoutInflater.inflate(R.layout.activity_piper_data_transfer_received, null) as FrameLayout
        container = root.findViewById(R.id.pdtReceivedContainer)
        status = root.findViewById(R.id.pdtReceivedStatus)
        root.findViewById<MaterialButton>(R.id.pdtReceivedBack).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperDataTransferReceivedActivity))
            setTextColor(PiperModernUi.textColor(this@PiperDataTransferReceivedActivity))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this@PiperDataTransferReceivedActivity))
            setOnClickListener { finish() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top + dp(4), bars.right, bars.bottom + dp(8))
            insets
        }
        setContentView(root)
        PiperModernUi.watch(this)
        receivedRoot = intent.getStringExtra(EXTRA_SESSION_DIR)?.let(::File)
        files = receivedRoot?.listFiles()?.filter { it.isFile && it.name != ".complete" }?.map { file ->
            val name = file.name.substringAfter('-', file.name)
            val mime = contentMime(name)
            Staged(file, name, mime)
        }.orEmpty()
        buildContent()
    }

    private fun buildContent() {
        container.removeAllViews()
        // The XML placeholder card is detached above, but still owns its TextView.
        // Detach the reusable status view before putting it inside the rebuilt card.
        (status.parent as? android.view.ViewGroup)?.removeView(status)
        container.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(text("Dữ liệu đã nhận", 19f, true))
            addView(text("${files.size} mục • ${formatBytes(files.sumOf { it.file.length() })}", 13f, false), top(5))
        }), bottom(12))
        status.text = if (files.isEmpty()) "Phiên này chưa có dữ liệu nhận hoàn tất." else "Kiểm tra dữ liệu rồi chọn cách lưu hoặc cài ứng dụng."
        container.addView(card(status), bottom(12))
        if (files.isEmpty()) return

        val applications = files.filter { isApk(it) }
        val images = files.filter { it.mime.startsWith("image/") }
        val videos = files.filter { it.mime.startsWith("video/") }
        val other = files.filter { !isApk(it) && !it.mime.startsWith("image/") && !it.mime.startsWith("video/") }
        if (applications.isNotEmpty()) addGroup("Ứng dụng", "${applications.size} APK", applications)
        if (images.isNotEmpty()) addGroup("Ảnh", "${images.size} ảnh", images)
        if (videos.isNotEmpty()) addGroup("Video", "${videos.size} video", videos)
        if (other.isNotEmpty()) addGroup("Tệp khác", "${other.size} tệp", other)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)) }
        actions.addView(text("Lưu vào thiết bị", 15f, true), bottom(4))
        actions.addView(text("Chọn thư mục đích. PiperOS sẽ giữ bản nhận tạm cho đến khi bạn xóa.", 12f, false), bottom(10))
        actions.addView(button("Nhập tất cả vào thư mục đã chọn") { folderPicker.launch(null) }, bottom(8))
        if (applications.isNotEmpty()) {
            actions.addView(button("Cài tất cả APK lần lượt") { beginApkInstall(applications.map { it.file }) }, bottom(8))
        }
        actions.addView(button("Xóa dữ liệu đã nhận", secondary = true) { deleteReceived() })
        container.addView(card(actions), bottom(12))
    }

    private fun addGroup(title: String, detail: String, items: List<Staged>) {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)) }
        group.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(detail, 12f, false))
        }, bottom(9))
        items.forEach { item ->
            val archive = if (isApk(item)) archiveInfo(item.file) else null
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = GradientDrawable().apply {
                    setColor(PiperModernUi.surfaceColor(this@PiperDataTransferReceivedActivity))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), PiperModernUi.borderColor(this@PiperDataTransferReceivedActivity))
                }
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val appIcon = archive?.applicationInfo?.let { appInfo -> runCatching { appInfo.loadIcon(packageManager) }.getOrNull() }
                if (appIcon != null) {
                    setImageDrawable(appIcon)
                } else {
                    setImageResource(when {
                        item.mime.startsWith("image/") -> android.R.drawable.ic_menu_gallery
                        item.mime.startsWith("video/") -> android.R.drawable.ic_media_play
                        isApk(item) -> android.R.drawable.sym_def_app_icon
                        else -> android.R.drawable.ic_menu_save
                    })
                    imageTintList = if (isApk(item)) null else android.content.res.ColorStateList.valueOf(PiperModernUi.accentColor(this@PiperDataTransferReceivedActivity))
                }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
            val label = archive?.applicationInfo?.let { appInfo ->
                runCatching { appInfo.loadLabel(packageManager).toString() }.getOrNull()
            }?.takeIf(String::isNotBlank)
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(label ?: item.name, 14f, true).apply { maxLines = 2 })
                addView(text(if (archive != null) "${archive.packageName} • ${formatBytes(item.file.length())}" else formatBytes(item.file.length()), 12f, false), top(4))
            }
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
            group.addView(row, bottom(7))
            if (isApk(item)) group.addView(button("Cài ${label ?: item.name}") { beginApkInstall(listOf(item.file)) }, bottom(7))
        }
        container.addView(card(group), bottom(11))
    }

    private fun archiveInfo(file: File) = runCatching {
        packageManager.getPackageArchiveInfo(file.absolutePath, PackageManagerCompat.archiveFlags)?.also { info ->
            info.applicationInfo?.let { app -> app.sourceDir = file.absolutePath; app.publicSourceDir = file.absolutePath }
        }
    }.getOrNull()

    private fun isApk(item: Staged): Boolean = item.mime == "application/vnd.android.package-archive" || item.name.endsWith(".apk", true)

    private fun importStagedFiles(treeUri: Uri) {
        val destination = DocumentFile.fromTreeUri(this, treeUri)
        if (destination == null || !destination.canWrite()) { showStatus("Thư mục đã chọn không cho phép ghi tệp."); return }
        val outputDir = destination.createDirectory("PiperOS Transfer ${System.currentTimeMillis()}") ?: destination
        showStatus("Đang nhập ${files.size} mục vào ${outputDir.name ?: "thư mục đã chọn"}…")
        io.execute {
            try {
                var copied = 0
                files.forEach { item ->
                    val target = outputDir.createFile(item.mime.ifBlank { "application/octet-stream" }, item.name)
                        ?: throw IllegalStateException("Không tạo được ${item.name}")
                    contentResolver.openOutputStream(target.uri, "w")?.use { output -> FileInputStream(item.file).use { it.copyTo(output, 128 * 1024) } }
                        ?: throw IllegalStateException("Không ghi được ${item.name}")
                    copied++
                    runOnUiThread { status.text = "Đã nhập $copied/${files.size}: ${item.name}" }
                }
                runOnUiThread { showStatus("Đã nhập đủ $copied mục vào ${outputDir.name ?: "thư mục đã chọn"}.") }
            } catch (error: Exception) { runOnUiThread { showStatus("Không nhập được dữ liệu: ${error.localizedMessage}") } }
        }
    }

    private fun beginApkInstall(items: List<File>) { awaitingInstall = items; installIndex = 0; installNextApk() }

    private fun installNextApk() {
        if (installIndex >= awaitingInstall.size) { showStatus("Đã xử lý xong ${awaitingInstall.size} APK. Kiểm tra ứng dụng đã cài."); return }
        val file = awaitingInstall[installIndex]
        val packageName = packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
        if (packageName.isNullOrBlank()) {
            PiperDialog.showMessage(this, "APK không hợp lệ", "${file.name} không phải gói Android hợp lệ; tệp này sẽ được bỏ qua.", onPositive = {
                installIndex++
                installNextApk()
            })
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            PiperDialog.showConfirm(this, "Cho phép cài APK", "Android cần bạn cho PiperOS Tool mở trình cài đặt. Mỗi ứng dụng vẫn cần được xác nhận.", "Mở cài đặt") {
                installSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${applicationContext.packageName}")))
            }
            return
        }
        installingPackage = packageName
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${applicationContext.packageName}.files", file)
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = android.content.ClipData.newRawUri("PiperOS APK", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            showStatus("Đang mở cài đặt ${installIndex + 1}/${awaitingInstall.size}: ${file.name}")
            installLauncher.launch(installIntent)
        }.onFailure { error ->
            showStatus("Không mở được trình cài APK: ${error.localizedMessage ?: error.javaClass.simpleName}")
            PiperDialog.showMessage(this, "Không mở được trình cài đặt", error.localizedMessage ?: "Kiểm tra lại APK rồi thử lần nữa.")
        }
    }

    private fun deleteReceived() {
        val root = receivedRoot ?: return
        PiperDialog.showConfirm(this, "Xóa dữ liệu đã nhận?", "Các tệp trong vùng chờ của phiên này sẽ bị xóa khỏi PiperOS Tool.", "Xóa", destructive = true) {
            root.deleteRecursively()
            receivedRoot = null
            files = emptyList()
            buildContent()
            showStatus("Đã xóa dữ liệu đã nhận.")
        }
    }

    private fun contentMime(name: String): String = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        ?: if (name.endsWith(".apk", true)) "application/vnd.android.package-archive" else "application/octet-stream"

    private fun showStatus(value: String) { if (::status.isInitialized) status.text = value }
    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(PiperModernUi.textColor(this@PiperDataTransferReceivedActivity))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(value: String, secondary: Boolean = false, action: () -> Unit) = MaterialButton(this).apply {
        text = value; isAllCaps = false; minHeight = dp(48); insetTop = 0; insetBottom = 0; cornerRadius = dp(14)
        if (secondary) { backgroundTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.surfaceColor(this@PiperDataTransferReceivedActivity)); setTextColor(PiperModernUi.textColor(this@PiperDataTransferReceivedActivity)); strokeWidth = dp(1); strokeColor = android.content.res.ColorStateList.valueOf(PiperModernUi.borderColor(this@PiperDataTransferReceivedActivity)) }
        setOnClickListener { action() }
    }
    private fun card(child: View) = MaterialCardView(this).apply {
        radius = dp(18).toFloat(); cardElevation = 0f; strokeWidth = dp(1); strokeColor = PiperModernUi.borderColor(this@PiperDataTransferReceivedActivity)
        setCardBackgroundColor(PiperModernUi.surfaceColor(this@PiperDataTransferReceivedActivity)); addView(child, FrameLayout.LayoutParams(-1, -2))
    }
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun bottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
    private fun top(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }

    private object PackageManagerCompat { const val archiveFlags = android.content.pm.PackageManager.GET_META_DATA }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
