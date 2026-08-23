package com.piperostool

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class ApkWorkspaceProgress(
    val phase: String,
    val completed: Int,
    val total: Int,
    val startedElapsed: Long
) {
    val percent: Int
        get() = if (total <= 0) 0 else (completed * 100 / total).coerceIn(0, 100)
    val elapsedMillis: Long
        get() = SystemClock.elapsedRealtime() - startedElapsed
}

data class ApkWorkspaceEntry(
    val name: String,
    val archivePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val extractedFile: File?,
    val childCount: Int = 0,
    val modified: Boolean = false
)

enum class ApkDecodeScope(val label: String) {
    MANIFEST("Manifest"),
    RESOURCES("Tài nguyên / assets"),
    DEX("Mã DEX"),
    NATIVE("Thư viện native"),
    ALL("Tất cả tệp")
}

class ApkWorkspace private constructor(
    val root: File,
    val sourceApk: File
) {
    val filesDirectory = File(root, "files")
    val decodedDirectory = File(root, "decoded")
    val outputDirectory = File(root, "output")
    private val previewDirectory = File(root, "previews")
    private val decodedModeFile = File(root, "decoded-mode.txt")
    val hasDecodedProject: Boolean
        get() = File(decodedDirectory, "AndroidManifest.xml").isFile
    val hasDecodedSmali: Boolean
        get() = decodedModeFile.takeIf { it.isFile }?.readText()?.trim() == "full"

    fun list(prefix: String): List<ApkWorkspaceEntry> {
        if (hasDecodedProject) return listDecoded(prefix)
        val normalized = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val children = linkedMapOf<String, ApkWorkspaceEntry>()
        ZipFile(sourceApk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (!entry.name.startsWith(normalized) || entry.name == normalized) return@forEach
                val remainder = entry.name.removePrefix(normalized)
                val first = remainder.substringBefore('/')
                if (first.isEmpty()) return@forEach
                val childPath = normalized + first
                val isDirectory = remainder.contains('/') || entry.isDirectory
                val exact = if (isDirectory) null else entry
                val existing = children[first]
                val extractedFile = File(filesDirectory, childPath).takeIf { it.exists() }
                children[first] = ApkWorkspaceEntry(
                    name = first,
                    archivePath = childPath,
                    isDirectory = isDirectory,
                    size = exact?.size ?: 0L,
                    extractedFile = extractedFile,
                    childCount = if (isDirectory) (existing?.childCount ?: 0) + 1 else 0,
                    modified = extractedFile?.isFile == true && EditorHistoryStore.isApkModified(extractedFile)
                )
            }
        }
        File(filesDirectory, normalized).listFiles()?.forEach { file ->
            children[file.name] = ApkWorkspaceEntry(
                name = file.name,
                archivePath = normalized + file.name,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                extractedFile = file,
                childCount = if (file.isDirectory) file.list()?.size ?: 0 else 0,
                modified = file.isFile && EditorHistoryStore.isApkModified(file)
            )
        }
        return children.values.sortedWith(
            compareByDescending<ApkWorkspaceEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    fun extractEntry(path: String): File {
        if (hasDecodedProject) {
            val decoded = safeDecoded(path)
            check(decoded.isFile) { "$path không phải là tệp trong project" }
            return decoded
        }
        val output = safeOutput(path)
        if (output.isFile) return output
        ZipFile(sourceApk).use { zip ->
            val entry = zip.getEntry(path) ?: error("Không tìm thấy $path trong APK")
            check(!entry.isDirectory) { "$path là thư mục" }
            output.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                FileOutputStream(output).use(input::copyTo)
            }
        }
        return output
    }

    fun previewFile(path: String): File {
        if (hasDecodedProject) {
            val decoded = safeDecoded(path)
            check(decoded.isFile) { "$path không phải là tệp trong project" }
            return decoded
        }
        val edited = File(filesDirectory, path)
        if (edited.isFile) return edited
        val output = File(previewDirectory, path)
        val rootPath = previewDirectory.canonicalPath + File.separator
        check(output.canonicalPath.startsWith(rootPath)) { "Đường dẫn preview không an toàn" }
        if (output.isFile) return output
        output.parentFile?.mkdirs()
        ZipFile(sourceApk).use { zip ->
            val entry = zip.getEntry(path) ?: error("Không tìm thấy $path trong APK")
            check(!entry.isDirectory) { "$path là thư mục" }
            zip.getInputStream(entry).use { input -> FileOutputStream(output).use(input::copyTo) }
        }
        return output
    }

    fun exportSelection(
        paths: Collection<String>,
        destination: DocumentFile,
        context: Context,
        onProgress: (ApkWorkspaceProgress) -> Unit
    ): Int {
        require(destination.isDirectory && destination.canWrite()) {
            "Thư mục đích không cho phép ghi"
        }
        val normalized = paths.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct()
        require(normalized.isNotEmpty()) { "Chưa chọn tệp hoặc thư mục" }
        if (hasDecodedProject) {
            return exportDecodedSelection(normalized, destination, context, onProgress)
        }
        val started = SystemClock.elapsedRealtime()
        val exportedFiles = linkedMapOf<String, File>()
        val directorySelections = linkedSetOf<String>()

        ZipFile(sourceApk).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            normalized.forEach { selected ->
                val selectedEntry = zip.getEntry(selected)
                if (selectedEntry != null && !selectedEntry.isDirectory) {
                    exportedFiles[selected] = extractEntry(selected)
                } else {
                    val prefix = "$selected/"
                    if (
                        selectedEntry?.isDirectory == true ||
                        entries.any { it.name.startsWith(prefix) } ||
                        File(filesDirectory, selected).isDirectory
                    ) {
                        directorySelections += selected
                    }
                    entries.filter { it.name.startsWith(prefix) }.forEach { entry ->
                        exportedFiles[entry.name] = extractEntry(entry.name)
                    }
                    val localRoot = File(filesDirectory, selected)
                    if (localRoot.isDirectory) {
                        localRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                            exportedFiles[file.relativeTo(filesDirectory).invariantSeparatorsPath] = file
                        }
                    }
                }
            }
        }

        normalized.forEach { selected ->
            val local = File(filesDirectory, selected)
            if (local.isFile) exportedFiles[selected] = local
        }

        val topFolders = mutableMapOf<String, DocumentFile>()
        directorySelections.forEach { selected ->
            topFolders[selected] = createUniqueDirectory(
                destination,
                selected.substringAfterLast('/')
            )
        }
        exportedFiles.entries.forEachIndexed { index, (archivePath, source) ->
            val owner = normalized.firstOrNull {
                archivePath == it || archivePath.startsWith("$it/")
            } ?: archivePath
            val ownerIsDirectory = owner in directorySelections
            val relative = if (ownerIsDirectory) archivePath.removePrefix("$owner/") else source.name
            val rootDocument = if (ownerIsDirectory) {
                topFolders.getOrPut(owner) {
                    createUniqueDirectory(destination, owner.substringAfterLast('/'))
                }
            } else {
                destination
            }
            writeDocumentFile(context, rootDocument, relative, source)
            onProgress(
                ApkWorkspaceProgress(
                    phase = "Đang backup $archivePath",
                    completed = index + 1,
                    total = exportedFiles.size,
                    startedElapsed = started
                )
            )
        }
        return exportedFiles.size
    }

    fun decode(
        scope: ApkDecodeScope,
        onProgress: (ApkWorkspaceProgress) -> Unit
    ): Int {
        val started = SystemClock.elapsedRealtime()
        ZipFile(sourceApk).use { zip ->
            val total = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { matches(scope, it.name) }
                .count()
            var completed = 0
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { matches(scope, it.name) }
                .forEach { entry ->
                val output = safeOutput(entry.name)
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use(input::copyTo)
                }
                completed++
                onProgress(
                    ApkWorkspaceProgress(
                        phase = "Đang giải nén ${entry.name}",
                        completed = completed,
                        total = total,
                        startedElapsed = started
                    )
                )
            }
            return completed
        }
    }

    fun decodeFullProject(
        decodeSmali: Boolean,
        onProgress: (ApkWorkspaceProgress) -> Unit
    ) {
        val started = SystemClock.elapsedRealtime()
        onProgress(ApkWorkspaceProgress("Đang giải mã Manifest và resources", 0, 0, started))
        ReAndroidApkEngine.decode(sourceApk, decodedDirectory, decodeSmali)
        decodedModeFile.writeText(if (decodeSmali) "full" else "resources")
        val phase = if (decodeSmali) {
            "Project XML và smali đã sẵn sàng"
        } else {
            "Project resources đã sẵn sàng"
        }
        onProgress(ApkWorkspaceProgress(phase, 100, 100, started))
    }

    fun extractedTextFiles(): List<File> = activeFilesDirectory().walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in TEXT_EXTENSIONS }
        .toList()

    fun stringsFiles(): List<File> {
        val resources = File(activeFilesDirectory(), "res")
        return resources.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .map { File(it, "strings.xml") }
            .filter(File::isFile)
            .sortedBy { it.parentFile?.name.orEmpty() }
            .toList()
    }

    private fun activeFilesDirectory(): File = if (hasDecodedProject) decodedDirectory else filesDirectory

    private fun listDecoded(prefix: String): List<ApkWorkspaceEntry> {
        val normalized = prefix.trim('/')
        val directory = if (normalized.isEmpty()) decodedDirectory else safeDecoded(normalized)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().map { file ->
            val path = file.relativeTo(decodedDirectory).invariantSeparatorsPath
            ApkWorkspaceEntry(
                name = file.name,
                archivePath = path,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                extractedFile = file,
                childCount = if (file.isDirectory) file.list()?.size ?: 0 else 0,
                modified = file.isFile && EditorHistoryStore.isApkModified(file)
            )
        }.sortedWith(
            compareByDescending<ApkWorkspaceEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun exportDecodedSelection(
        paths: List<String>,
        destination: DocumentFile,
        context: Context,
        onProgress: (ApkWorkspaceProgress) -> Unit
    ): Int {
        val started = SystemClock.elapsedRealtime()
        val files = linkedMapOf<String, File>()
        val directories = linkedSetOf<String>()
        paths.forEach { path ->
            val source = safeDecoded(path)
            when {
                source.isFile -> files[path] = source
                source.isDirectory -> {
                    directories += path
                    source.walkTopDown().filter { it.isFile }.forEach { file ->
                        files[file.relativeTo(decodedDirectory).invariantSeparatorsPath] = file
                    }
                }
            }
        }
        val topFolders = directories.associateWith {
            createUniqueDirectory(destination, it.substringAfterLast('/'))
        }
        files.entries.forEachIndexed { index, (path, source) ->
            val owner = directories.firstOrNull { path == it || path.startsWith("$it/") }
            val rootDocument = owner?.let(topFolders::get) ?: destination
            val relative = owner?.let { path.removePrefix("$it/") } ?: source.name
            writeDocumentFile(context, requireNotNull(rootDocument), relative, source)
            onProgress(ApkWorkspaceProgress("Đang backup $path", index + 1, files.size, started))
        }
        return files.size
    }

    private fun safeDecoded(path: String): File {
        val output = File(decodedDirectory, path)
        val rootPath = decodedDirectory.canonicalPath + File.separator
        check(
            output.canonicalPath == decodedDirectory.canonicalPath ||
                output.canonicalPath.startsWith(rootPath)
        ) { "Đường dẫn project không an toàn" }
        return output
    }

    private fun safeOutput(path: String): File {
        val output = File(filesDirectory, path)
        val rootPath = filesDirectory.canonicalPath + File.separator
        check(output.canonicalPath.startsWith(rootPath)) { "Đường dẫn ZIP không an toàn" }
        return output
    }

    private fun writeDocumentFile(
        context: Context,
        root: DocumentFile,
        relativePath: String,
        source: File
    ) {
        val parts = relativePath.split('/').filter { it.isNotBlank() }
        var directory = root
        parts.dropLast(1).forEach { name ->
            directory = directory.findFile(name)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(name)
                ?: error("Không thể tạo thư mục $name")
        }
        val name = parts.lastOrNull() ?: source.name
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream"
        val target = createUniqueFile(directory, mime, name)
        context.contentResolver.openOutputStream(target.uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Không thể ghi $name")
    }

    private fun createUniqueDirectory(parent: DocumentFile, requestedName: String): DocumentFile {
        var candidate = requestedName.ifBlank { "backup" }
        var suffix = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$requestedName ($suffix)"
            suffix++
        }
        return parent.createDirectory(candidate) ?: error("Không thể tạo thư mục $candidate")
    }

    private fun createUniqueFile(
        parent: DocumentFile,
        mime: String,
        requestedName: String
    ): DocumentFile {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var candidate = requestedName
        var suffix = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$base ($suffix)$extension"
            suffix++
        }
        val created = parent.createFile(mime, candidate) ?: error("Không thể tạo $candidate")
        if (created.name != candidate && !created.renameTo(candidate)) {
            error("Không thể giữ đúng tên tệp $candidate")
        }
        return created
    }

    private fun matches(scope: ApkDecodeScope, name: String): Boolean = when (scope) {
        ApkDecodeScope.MANIFEST -> name == "AndroidManifest.xml"
        ApkDecodeScope.RESOURCES ->
            name == "resources.arsc" || name.startsWith("res/") || name.startsWith("assets/")
        ApkDecodeScope.DEX -> name.matches(Regex("classes(\\d*)?\\.dex"))
        ApkDecodeScope.NATIVE -> name.startsWith("lib/")
        ApkDecodeScope.ALL -> true
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "xml", "json", "txt", "html", "htm", "css", "js", "md", "properties", "yml", "yaml"
        )

        fun createFromPath(context: Context, source: File): ApkWorkspace {
            require(source.isFile) { "APK nguồn không tồn tại" }
            return create(context, source.nameWithoutExtension) { output ->
                source.inputStream().use { input -> output.outputStream().use(input::copyTo) }
            }
        }

        fun createFromUri(context: Context, uri: Uri): ApkWorkspace {
            return create(context, "imported") { output ->
                val input = requireNotNull(context.contentResolver.openInputStream(uri))
                input.use { source -> output.outputStream().use(source::copyTo) }
            }
        }

        private fun create(
            context: Context,
            label: String,
            copySource: (File) -> Unit
        ): ApkWorkspace {
            val base = File(context.getExternalFilesDir(null), "APKEditor")
            base.mkdirs()
            val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val root = File(base, "${System.currentTimeMillis()}-$safeLabel").apply { mkdirs() }
            val source = File(root, "source.apk")
            copySource(source)
            check(ZipFile(source).use { it.getEntry("AndroidManifest.xml") != null }) {
                "Tệp đã chọn không phải APK hợp lệ"
            }
            return ApkWorkspace(root, source).also {
                it.filesDirectory.mkdirs()
                it.outputDirectory.mkdirs()
            }
        }

        fun restore(rootPath: String): ApkWorkspace? {
            val root = File(rootPath)
            val source = File(root, "source.apk")
            return if (root.isDirectory && source.isFile) ApkWorkspace(root, source) else null
        }
    }
}
