package com.piperostool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
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
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class PiperFileManagerActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var pathView: TextView
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private lateinit var adapter: FileManagerAdapter
    private var currentDirectory = Environment.getExternalStorageDirectory()
    private var archiveFile: File? = null
    private var archivePrefix = ""
    private var allEntries = emptyList<ApkWorkspaceEntry>()
    private var receiverRegistered = false

    private val operationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(FileOperationService.EXTRA_MESSAGE).orEmpty()
            if (message.isNotBlank()) Toast.makeText(this@PiperFileManagerActivity, message, Toast.LENGTH_LONG).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_piper_file_manager)
        bindViews()
        applyInsets()
        configureActions()
        ensureStorageAccess()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) render()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                operationReceiver,
                IntentFilter(FileOperationService.ACTION_FINISHED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(operationReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onBackPressed() {
        when {
            archiveFile != null && archivePrefix.isNotEmpty() -> {
                archivePrefix = archivePrefix.substringBeforeLast('/', "")
                render()
            }
            archiveFile != null -> {
                archiveFile = null
                archivePrefix = ""
                render()
            }
            currentDirectory.parentFile != null &&
                currentDirectory != Environment.getExternalStorageDirectory() -> {
                currentDirectory = currentDirectory.parentFile!!
                render()
            }
            else -> super.onBackPressed()
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.fileManagerRoot)
        toolbar = findViewById(R.id.fileManagerToolbar)
        pathView = findViewById(R.id.fileManagerPath)
        search = findViewById(R.id.fileManagerSearch)
        empty = findViewById(R.id.fileManagerEmpty)
        adapter = FileManagerAdapter(::openEntry, ::showEntryActions, ::loadSpecialIcon)
        findViewById<RecyclerView>(R.id.fileManagerFiles).apply {
            layoutManager = LinearLayoutManager(this@PiperFileManagerActivity)
            adapter = this@PiperFileManagerActivity.adapter
        }
    }

    private fun applyInsets() {
        val top = toolbar.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, top + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom + bars.bottom)
            insets
        }
    }

    private fun configureActions() {
        findViewById<View>(R.id.btnFileManagerBack).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnFileManagerHome).setOnClickListener {
            archiveFile = null
            currentDirectory = Environment.getExternalStorageDirectory()
            render()
        }
        findViewById<View>(R.id.btnFileManagerRefresh).setOnClickListener { render() }
        findViewById<View>(R.id.btnFileManagerMore).setOnClickListener { showManagerActions() }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(value: Editable?) = applySearch(value?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle("Cho phép quản lý tệp")
                .setMessage("PiperOS File Manager cần quyền truy cập tất cả tệp để duyệt, nén và giải nén thư mục bạn chọn.")
                .setNegativeButton("Để sau", null)
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .show()
        }
    }

    private fun render() {
        allEntries = archiveFile?.let(::listArchive) ?: listDirectory(currentDirectory)
        pathView.text = archiveFile?.let {
            "${it.name} / ${archivePrefix.ifEmpty { "" }}"
        } ?: currentDirectory.absolutePath
        applySearch(search.text?.toString().orEmpty())
    }

    private fun applySearch(query: String) {
        val filtered = if (query.isBlank()) allEntries else allEntries.filter {
            it.name.contains(query, ignoreCase = true)
        }
        adapter.submit(filtered)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun listDirectory(directory: File): List<ApkWorkspaceEntry> =
        directory.listFiles()?.map { file ->
            ApkWorkspaceEntry(
                name = file.name,
                archivePath = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                extractedFile = file
            )
        }?.sortedWith(compareByDescending<ApkWorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

    private fun loadSpecialIcon(entry: ApkWorkspaceEntry, target: ImageView) {
        val file = entry.extractedFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val icon = runCatching {
                when {
                    file.isFile && file.extension.equals("apk", true) -> {
                        packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.applicationInfo?.let {
                            it.sourceDir = file.absolutePath
                            it.publicSourceDir = file.absolutePath
                            it.loadIcon(packageManager)
                        }
                    }
                    file.isDirectory && file.parentFile?.name in setOf("data", "obb") &&
                        file.parentFile?.parentFile?.name.equals("Android", true) ->
                        packageManager.getApplicationIcon(file.name)
                    else -> null
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (icon != null && target.contentDescription == entry.archivePath && !isDestroyed) {
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setImageDrawable(icon)
                }
            }
        }
    }

    private fun listArchive(file: File): List<ApkWorkspaceEntry> {
        val prefix = archivePrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val items = linkedMapOf<String, ApkWorkspaceEntry>()
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (!entry.name.startsWith(prefix) || entry.name == prefix) return@forEach
                val remainder = entry.name.removePrefix(prefix)
                val name = remainder.substringBefore('/')
                if (name.isEmpty()) return@forEach
                val directory = remainder.contains('/') || entry.isDirectory
                items[name] = ApkWorkspaceEntry(
                    name, prefix + name, directory,
                    if (directory) 0 else entry.size,
                    null
                )
            }
        }
        return items.values.sortedWith(compareByDescending<ApkWorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun openEntry(entry: ApkWorkspaceEntry) {
        val archive = archiveFile
        if (archive != null) {
            if (entry.isDirectory) {
                archivePrefix = entry.archivePath
                render()
            } else {
                extractSingleAndOpen(archive, entry.archivePath)
            }
            return
        }
        val file = File(entry.archivePath)
        when {
            file.isDirectory -> {
                currentDirectory = file
                render()
            }
            file.extension.equals("apk", true) -> startActivity(
                Intent(this, ApkEditorActivity::class.java)
                    .putExtra(ApkEditorActivity.EXTRA_APK_PATH, file.absolutePath)
            )
            ApkMediaTypes.isVisualMedia(file.name) -> openMediaGallery(file)
            FileArchiveFormat.detect(file.name) == FileArchiveFormat.ZIP -> {
                if (runCatching { net.lingala.zip4j.ZipFile(file).isEncrypted }.getOrDefault(false)) {
                    showExtractDialog(file)
                    return
                }
                runCatching { ZipFile(file).close() }
                    .onSuccess {
                        archiveFile = file
                        archivePrefix = ""
                        render()
                    }
                    .onFailure { Toast.makeText(this, "Archive lỗi: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            FileArchiveFormat.detect(file.name) != null -> showExtractDialog(file)
            isText(file) -> startActivity(
                Intent(this, TextEditorActivity::class.java)
                    .putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
            )
            else -> openExternal(file)
        }
    }

    private fun openMediaGallery(file: File) {
        val media = allEntries.mapNotNull { it.extractedFile }
            .filter { it.isFile && ApkMediaTypes.isVisualMedia(it.name) }
        startActivity(
            Intent(this, PiperMediaGalleryActivity::class.java)
                .putStringArrayListExtra(PiperMediaGalleryActivity.EXTRA_MEDIA_PATHS, ArrayList(media.map(File::getAbsolutePath)))
                .putExtra(PiperMediaGalleryActivity.EXTRA_DIRECT_FILES, true)
                .putExtra(PiperMediaGalleryActivity.EXTRA_INITIAL_INDEX, media.indexOf(file).coerceAtLeast(0))
        )
    }

    private fun showEntryActions(entry: ApkWorkspaceEntry) {
        if (archiveFile != null) {
            AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(arrayOf("Mở", "Giải nén mục này")) { _, index ->
                    if (index == 0) openEntry(entry) else extractArchiveEntry(entry)
                }.show()
            return
        }
        val file = File(entry.archivePath)
        val actions = buildList {
            add("Mở")
            add("Đổi tên")
            add("Nén / mã hóa")
            if (FileArchiveFormat.detect(file.name) != null) add("Giải nén tại đây")
            add("Xóa")
        }
        AlertDialog.Builder(this).setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    "Mở" -> openEntry(entry)
                    "Đổi tên" -> rename(file)
                    "Nén / mã hóa" -> showCompressDialog(file)
                    "Giải nén tại đây" -> showExtractDialog(file)
                    "Xóa" -> confirmDelete(file)
                }
            }.show()
    }

    private fun showManagerActions() {
        val archive = archiveFile
        val options = if (archive != null) {
            arrayOf("Giải nén toàn bộ", "Đóng archive")
        } else {
            arrayOf("Tạo thư mục", "Sắp xếp theo tên", "Sắp xếp theo kích thước")
        }
        AlertDialog.Builder(this).setTitle("Công cụ tệp")
            .setItems(options) { _, which ->
                if (archive != null) {
                    if (which == 0) showExtractDialog(archive) else {
                        archiveFile = null
                        render()
                    }
                } else when (which) {
                    0 -> createFolder()
                    1 -> adapter.submit(allEntries.sortedBy { it.name.lowercase() })
                    2 -> adapter.submit(allEntries.sortedByDescending { it.size })
                }
            }.show()
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "Tên thư mục" }
        AlertDialog.Builder(this).setTitle("Tạo thư mục").setView(input)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Tạo") { _, _ ->
                val target = File(currentDirectory, input.text.toString().trim())
                if (target.name.isNotEmpty() && target.mkdir()) render()
                else Toast.makeText(this, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun rename(file: File) {
        val input = EditText(this).apply { setText(file.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Đổi tên").setView(input)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ ->
                val target = File(file.parentFile, input.text.toString().trim())
                if (target.name.isNotEmpty() && file.renameTo(target)) render()
                else Toast.makeText(this, "Không thể đổi tên", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this).setTitle("Xóa ${file.name}?")
            .setMessage("Thao tác này không thể hoàn tác.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = file.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        if (!success) Toast.makeText(this@PiperFileManagerActivity, "Xóa thất bại", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }.show()
    }

    private fun showCompressDialog(file: File) {
        val formats = FileArchiveFormat.entries
        val presets = ArchiveCompressionPreset.entries
        val nameInput = EditText(this).apply {
            hint = "Tên archive"
            setText(file.nameWithoutExtension)
            selectAll()
        }
        val formatSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PiperFileManagerActivity,
                android.R.layout.simple_spinner_dropdown_item,
                formats.map { it.label }
            )
        }
        val levelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PiperFileManagerActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presets.map { it.label }
            )
            setSelection(ArchiveCompressionPreset.NORMAL.ordinal)
        }
        val passwordEnabled = CheckBox(this).apply {
            text = "Mật khẩu AES-256 (chỉ ZIP)"
            setTextColor(getColor(R.color.white))
        }
        val passwordInput = EditText(this).apply {
            hint = "Mật khẩu"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = View.GONE
        }
        passwordEnabled.setOnCheckedChangeListener { _, checked ->
            passwordInput.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) formatSpinner.setSelection(FileArchiveFormat.ZIP.ordinal)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameInput)
            addView(TextView(this@PiperFileManagerActivity).apply { text = "Định dạng"; setTextColor(0xBFFFFFFF.toInt()) })
            addView(formatSpinner)
            addView(TextView(this@PiperFileManagerActivity).apply { text = "Mức nén"; setTextColor(0xBFFFFFFF.toInt()) })
            addView(levelSpinner)
            addView(passwordEnabled)
            addView(passwordInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Tạo archive")
            .setView(panel)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Bắt đầu") { _, _ ->
                val format = formats[formatSpinner.selectedItemPosition]
                val password = passwordInput.text.toString().takeIf { passwordEnabled.isChecked && it.isNotEmpty() }
                if (passwordEnabled.isChecked && password == null) {
                    Toast.makeText(this, "Mật khẩu không được để trống", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val baseName = nameInput.text.toString().trim().ifEmpty { file.nameWithoutExtension }
                val output = uniqueOutput(File(file.parentFile, baseName + format.extension))
                startFileOperation(
                    FileOperationService.ACTION_COMPRESS,
                    file,
                    output,
                    format,
                    presets[levelSpinner.selectedItemPosition],
                    password
                )
            }
            .show()
    }

    private fun showExtractDialog(archive: File) {
        val password = EditText(this).apply {
            hint = "Mật khẩu nếu archive có mã hóa"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Giải nén ${archive.name}")
            .setMessage("Tác vụ tiếp tục khi tắt màn hình hoặc rời ứng dụng.")
            .setView(password)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Giải nén") { _, _ ->
                val target = uniqueDirectory(File(archive.parentFile, archiveBaseName(archive.name)))
                startFileOperation(
                    FileOperationService.ACTION_EXTRACT,
                    archive,
                    target,
                    password = password.text.toString().takeIf(String::isNotEmpty)
                )
                archiveFile = null
            }
            .show()
    }

    private fun startFileOperation(
        action: String,
        source: File,
        target: File,
        format: FileArchiveFormat? = null,
        preset: ArchiveCompressionPreset? = null,
        password: String? = null
    ) {
        if (FileOperationService.running) {
            Toast.makeText(this, "Một tác vụ tệp khác đang chạy", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, FileOperationService::class.java)
            .setAction(action)
            .putExtra(FileOperationService.EXTRA_SOURCE, source.absolutePath)
            .putExtra(FileOperationService.EXTRA_TARGET, target.absolutePath)
            .putExtra(FileOperationService.EXTRA_PASSWORD, password)
        format?.let { intent.putExtra(FileOperationService.EXTRA_FORMAT, it.name) }
        preset?.let { intent.putExtra(FileOperationService.EXTRA_PRESET, it.name) }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Tác vụ đang chạy nền", Toast.LENGTH_SHORT).show()
    }

    private fun uniqueOutput(requested: File): File {
        if (!requested.exists()) return requested
        val extension = requested.name.substringAfter(requested.nameWithoutExtension, "")
        var index = 1
        while (true) {
            val candidate = File(requested.parentFile, "${requested.nameWithoutExtension} ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun uniqueDirectory(requested: File): File {
        if (!requested.exists()) return requested
        var index = 1
        while (true) {
            val candidate = File(requested.parentFile, "${requested.name} ($index)")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun archiveBaseName(name: String): String {
        val format = FileArchiveFormat.detect(name) ?: return name.substringBeforeLast('.')
        return name.dropLast(format.extension.length).ifBlank { "extracted" }
    }

    private fun extractArchiveEntry(entry: ApkWorkspaceEntry) {
        val archive = archiveFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val target = File(archive.parentFile, archive.nameWithoutExtension)
                ZipFile(archive).use { zip ->
                    if (entry.isDirectory) {
                        zip.entries().asSequence().filter { it.name.startsWith(entry.archivePath + "/") }
                            .forEach { extractZipEntry(zip, it, target) }
                    } else {
                        val zipEntry = zip.getEntry(entry.archivePath)
                            ?: error("Không tìm thấy ${entry.archivePath}")
                        extractZipEntry(zip, zipEntry, target)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    Toast.makeText(
                        this@PiperFileManagerActivity,
                        "Đã giải nén ${entry.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        this@PiperFileManagerActivity,
                        "Giải nén lỗi: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun extractSingleAndOpen(archive: File, path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val output = runCatching {
                val cache = File(cacheDir, "archive-preview/${archive.nameWithoutExtension}")
                ZipFile(archive).use { zip ->
                    val entry = zip.getEntry(path) ?: error("Không tìm thấy tệp")
                    val file = safeArchiveOutput(cache, entry.name)
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> FileOutputStream(file).use(input::copyTo) }
                    file
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (output == null) Toast.makeText(this@PiperFileManagerActivity, "Không mở được tệp", Toast.LENGTH_SHORT).show()
                else if (isText(output)) startActivity(Intent(this@PiperFileManagerActivity, TextEditorActivity::class.java).putExtra(TextEditorActivity.EXTRA_FILE_PATH, output.absolutePath))
                else openExternal(output)
            }
        }
    }

    private fun extractZipEntry(zip: ZipFile, entry: ZipEntry, target: File) {
        val output = safeArchiveOutput(target, entry.name)
        if (entry.isDirectory) output.mkdirs() else {
            output.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> FileOutputStream(output).use(input::copyTo) }
        }
    }

    private fun safeArchiveOutput(target: File, name: String): File {
        val output = File(target, name)
        val rootPath = target.canonicalPath + File.separator
        check(output.canonicalPath.startsWith(rootPath)) { "Archive chứa đường dẫn không an toàn" }
        return output
    }

    private fun openExternal(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "Không có ứng dụng mở định dạng này", Toast.LENGTH_SHORT).show() }
    }

    private fun isText(file: File): Boolean = file.extension.lowercase(Locale.US) in TEXT_EXTENSIONS

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val TEXT_EXTENSIONS = setOf("txt", "xml", "json", "md", "html", "css", "js", "properties", "yml", "yaml", "log")
    }
}
