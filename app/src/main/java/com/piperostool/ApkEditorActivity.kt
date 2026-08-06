package com.piperostool

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ApkEditorActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var empty: TextView
    private lateinit var progressPanel: View
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPercent: TextView
    private lateinit var progressDetail: TextView
    private lateinit var adapter: WorkspaceFileAdapter
    private var workspace: ApkWorkspace? = null
    private var currentPrefix = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_apk_editor)
        bindViews()
        applyInsets()
        configureActions()
        val restored = savedInstanceState?.getString(STATE_WORKSPACE)?.let(ApkWorkspace::restore)
        if (restored != null) {
            setWorkspace(restored)
        } else {
            importSource()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        workspace?.let { outState.putString(STATE_WORKSPACE, it.root.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (currentPrefix.isNotEmpty()) {
            currentPrefix = currentPrefix.substringBeforeLast('/', "")
            renderFiles()
        } else {
            super.onBackPressed()
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.apkEditorRoot)
        toolbar = findViewById(R.id.apkEditorToolbar)
        title = findViewById(R.id.apkEditorTitle)
        subtitle = findViewById(R.id.apkEditorSubtitle)
        breadcrumb = findViewById(R.id.apkEditorBreadcrumb)
        empty = findViewById(R.id.apkEditorEmpty)
        progressPanel = findViewById(R.id.apkEditorProgressPanel)
        progressBar = findViewById(R.id.apkEditorProgress)
        progressPercent = findViewById(R.id.apkEditorProgressPercent)
        progressDetail = findViewById(R.id.apkEditorProgressDetail)
        adapter = WorkspaceFileAdapter(::openEntry)
        findViewById<RecyclerView>(R.id.apkEditorFiles).apply {
            layoutManager = LinearLayoutManager(this@ApkEditorActivity)
            adapter = this@ApkEditorActivity.adapter
        }
    }

    private fun applyInsets() {
        val initialTop = toolbar.paddingTop
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, initialTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, initialBottom + bars.bottom)
            insets
        }
    }

    private fun configureActions() {
        findViewById<View>(R.id.btnApkEditorBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnApkDecode).setOnClickListener { showDecodeOptions() }
        findViewById<View>(R.id.btnApkManifest).setOnClickListener { openManifestReport() }
        findViewById<View>(R.id.btnApkStrings).setOnClickListener { openStrings() }
        findViewById<View>(R.id.btnApkBuild).setOnClickListener { buildApk() }
    }

    private fun importSource() {
        runBusy("Đang đọc APK") {
            val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
            val created = if (!apkPath.isNullOrBlank()) {
                ApkWorkspace.createFromPath(this, File(apkPath))
            } else {
                val uri: Uri = intent.data ?: error("Chưa chọn tệp APK")
                ApkWorkspace.createFromUri(this, uri)
            }
            withContext(Dispatchers.Main) { setWorkspace(created) }
        }
    }

    private fun setWorkspace(value: ApkWorkspace) {
        workspace = value
        val info = packageManager.getPackageArchiveInfo(value.sourceApk.absolutePath, 0)
        title.text = info?.applicationInfo?.let {
            it.sourceDir = value.sourceApk.absolutePath
            it.publicSourceDir = value.sourceApk.absolutePath
            runCatching { it.loadLabel(packageManager).toString() }.getOrNull()
        } ?: value.root.name.substringAfter('-')
        subtitle.text = buildString {
            append(info?.packageName ?: "APK workspace")
            append(" • ")
            append(Formatter.formatShortFileSize(this@ApkEditorActivity, value.sourceApk.length()))
        }
        renderFiles()
    }

    private fun renderFiles() {
        val entries = workspace?.list(currentPrefix).orEmpty()
        adapter.submit(entries)
        breadcrumb.text = if (currentPrefix.isEmpty()) "APK /" else "APK / $currentPrefix"
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEntry(entry: ApkWorkspaceEntry) {
        if (busy) return
        if (entry.isDirectory) {
            currentPrefix = entry.archivePath
            renderFiles()
            return
        }
        runBusy("Đang mở ${entry.name}") {
            val file = workspace!!.extractEntry(entry.archivePath)
            withContext(Dispatchers.Main) {
                renderFiles()
                if (isText(file)) {
                    startActivity(
                        Intent(this@ApkEditorActivity, TextEditorActivity::class.java)
                            .putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
                    )
                } else {
                    AlertDialog.Builder(this@ApkEditorActivity)
                        .setTitle(entry.name)
                        .setMessage(
                            "Tệp nhị phân đã được giải nén vào workspace. " +
                                "Kích thước: ${Formatter.formatShortFileSize(this@ApkEditorActivity, file.length())}."
                        )
                        .setPositiveButton("Đóng", null)
                        .show()
                }
            }
        }
    }

    private fun showDecodeOptions() {
        val options = ApkDecodeScope.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Phạm vi giải nén")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                decode(options[which])
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun decode(scope: ApkDecodeScope) {
        runBusy("Chuẩn bị giải nén") {
            val count = workspace!!.decode(scope) { progress ->
                runOnUiThread { showProgress(progress) }
            }
            withContext(Dispatchers.Main) {
                renderFiles()
                Toast.makeText(this@ApkEditorActivity, "Đã giải nén $count tệp", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openManifestReport() {
        val workspace = workspace ?: return
        runBusy("Đang đọc Manifest") {
            val report = createManifestReport(workspace)
            withContext(Dispatchers.Main) {
                startActivity(
                    Intent(this@ApkEditorActivity, TextEditorActivity::class.java)
                        .putExtra(TextEditorActivity.EXTRA_FILE_PATH, report.absolutePath)
                )
            }
        }
    }

    private fun createManifestReport(workspace: ApkWorkspace): File {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS
        val info = packageManager.getPackageArchiveInfo(workspace.sourceApk.absolutePath, flags)
            ?: error("Không đọc được Manifest APK")
        val output = File(workspace.root, "reports/AndroidManifest-report.txt")
        output.parentFile?.mkdirs()
        output.writeText(buildString {
            appendLine("PiperOS APK Editor - Manifest report")
            appendLine("Lưu ý: AndroidManifest.xml trong APK là Binary XML. Báo cáo này chỉ đọc, không ghi đè file binary.")
            appendLine()
            appendLine("package: ${info.packageName}")
            appendLine("versionName: ${info.versionName}")
            appendLine("versionCode: ${PackageInfoCompat.getLongVersionCode(info)}")
            appendLine("minSdk: ${info.applicationInfo?.minSdkVersion}")
            appendLine("targetSdk: ${info.applicationInfo?.targetSdkVersion}")
            appendLine()
            appendLine("uses-permission:")
            info.requestedPermissions.orEmpty().sorted().forEach { appendLine("  - $it") }
            appendComponents("activities", info.activities?.map { it.name }.orEmpty())
            appendComponents("services", info.services?.map { it.name }.orEmpty())
            appendComponents("receivers", info.receivers?.map { it.name }.orEmpty())
            appendComponents("providers", info.providers?.map { it.name }.orEmpty())
        })
        return output
    }

    private fun StringBuilder.appendComponents(label: String, values: List<String>) {
        appendLine()
        appendLine("$label (${values.size}):")
        values.sorted().forEach { appendLine("  - $it") }
    }

    private fun openStrings() {
        val workspace = workspace ?: return
        runBusy("Đang tìm strings.xml") {
            if (workspace.stringsFiles().isEmpty()) {
                workspace.decode(ApkDecodeScope.RESOURCES) { progress ->
                    runOnUiThread { showProgress(progress) }
                }
            }
            val strings = workspace.stringsFiles()
            withContext(Dispatchers.Main) {
                if (strings.isEmpty()) {
                    AlertDialog.Builder(this@ApkEditorActivity)
                        .setTitle("String resources đã biên dịch")
                        .setMessage(
                            "APK này lưu chuỗi trong resources.arsc và Binary XML. " +
                                "Chế độ ZIP an toàn không thể sửa chuỗi đó mà không có apktool/aapt2. " +
                                "Các APK hoặc project có strings.xml dạng văn bản vẫn chỉnh sửa trực tiếp được."
                        )
                        .setPositiveButton("Đã hiểu", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@ApkEditorActivity)
                        .setTitle("Ngôn ngữ (${strings.size})")
                        .setItems(strings.map { it.parentFile?.name + "/" + it.name }.toTypedArray()) { _, index ->
                            startActivity(
                                Intent(this@ApkEditorActivity, TextEditorActivity::class.java)
                                    .putExtra(TextEditorActivity.EXTRA_FILE_PATH, strings[index].absolutePath)
                            )
                        }
                        .show()
                }
            }
        }
    }

    private fun buildApk() {
        val workspace = workspace ?: return
        AlertDialog.Builder(this)
            .setTitle("Xây dựng APK đã chỉnh sửa")
            .setMessage(
                "APK đầu ra sẽ được ký bằng PiperOS Editor key. " +
                    "Nó không thể cập nhật đè ứng dụng gốc có chữ ký khác."
            )
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xây dựng") { _, _ ->
                runBusy("Chuẩn bị xây dựng") {
                    val output = PiperApkBuilder(this@ApkEditorActivity).build(workspace) { progress ->
                        runOnUiThread { showProgress(progress) }
                    }
                    val exported = exportToDownloads(output)
                    withContext(Dispatchers.Main) { showBuildResult(output, exported) }
                }
            }
            .show()
    }

    private fun showBuildResult(output: File, exported: Uri?) {
        AlertDialog.Builder(this)
            .setTitle("Xây dựng hoàn tất")
            .setMessage("APK đã ký: ${output.name}\nĐã lưu vào Download/PiperOS_APK_Editor")
            .setNegativeButton("Đóng", null)
            .setNeutralButton("Chia sẻ") { _, _ -> shareApk(output) }
            .setPositiveButton("Cài đặt") { _, _ -> installApk(exported ?: fileUri(output)) }
            .show()
    }

    private fun exportToDownloads(file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PiperOS_APK_Editor")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun shareApk(file: File) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, fileUri(file))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Chia sẻ APK"
            )
        )
    }

    private fun installApk(uri: Uri) {
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun fileUri(file: File): Uri = FileProvider.getUriForFile(
        this,
        "$packageName.files",
        file
    )

    private fun isText(file: File): Boolean {
        if (file.extension.lowercase(Locale.US) !in TEXT_EXTENSIONS) return false
        return file.inputStream().use { input ->
            val sample = ByteArray(512)
            val count = input.read(sample)
            count <= 0 || sample.take(count).none { it == 0.toByte() }
        }
    }

    private fun runBusy(initial: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        progressPanel.visibility = View.VISIBLE
        progressDetail.text = initial
        progressBar.isIndeterminate = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { block() }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ApkEditorActivity,
                            "Lỗi: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            withContext(Dispatchers.Main) {
                busy = false
                progressPanel.visibility = View.GONE
            }
        }
    }

    private fun showProgress(progress: ApkWorkspaceProgress) {
        progressPanel.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = progress.percent
        progressPercent.text = "${progress.percent}%"
        progressDetail.text = buildString {
            append(progress.phase)
            append(" • ")
            append(progress.completed)
            append('/')
            append(progress.total)
            append(" tệp • ")
            append(String.format(Locale.US, "%.1fs", progress.elapsedMillis / 1000.0))
        }
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val STATE_WORKSPACE = "workspace"
        private val TEXT_EXTENSIONS = setOf(
            "xml", "json", "txt", "html", "htm", "css", "js", "md", "properties", "yml", "yaml"
        )
    }
}
