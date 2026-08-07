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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
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
import com.bumptech.glide.Glide
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
    private lateinit var search: EditText
    private lateinit var fileCount: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var adapter: WorkspaceFileAdapter
    private var workspace: ApkWorkspace? = null
    private var currentPrefix = ""
    private var busy = false
    private var selectionMode = false
    private val selectedPaths = linkedSetOf<String>()
    private var currentEntries = emptyList<ApkWorkspaceEntry>()
    private var pendingBackupPaths = emptyList<String>()

    private val backupDestination = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        exportBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_apk_editor)
        bindViews()
        applyInsets()
        configureActions()
        configureBackNavigation()
        val restored = savedInstanceState?.getString(STATE_WORKSPACE)?.let(ApkWorkspace::restore)
        if (restored != null) {
            currentPrefix = savedInstanceState.getString(STATE_PREFIX).orEmpty()
            setWorkspace(restored)
        } else {
            importSource()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        workspace?.let { outState.putString(STATE_WORKSPACE, it.root.absolutePath) }
        outState.putString(STATE_PREFIX, currentPrefix)
        super.onSaveInstanceState(outState)
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectionMode -> leaveSelectionMode()
                    currentPrefix.isNotEmpty() -> {
                        currentPrefix = currentPrefix.substringBeforeLast('/', "")
                        renderFiles()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
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
        search = findViewById(R.id.apkEditorSearch)
        fileCount = findViewById(R.id.apkEditorFileCount)
        selectionBar = findViewById(R.id.apkEditorSelectionBar)
        selectionCount = findViewById(R.id.apkEditorSelectionCount)
        adapter = WorkspaceFileAdapter(::handleEntryClick, ::toggleSelection, ::loadThumbnail)
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
        findViewById<View>(R.id.btnApkEditorBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<View>(R.id.btnApkDecode).setOnClickListener { showDecodeOptions() }
        findViewById<View>(R.id.btnApkManifest).setOnClickListener { openManifestReport() }
        findViewById<View>(R.id.btnApkStrings).setOnClickListener { openStrings() }
        findViewById<View>(R.id.btnApkBuild).setOnClickListener { buildApk() }
        findViewById<View>(R.id.btnApkSelect).setOnClickListener { enterSelectionMode() }
        findViewById<View>(R.id.btnApkSelectionCancel).setOnClickListener { leaveSelectionMode() }
        findViewById<View>(R.id.btnApkSelectionBackup).setOnClickListener { chooseBackupDestination() }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(value: Editable?) = applyFileFilter(value?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
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
        if (currentPrefix.isEmpty()) {
            currentPrefix = intent.getStringExtra(EXTRA_START_PATH)?.trim('/').orEmpty()
        }
        renderFiles()
    }

    private fun renderFiles() {
        currentEntries = workspace?.list(currentPrefix).orEmpty()
        applyFileFilter(search.text?.toString().orEmpty())
        breadcrumb.text = if (currentPrefix.isEmpty()) "APK /" else "APK / $currentPrefix"
        fileCount.text = "${currentEntries.count { it.isDirectory }} thư mục • " +
            "${currentEntries.count { !it.isDirectory }} tệp"
    }

    private fun applyFileFilter(query: String) {
        val filtered = if (query.isBlank()) currentEntries else currentEntries.filter {
            it.name.contains(query.trim(), ignoreCase = true)
        }
        adapter.submit(filtered)
        adapter.setSelected(selectedPaths)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleEntryClick(entry: ApkWorkspaceEntry) {
        if (selectionMode) toggleSelection(entry) else openEntry(entry)
    }

    private fun loadThumbnail(entry: ApkWorkspaceEntry, target: android.widget.ImageView) {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = runCatching { workspace?.previewFile(entry.archivePath) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (file != null && target.contentDescription == entry.archivePath && !isDestroyed) {
                    target.setPadding(0, 0, 0, 0)
                    target.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    Glide.with(target).load(file).dontAnimate().fitCenter().into(target)
                }
            }
        }
    }

    private fun enterSelectionMode() {
        if (busy) return
        selectionMode = true
        selectionBar.visibility = View.VISIBLE
        updateSelectionUi()
        Toast.makeText(this, "Chạm để chọn tệp hoặc thư mục", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSelection(entry: ApkWorkspaceEntry) {
        if (!selectionMode) selectionMode = true
        if (!selectedPaths.add(entry.archivePath)) selectedPaths.remove(entry.archivePath)
        selectionBar.visibility = View.VISIBLE
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        selectionCount.text = "ĐÃ CHỌN ${selectedPaths.size}"
        findViewById<View>(R.id.btnApkSelectionBackup).isEnabled = selectedPaths.isNotEmpty()
        findViewById<View>(R.id.btnApkSelectionBackup).alpha =
            if (selectedPaths.isEmpty()) 0.45f else 1f
        adapter.setSelected(selectedPaths)
    }

    private fun leaveSelectionMode() {
        selectionMode = false
        selectedPaths.clear()
        selectionBar.visibility = View.GONE
        adapter.setSelected(emptySet())
    }

    private fun chooseBackupDestination() {
        if (selectedPaths.isEmpty()) return
        pendingBackupPaths = selectedPaths.toList()
        backupDestination.launch(null)
    }

    private fun exportBackup(uri: Uri) {
        val paths = pendingBackupPaths
        if (paths.isEmpty()) return
        val currentWorkspace = workspace ?: return
        val service = Intent(this, ApkBackupService::class.java)
            .setAction(ApkBackupService.ACTION_START)
            .putExtra(ApkBackupService.EXTRA_WORKSPACE_ROOT, currentWorkspace.root.absolutePath)
            .putExtra(ApkBackupService.EXTRA_DESTINATION_URI, uri.toString())
            .putStringArrayListExtra(ApkBackupService.EXTRA_PATHS, ArrayList(paths))
        ContextCompat.startForegroundService(this, service)
        pendingBackupPaths = emptyList()
        leaveSelectionMode()
        Toast.makeText(
            this,
            "Backup đang chạy nền. Bạn có thể tắt màn hình hoặc rời ứng dụng.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openEntry(entry: ApkWorkspaceEntry) {
        if (busy) return
        if (entry.isDirectory) {
            currentPrefix = entry.archivePath
            renderFiles()
            return
        }
        if (ApkMediaTypes.isVisualMedia(entry.name)) {
            val media = currentEntries.filter { !it.isDirectory && ApkMediaTypes.isVisualMedia(it.name) }
            startActivity(
                Intent(this, PiperMediaGalleryActivity::class.java)
                    .putExtra(PiperMediaGalleryActivity.EXTRA_WORKSPACE_ROOT, workspace!!.root.absolutePath)
                    .putStringArrayListExtra(PiperMediaGalleryActivity.EXTRA_MEDIA_PATHS, ArrayList(media.map { it.archivePath }))
                    .putExtra(PiperMediaGalleryActivity.EXTRA_INITIAL_INDEX, media.indexOfFirst { it.archivePath == entry.archivePath }.coerceAtLeast(0))
            )
            return
        }
        runBusy("Đang mở ${entry.name}") {
            val file = workspace!!.extractEntry(entry.archivePath)
            withContext(Dispatchers.Main) {
                renderFiles()
                startActivity(
                    Intent(this@ApkEditorActivity, PiperFilePreviewActivity::class.java)
                        .putExtra(PiperFilePreviewActivity.EXTRA_FILE_PATH, file.absolutePath)
                )
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return runCatching {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "PiperOS_APK_Editor"
                ).apply { mkdirs() }
                val target = File(directory, file.name)
                file.copyTo(target, overwrite = true)
                fileUri(target)
            }.getOrNull()
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PiperOS_APK_Editor")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
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
        const val EXTRA_START_PATH = "start_path"
        private const val STATE_WORKSPACE = "workspace"
        private const val STATE_PREFIX = "prefix"
        private val TEXT_EXTENSIONS = setOf(
            "xml", "json", "txt", "html", "htm", "css", "js", "md", "properties", "yml", "yaml"
        )
    }
}
