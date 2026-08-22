package com.piperostool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.piperostool.privileged.PiperPrivilegedPreferences
import com.piperostool.privileged.client.PiperPrivilegedClient
import com.piperostool.privileged.ui.AdvancedAccessActivity
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
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var adapter: FileManagerAdapter
    private var currentDirectory = Environment.getExternalStorageDirectory()
    private var archiveFile: File? = null
    private var archivePrefix = ""
    private var allEntries = emptyList<ApkWorkspaceEntry>()
    private var visibleEntries = emptyList<ApkWorkspaceEntry>()
    private val selectedPaths = linkedSetOf<String>()
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private var receiverRegistered = false
    private var operationDialog: Dialog? = null
    private var operationProgress: ProgressBar? = null
    private var operationPercent: TextView? = null
    private var operationDetail: TextView? = null
    private var pendingDestinationInput: EditText? = null
    private lateinit var privilegedClient: PiperPrivilegedClient
    private var renderGeneration = 0
    private var directoryLoadJob: Job? = null
    private val destinationPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        resolveTreePath(uri)?.let { pendingDestinationInput?.setText(it) }
            ?: Toast.makeText(this, "Không thể dùng đường dẫn thư mục này", Toast.LENGTH_SHORT).show()
    }

    private val operationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FileOperationService.ACTION_PROGRESS) {
                updateOperationProgress(intent)
                return
            }
            val message = intent?.getStringExtra(FileOperationService.EXTRA_MESSAGE).orEmpty()
            operationDialog?.dismiss()
            operationDialog = null
            if (message.isNotBlank()) Toast.makeText(this@PiperFileManagerActivity, message, Toast.LENGTH_LONG).show()
            invalidateDirectoryCache(currentDirectory)
            render(forceRefresh = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_piper_file_manager)
        privilegedClient = PiperPrivilegedClient(this)
        bindViews()
        applyInsets()
        configureActions()
        configureBackNavigation()
        ensureStorageAccess()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && allEntries.isEmpty() && directoryLoadJob?.isActive != true) render()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                operationReceiver,
                IntentFilter().apply {
                    addAction(FileOperationService.ACTION_PROGRESS)
                    addAction(FileOperationService.ACTION_FINISHED)
                },
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

    override fun onDestroy() {
        directoryLoadJob?.cancel()
        privilegedClient.close()
        super.onDestroy()
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
                        navigateTo(currentDirectory.parentFile!!)
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
        root = findViewById(R.id.fileManagerRoot)
        toolbar = findViewById(R.id.fileManagerToolbar)
        pathView = findViewById(R.id.fileManagerPath)
        search = findViewById(R.id.fileManagerSearch)
        empty = findViewById(R.id.fileManagerEmpty)
        loading = findViewById(R.id.fileManagerLoading)
        loadingText = findViewById(R.id.fileManagerLoadingText)
        selectionBar = findViewById(R.id.fileSelectionBar)
        selectionCount = findViewById(R.id.fileSelectionCount)
        adapter = FileManagerAdapter(
            onClick = { entry ->
                if (selectedPaths.isEmpty()) openEntry(entry) else toggleSelection(entry)
            },
            onLongClick = ::toggleSelection,
            onSpecialIcon = ::loadSpecialIcon
        )
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
        findViewById<View>(R.id.btnFileManagerBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<View>(R.id.btnFileManagerHome).setOnClickListener {
            archiveFile = null
            navigateTo(Environment.getExternalStorageDirectory())
        }
        findViewById<View>(R.id.btnFileManagerRefresh).setOnClickListener { render(forceRefresh = true) }
        findViewById<View>(R.id.btnFileManagerMore).setOnClickListener { showManagerActions() }
        findViewById<View>(R.id.fileSelectionClose).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.fileSelectAll).setOnClickListener {
            selectedPaths.clear()
            selectedPaths += visibleEntries.map(ApkWorkspaceEntry::archivePath)
            updateSelectionUi()
        }
        findViewById<View>(R.id.fileCompressSelected).setOnClickListener {
            selectedFiles().takeIf { it.isNotEmpty() }?.let(::showCompressDialog)
        }
        findViewById<View>(R.id.fileCopySelected).setOnClickListener {
            showTransferDestination(FileOperationService.ACTION_COPY)
        }
        findViewById<View>(R.id.fileMoveSelected).setOnClickListener {
            showTransferDestination(FileOperationService.ACTION_MOVE)
        }
        findViewById<View>(R.id.fileBackupSelected).setOnClickListener { backupSelected() }
        findViewById<View>(R.id.fileDeleteSelected).setOnClickListener { confirmDeleteSelected() }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(value: Editable?) = applySearch(value?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            PiperDialog.showConfirm(
                context = this,
                title = "Cho phép quản lý tệp",
                message = "PiperOS File Manager cần quyền truy cập tất cả tệp để duyệt, nén và giải nén thư mục bạn chọn.",
                positiveLabel = "Mở cài đặt",
                negativeLabel = "Để sau"
            ) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
            }
        }
    }

    private fun render(forceRefresh: Boolean = false) {
        val archive = archiveFile
        if (archive != null) {
            directoryLoadJob?.cancel()
            renderGeneration++
            setDirectoryLoading(false)
            pathView.text = "${archive.name} / ${archivePrefix.ifEmpty { "" }}"
            allEntries = listArchive(archive)
            applySearch(search.text?.toString().orEmpty())
            return
        }
        loadDirectory(currentDirectory, commitNavigation = false, forceRefresh = forceRefresh)
    }

    private fun navigateTo(directory: File) {
        archiveFile = null
        archivePrefix = ""
        clearSelection()
        loadDirectory(directory, commitNavigation = true, forceRefresh = false)
    }

    private fun loadDirectory(directory: File, commitNavigation: Boolean, forceRefresh: Boolean) {
        val requestedDirectory = runCatching { directory.canonicalFile }.getOrDefault(directory.absoluteFile)
        val previousDirectory = currentDirectory
        val cacheKey = directoryCacheKey(requestedDirectory)
        val cached = synchronized(DIRECTORY_CACHE) { DIRECTORY_CACHE[cacheKey] }
        val generation = ++renderGeneration
        directoryLoadJob?.cancel()
        pathView.text = requestedDirectory.absolutePath
        if (cached != null && !forceRefresh) {
            if (commitNavigation) currentDirectory = requestedDirectory
            allEntries = cached
            applySearch(search.text?.toString().orEmpty())
            setDirectoryLoading(false)
            return
        } else {
            setDirectoryLoading(true, requestedDirectory)
        }
        directoryLoadJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val privileged = shouldUsePrivilegedBackend(requestedDirectory)
                    val remote = if (privileged) {
                        privilegedClient.list(
                            requestedDirectory.absolutePath,
                            PiperPrivilegedPreferences.showHidden(this@PiperFileManagerActivity)
                        ) ?: error("PPS không thể đọc ${requestedDirectory.absolutePath}")
                    } else null
                    remote?.map { entry ->
                        ApkWorkspaceEntry(
                            name = entry.name,
                            archivePath = entry.path,
                            isDirectory = entry.directory,
                            size = entry.size,
                            extractedFile = File(entry.path)
                        )
                    } ?: listDirectory(requestedDirectory)
                }
            }
            if (generation != renderGeneration || isDestroyed) return@launch
            setDirectoryLoading(false)
            result.onSuccess { entries ->
                if (commitNavigation) currentDirectory = requestedDirectory
                pathView.text = currentDirectory.absolutePath
                synchronized(DIRECTORY_CACHE) {
                    DIRECTORY_CACHE[cacheKey] = entries
                }
                allEntries = entries
                applySearch(search.text?.toString().orEmpty())
            }.onFailure { error ->
                if (cached == null || forceRefresh) {
                    currentDirectory = previousDirectory
                    pathView.text = previousDirectory.absolutePath
                    Toast.makeText(
                        this@PiperFileManagerActivity,
                        error.message ?: "Không thể đọc thư mục",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun directoryCacheKey(directory: File): String = buildString {
        append(directory.absolutePath)
        append('|').append(PiperPrivilegedPreferences.showHidden(this@PiperFileManagerActivity))
        append('|').append(shouldUsePrivilegedBackend(directory))
    }

    private fun invalidateDirectoryCache(directory: File) {
        val path = directory.absolutePath.trimEnd('/')
        synchronized(DIRECTORY_CACHE) {
            DIRECTORY_CACHE.keys.removeAll { key ->
                val cachedPath = key.substringBefore('|').trimEnd('/')
                cachedPath == path || cachedPath.startsWith("$path/") || path.startsWith("$cachedPath/")
            }
        }
    }

    private fun setDirectoryLoading(visible: Boolean, directory: File? = null) {
        loading.visibility = if (visible) View.VISIBLE else View.GONE
        empty.visibility = View.GONE
        if (visible && directory != null) {
            loadingText.text = "Đang đọc ${directory.absolutePath}..."
        }
    }

    private fun applySearch(query: String) {
        val filtered = if (query.isBlank()) allEntries else allEntries.filter {
            it.name.contains(query, ignoreCase = true)
        }
        visibleEntries = filtered
        adapter.submit(filtered)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleSelection(entry: ApkWorkspaceEntry) {
        if (entry.archivePath in selectedPaths) selectedPaths -= entry.archivePath
        else selectedPaths += entry.archivePath
        updateSelectionUi()
    }

    private fun clearSelection() {
        selectedPaths.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        selectionBar.visibility = if (selectedPaths.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.text = "${selectedPaths.size} đã chọn"
        adapter.updateSelection(selectedPaths)
    }

    private fun selectedFiles(): List<File> = selectedPaths.map(::File).filter(File::exists)

    private fun backupSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        val suggested = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PiperOS_Backups/${System.currentTimeMillis()}"
        )
        val (destinationRow, destination) = destinationField(suggested.absolutePath)
        PiperDialog.showCustom(
            context = this,
            title = "Chọn nơi sao lưu",
            message = "Nhập đường dẫn hoặc bấm Tìm để chọn thư mục đích.",
            icon = R.drawable.ic_file_backup,
            content = destinationRow,
            positiveLabel = "Sao lưu",
            onPositive = {
                val target = File(destination.text.toString().trim())
                if (!validateDestination(target)) false else {
                    startBatchFileOperation(FileOperationService.ACTION_BACKUP, files, target)
                    clearSelection()
                    true
                }
            }
        )
    }

    private fun showTransferDestination(action: String, sourceFiles: List<File> = selectedFiles()) {
        val files = sourceFiles
        if (files.isEmpty()) return
        val (destinationRow, destination) = destinationField(currentDirectory.absolutePath)
        val moving = action == FileOperationService.ACTION_MOVE
        PiperDialog.showCustom(
            context = this,
            title = if (moving) "Di chuyển đến" else "Sao chép đến",
            message = "Nhập đường dẫn hoặc bấm Tìm để chọn thư mục đích.",
            icon = if (moving) R.drawable.ic_file_move else R.drawable.ic_file_copy,
            content = destinationRow,
            positiveLabel = if (moving) "Di chuyển" else "Sao chép",
            onPositive = {
                val target = File(destination.text.toString().trim())
                if (!validateDestination(target)) false else {
                    startBatchFileOperation(action, files, target)
                    clearSelection()
                    true
                }
            }
        )
    }

    private fun destinationField(initialPath: String): Pair<View, EditText> {
        val input = EditText(this).apply {
            setText(initialPath)
            hint = "Đường dẫn thư mục đích"
            isSingleLine = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(com.google.android.material.button.MaterialButton(this@PiperFileManagerActivity).apply {
                text = "Tìm"
                isAllCaps = false
                cornerRadius = dp(8)
                setOnClickListener {
                    pendingDestinationInput = input
                    destinationPicker.launch(null)
                }
            }, LinearLayout.LayoutParams(dp(84), dp(52)).apply { marginStart = dp(8) })
        }
        return row to input
    }

    private fun validateDestination(target: File): Boolean {
        if (target.path.isBlank()) {
            Toast.makeText(this, "Hãy chọn đường dẫn đích", Toast.LENGTH_SHORT).show()
            return false
        }
        val ready = target.isDirectory || target.mkdirs()
        if (!ready || !target.canWrite()) {
            Toast.makeText(this, "Không thể ghi vào thư mục đích", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun resolveTreePath(uri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "")
        when {
            volume.equals("primary", true) -> File(
                Environment.getExternalStorageDirectory(),
                relative
            ).absolutePath
            volume.isNotBlank() -> File("/storage/$volume", relative).absolutePath
            else -> null
        }
    }.getOrNull()

    private fun confirmDeleteSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        PiperDialog.showConfirm(
            context = this,
            title = "Xóa ${files.size} mục đã chọn?",
            message = "Tệp và thư mục sẽ bị xóa khỏi thiết bị. Thao tác này không thể hoàn tác.",
            positiveLabel = "Xóa",
            destructive = true
        ) {
            startBatchFileOperation(
                FileOperationService.ACTION_DELETE,
                files,
                currentDirectory
            )
            clearSelection()
        }
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
        }?.filter { PiperPrivilegedPreferences.showHidden(this) || !it.name.startsWith('.') }
            ?.sortedWith(compareByDescending<ApkWorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

    private fun shouldUsePrivilegedBackend(directory: File): Boolean {
        if (directory.absolutePath == "/" && PiperPrivilegedPreferences.systemFiles(this)) return true
        val restricted = directory.absolutePath.startsWith("/storage/emulated/0/Android/data") ||
            directory.absolutePath.startsWith("/storage/emulated/0/Android/obb")
        return restricted && PiperPrivilegedPreferences.androidRestricted(this)
    }

    private fun loadSpecialIcon(entry: ApkWorkspaceEntry, target: ImageView) {
        val file = entry.extractedFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val icon = runCatching {
                when {
                    !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true) -> {
                        packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.applicationInfo?.let {
                            it.sourceDir = file.absolutePath
                            it.publicSourceDir = file.absolutePath
                            it.loadIcon(packageManager)
                        }
                    }
                    entry.isDirectory && file.parentFile?.name in setOf("data", "obb") &&
                        file.parentFile?.parentFile?.name.equals("Android", true) ->
                        packageManager.getApplicationIcon(file.name)
                    else -> null
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (icon != null && target.contentDescription == entry.archivePath && !isDestroyed) {
                    target.scaleType = ImageView.ScaleType.CENTER_INSIDE
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
            entry.isDirectory -> {
                navigateTo(file)
            }
            shouldUsePrivilegedBackend(currentDirectory) && !file.canRead() -> openPrivilegedFile(entry)
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
            else -> startActivity(
                Intent(this, TextEditorActivity::class.java)
                    .putExtra(TextEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
            )
        }
    }

    private fun openPrivilegedFile(entry: ApkWorkspaceEntry) {
        lifecycleScope.launch {
            val output = withContext(Dispatchers.IO) {
                privilegedClient.materializeReadOnly(
                    entry.archivePath,
                    File(cacheDir, "pps-preview/${System.nanoTime()}-${entry.name}")
                )
            }
            if (output == null) {
                Toast.makeText(this@PiperFileManagerActivity, "PPS không thể đọc tệp này", Toast.LENGTH_LONG).show()
                return@launch
            }
            when {
                ApkMediaTypes.isVisualMedia(output.name) -> startActivity(
                    Intent(this@PiperFileManagerActivity, PiperFilePreviewActivity::class.java)
                        .putExtra(PiperFilePreviewActivity.EXTRA_FILE_PATH, output.absolutePath)
                )
                isText(output) -> startActivity(Intent(this@PiperFileManagerActivity, TextEditorActivity::class.java).putExtra(TextEditorActivity.EXTRA_FILE_PATH, output.path))
                else -> openExternal(output)
            }
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
            PiperActionSheet.show(this, entry.name, listOf(
                PiperSheetAction("Mở", icon = R.drawable.ic_file_document) { openEntry(entry) },
                PiperSheetAction("Giải nén mục này", icon = R.drawable.ic_file_archive) { extractArchiveEntry(entry) }
            ))
            return
        }
        val file = File(entry.archivePath)
        val actions = buildList {
            add(PiperSheetAction("Mở", icon = R.drawable.ic_file_document) { openEntry(entry) })
            add(PiperSheetAction("Đổi tên", icon = R.drawable.ic_file_rename) { rename(file) })
            add(PiperSheetAction("Sao chép đến...", icon = R.drawable.ic_file_copy) {
                showTransferDestination(FileOperationService.ACTION_COPY, listOf(file))
            })
            add(PiperSheetAction("Di chuyển đến...", icon = R.drawable.ic_file_move) {
                showTransferDestination(FileOperationService.ACTION_MOVE, listOf(file))
            })
            add(PiperSheetAction("Nén / mã hóa", icon = R.drawable.ic_file_archive) { showCompressDialog(file) })
            if (FileArchiveFormat.detect(file.name) != null) {
                add(PiperSheetAction("Giải nén tại đây", icon = R.drawable.ic_file_archive) { showExtractDialog(file) })
            }
            add(PiperSheetAction("Xóa", icon = R.drawable.ic_file_delete) { confirmDelete(file) })
        }
        PiperActionSheet.show(this, file.name, actions)
    }

    private fun showManagerActions() {
        val archive = archiveFile
        val actions = if (archive != null) {
            listOf(
                PiperSheetAction("Giải nén toàn bộ", archive.name, R.drawable.ic_file_archive) {
                    showExtractDialog(archive)
                },
                PiperSheetAction("Đóng tệp nén", "Quay lại thư mục", R.drawable.ic_browser_close) {
                    archiveFile = null
                    render()
                }
            )
        } else {
            listOf(
                PiperSheetAction("Tạo thư mục", null, R.drawable.ic_browser_add, ::createFolder),
                PiperSheetAction("Tạo tệp văn bản", null, R.drawable.ic_file_document, ::createTextFile),
                PiperSheetAction("Sắp xếp theo tên", "A đến Z", R.drawable.ic_file_document) {
                    adapter.submit(allEntries.sortedBy { it.name.lowercase() })
                },
                PiperSheetAction("Sắp xếp theo kích thước", "Lớn nhất trước", R.drawable.ic_browser_download) {
                    adapter.submit(allEntries.sortedByDescending { it.size })
                },
                PiperSheetAction("Chọn nhiều", "Giữ một mục cũng có thể bắt đầu", R.drawable.check_circle) {
                    if (visibleEntries.isNotEmpty()) toggleSelection(visibleEntries.first())
                },
                PiperSheetAction("Truy cập chuyên sâu", "PPS · ROOT · Android/data · tệp ẩn", R.drawable.ic_terminal) {
                    startActivity(Intent(this, AdvancedAccessActivity::class.java))
                },
                PiperSheetAction("Hệ điều hành", "Duyệt / ở chế độ chỉ đọc", R.drawable.ic_file_document) {
                    if (!PiperPrivilegedPreferences.systemFiles(this)) {
                        Toast.makeText(this, "Hãy bật Tệp tin hệ điều hành trong Truy cập chuyên sâu", Toast.LENGTH_LONG).show()
                    } else {
                        navigateTo(File("/"))
                    }
                }
            )
        }
        PiperActionSheet.show(this, "Công cụ tệp", actions)
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "Tên thư mục" }
        PiperDialog.showCustom(
            context = this,
            title = "Tạo thư mục",
            content = input,
            positiveLabel = "Tạo",
            onPositive = {
                val target = File(currentDirectory, input.text.toString().trim())
                val success = target.name.isNotEmpty() && target.mkdir()
                if (success) render() else Toast.makeText(this, "Không thể tạo thư mục", Toast.LENGTH_SHORT).show()
                success
            }
        )
    }

    private fun createTextFile() {
        val input = EditText(this).apply { hint = "Tên tệp, ví dụ notes.txt" }
        PiperDialog.showCustom(
            context = this,
            title = "Tạo tệp mới",
            content = input,
            positiveLabel = "Tạo",
            onPositive = {
                val name = input.text.toString().trim()
                val target = File(currentDirectory, name)
                val success = name.isNotEmpty() && !target.exists() && runCatching {
                    target.parentFile?.mkdirs()
                    target.createNewFile()
                }.getOrDefault(false)
                if (success) {
                    render()
                    startActivity(
                        Intent(this, TextEditorActivity::class.java)
                            .putExtra(TextEditorActivity.EXTRA_FILE_PATH, target.absolutePath)
                    )
                } else Toast.makeText(this, "Không thể tạo tệp", Toast.LENGTH_SHORT).show()
                success
            }
        )
    }

    private fun rename(file: File) {
        val input = EditText(this).apply { setText(file.name); selectAll() }
        PiperDialog.showCustom(
            context = this,
            title = "Đổi tên",
            content = input,
            positiveLabel = "Lưu",
            onPositive = {
                val target = File(file.parentFile, input.text.toString().trim())
                val success = target.name.isNotEmpty() && file.renameTo(target)
                if (success) render() else Toast.makeText(this, "Không thể đổi tên", Toast.LENGTH_SHORT).show()
                success
            }
        )
    }

    private fun confirmDelete(file: File) {
        PiperDialog.showConfirm(
            context = this,
            title = "Xóa ${file.name}?",
            message = "Tệp hoặc thư mục sẽ bị xóa khỏi thiết bị. Thao tác này không thể hoàn tác.",
            positiveLabel = "Xóa",
            destructive = true
        ) {
            startBatchFileOperation(FileOperationService.ACTION_DELETE, listOf(file), file.parentFile ?: currentDirectory)
        }
    }

    private fun showCompressDialog(file: File) = showCompressDialog(listOf(file))

    private fun showCompressDialog(files: List<File>) {
        val file = files.firstOrNull() ?: return
        val formats = FileArchiveFormat.entries
        val presets = ArchiveCompressionPreset.entries
        val nameInput = EditText(this).apply {
            hint = "Tên archive"
            setText(file.nameWithoutExtension)
            selectAll()
        }
        val (destinationRow, destinationInput) = destinationField(
            (file.parentFile ?: currentDirectory).absolutePath
        )
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
            addView(TextView(this@PiperFileManagerActivity).apply {
                text = "Thư mục lưu"
                setTextColor(PiperModernUi.secondaryTextColor(this@PiperFileManagerActivity))
            })
            addView(destinationRow)
            addView(TextView(this@PiperFileManagerActivity).apply { text = "Định dạng"; setTextColor(0xBFFFFFFF.toInt()) })
            addView(formatSpinner)
            addView(TextView(this@PiperFileManagerActivity).apply { text = "Mức nén"; setTextColor(0xBFFFFFFF.toInt()) })
            addView(levelSpinner)
            addView(passwordEnabled)
            addView(passwordInput)
        }
        PiperDialog.showCustom(
            context = this,
            title = "Tạo tệp nén",
            message = "Chọn định dạng, mức nén và bảo vệ bằng mật khẩu nếu cần.",
            icon = R.drawable.ic_file_archive,
            content = panel,
            positiveLabel = "Bắt đầu",
            onPositive = {
                val format = formats[formatSpinner.selectedItemPosition]
                val password = passwordInput.text.toString().takeIf { passwordEnabled.isChecked && it.isNotEmpty() }
                if (passwordEnabled.isChecked && password == null) {
                    Toast.makeText(this, "Mật khẩu không được để trống", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    val baseName = nameInput.text.toString().trim().ifEmpty { file.nameWithoutExtension }
                    val destination = File(destinationInput.text.toString().trim())
                    if (!validateDestination(destination)) return@showCustom false
                    val output = uniqueOutput(File(destination, baseName + format.extension))
                    if (files.size == 1) {
                        startFileOperation(
                            FileOperationService.ACTION_COMPRESS,
                            file,
                            output,
                            format,
                            presets[levelSpinner.selectedItemPosition],
                            password
                        )
                    } else {
                        startBatchFileOperation(
                            FileOperationService.ACTION_COMPRESS,
                            files,
                            output,
                            format,
                            presets[levelSpinner.selectedItemPosition],
                            password
                        )
                        clearSelection()
                    }
                    true
                }
            }
        )
    }

    private fun showExtractDialog(archive: File) {
        val password = EditText(this).apply {
            hint = "Mật khẩu nếu archive có mã hóa"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        PiperDialog.showCustom(
            context = this,
            title = "Giải nén ${archive.name}",
            message = "Tác vụ tiếp tục khi tắt màn hình hoặc rời ứng dụng.",
            icon = R.drawable.ic_file_archive,
            content = password,
            positiveLabel = "Giải nén",
            onPositive = {
                val target = uniqueDirectory(File(archive.parentFile, archiveBaseName(archive.name)))
                startFileOperation(
                    FileOperationService.ACTION_EXTRACT,
                    archive,
                    target,
                    password = password.text.toString().takeIf(String::isNotEmpty)
                )
                archiveFile = null
                true
            }
        )
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
        showOperationDialog(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startBatchFileOperation(
        action: String,
        sources: List<File>,
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
            .putStringArrayListExtra(
                FileOperationService.EXTRA_SOURCES,
                ArrayList(sources.map(File::getAbsolutePath))
            )
            .putExtra(FileOperationService.EXTRA_TARGET, target.absolutePath)
            .putExtra(FileOperationService.EXTRA_PASSWORD, password)
        format?.let { intent.putExtra(FileOperationService.EXTRA_FORMAT, it.name) }
        preset?.let { intent.putExtra(FileOperationService.EXTRA_PRESET, it.name) }
        showOperationDialog(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun showOperationDialog(action: String) {
        operationDialog?.dismiss()
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(PiperModernUi.accentColor(this@PiperFileManagerActivity))
        }
        val percent = TextView(this).apply {
            text = "Đang chuẩn bị..."
            textSize = 18f
            setTextColor(PiperModernUi.textColor(this@PiperFileManagerActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val detail = TextView(this).apply {
            text = operationLabel(action)
            textSize = 12f
            setTextColor(PiperModernUi.secondaryTextColor(this@PiperFileManagerActivity))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(percent)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            })
            addView(detail)
        }
        operationProgress = progress
        operationPercent = percent
        operationDetail = detail
        operationDialog = PiperDialog.showCustom(
            context = this,
            title = operationLabel(action),
            message = "Bạn có thể để tác vụ chạy dưới nền hoặc hủy bất cứ lúc nào.",
            icon = operationIcon(action),
            content = panel,
            positiveLabel = "Chạy dưới nền",
            negativeLabel = "Hủy bỏ",
            onPositive = { true },
            onNegative = {
                startService(Intent(this, FileOperationService::class.java).setAction(FileOperationService.ACTION_CANCEL))
            }
        ).also { it.setCanceledOnTouchOutside(false) }
    }

    private fun updateOperationProgress(intent: Intent) {
        if (operationDialog == null && !isFinishing) {
            showOperationDialog(intent.getStringExtra(FileOperationService.EXTRA_ACTION).orEmpty())
        }
        val total = intent.getIntExtra(FileOperationService.EXTRA_TOTAL, 0)
        val completed = intent.getIntExtra(FileOperationService.EXTRA_COMPLETED, 0)
        val percent = intent.getIntExtra(FileOperationService.EXTRA_PERCENT, 0).coerceIn(0, 100)
        val current = intent.getStringExtra(FileOperationService.EXTRA_CURRENT_NAME).orEmpty()
        operationProgress?.isIndeterminate = total <= 0
        if (total > 0) operationProgress?.progress = percent
        operationPercent?.text = if (total > 0) "$percent%" else "Đang chuẩn bị..."
        operationDetail?.text = if (total > 0) "$completed/$total • $current" else current
    }

    private fun operationLabel(action: String): String = when (action) {
        FileOperationService.ACTION_COMPRESS -> "Đang nén tệp"
        FileOperationService.ACTION_EXTRACT -> "Đang giải nén"
        FileOperationService.ACTION_BACKUP -> "Đang sao lưu"
        FileOperationService.ACTION_DELETE -> "Đang xóa"
        FileOperationService.ACTION_COPY -> "Đang sao chép"
        FileOperationService.ACTION_MOVE -> "Đang di chuyển"
        else -> "Đang xử lý tệp"
    }

    private fun operationIcon(action: String): Int = when (action) {
        FileOperationService.ACTION_COMPRESS, FileOperationService.ACTION_EXTRACT -> R.drawable.ic_file_archive
        FileOperationService.ACTION_BACKUP -> R.drawable.ic_file_backup
        FileOperationService.ACTION_DELETE -> R.drawable.ic_file_delete
        FileOperationService.ACTION_MOVE -> R.drawable.ic_file_move
        else -> R.drawable.ic_file_copy
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
        private const val MAX_DIRECTORY_CACHE_ENTRIES = 96
        private val DIRECTORY_CACHE = object : LinkedHashMap<String, List<ApkWorkspaceEntry>>(48, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<ApkWorkspaceEntry>>?
            ): Boolean = size > MAX_DIRECTORY_CACHE_ENTRIES
        }
        private val TEXT_EXTENSIONS = setOf("txt", "xml", "json", "md", "html", "css", "js", "properties", "yml", "yaml", "log")
    }
}
