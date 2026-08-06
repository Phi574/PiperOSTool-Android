package com.piperostool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
import java.util.zip.ZipOutputStream

class PiperFileManagerActivity : AppCompatActivity() {
    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var pathView: TextView
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private lateinit var adapter: WorkspaceFileAdapter
    private var currentDirectory = Environment.getExternalStorageDirectory()
    private var archiveFile: File? = null
    private var archivePrefix = ""
    private var allEntries = emptyList<ApkWorkspaceEntry>()

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
        adapter = WorkspaceFileAdapter(::openEntry, ::showEntryActions)
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
            file.extension.lowercase() in ARCHIVE_EXTENSIONS -> {
                runCatching { ZipFile(file).close() }
                    .onSuccess {
                        archiveFile = file
                        archivePrefix = ""
                        render()
                    }
                    .onFailure { Toast.makeText(this, "Archive lỗi: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            isText(file) -> startActivity(
                Intent(this, TextEditorActivity::class.java)
                    .putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
            )
            else -> openExternal(file)
        }
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
            add("Nén thành ZIP")
            if (file.extension.lowercase() in ARCHIVE_EXTENSIONS) add("Giải nén tại đây")
            add("Xóa")
        }
        AlertDialog.Builder(this).setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    "Mở" -> openEntry(entry)
                    "Đổi tên" -> rename(file)
                    "Nén thành ZIP" -> compress(file)
                    "Giải nén tại đây" -> extractAll(file)
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
                    if (which == 0) extractAll(archive) else {
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

    private fun compress(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val defaultName = file.nameWithoutExtension + ".zip"
                val output = File(
                    file.parentFile,
                    if (file.name.equals(defaultName, ignoreCase = true)) {
                        file.nameWithoutExtension + "-archive.zip"
                    } else {
                        defaultName
                    }
                )
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    val base = if (file.isDirectory) file.parentFile!! else file.parentFile!!
                    file.walkTopDown().filter { it.isFile }.forEach { child ->
                        zip.putNextEntry(ZipEntry(child.relativeTo(base).invariantSeparatorsPath))
                        child.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                output
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    Toast.makeText(
                        this@PiperFileManagerActivity,
                        "Đã tạo ${it.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        this@PiperFileManagerActivity,
                        "Nén lỗi: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                render()
            }
        }
    }

    private fun extractAll(archive: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val target = File(archive.parentFile, archive.nameWithoutExtension)
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val output = safeArchiveOutput(target, entry.name)
                        if (entry.isDirectory) output.mkdirs() else {
                            output.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input -> FileOutputStream(output).use(input::copyTo) }
                        }
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) { Toast.makeText(this@PiperFileManagerActivity, "Giải nén lỗi: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            withContext(Dispatchers.Main) { archiveFile = null; render() }
        }
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

    companion object {
        private val ARCHIVE_EXTENSIONS = setOf("zip", "jar", "xapk", "apks")
        private val TEXT_EXTENSIONS = setOf("txt", "xml", "json", "md", "html", "css", "js", "properties", "yml", "yaml", "log")
    }
}
