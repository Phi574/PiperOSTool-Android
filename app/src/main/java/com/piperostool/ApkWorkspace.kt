package com.piperostool

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
    val extractedFile: File?
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
    val outputDirectory = File(root, "output")

    fun list(prefix: String): List<ApkWorkspaceEntry> {
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
                children[first] = ApkWorkspaceEntry(
                    name = first,
                    archivePath = childPath,
                    isDirectory = isDirectory,
                    size = exact?.size ?: 0L,
                    extractedFile = File(filesDirectory, childPath).takeIf { it.isFile }
                )
            }
        }
        File(filesDirectory, normalized).listFiles()?.forEach { file ->
            children[file.name] = ApkWorkspaceEntry(
                name = file.name,
                archivePath = normalized + file.name,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                extractedFile = file
            )
        }
        return children.values.sortedWith(
            compareByDescending<ApkWorkspaceEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    fun extractEntry(path: String): File {
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

    fun decode(
        scope: ApkDecodeScope,
        onProgress: (ApkWorkspaceProgress) -> Unit
    ): Int {
        val started = SystemClock.elapsedRealtime()
        ZipFile(sourceApk).use { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { matches(scope, it.name) }
                .toList()
            entries.forEachIndexed { index, entry ->
                val output = safeOutput(entry.name)
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use(input::copyTo)
                }
                onProgress(
                    ApkWorkspaceProgress(
                        phase = "Đang giải nén ${entry.name}",
                        completed = index + 1,
                        total = entries.size,
                        startedElapsed = started
                    )
                )
            }
            return entries.size
        }
    }

    fun extractedTextFiles(): List<File> = filesDirectory.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in TEXT_EXTENSIONS }
        .toList()

    fun stringsFiles(): List<File> = filesDirectory.walkTopDown()
        .filter {
            it.isFile && it.name == "strings.xml" &&
                it.parentFile?.name?.startsWith("values") == true
        }
        .toList()

    private fun safeOutput(path: String): File {
        val output = File(filesDirectory, path)
        val rootPath = filesDirectory.canonicalPath + File.separator
        check(output.canonicalPath.startsWith(rootPath)) { "Đường dẫn ZIP không an toàn" }
        return output
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
