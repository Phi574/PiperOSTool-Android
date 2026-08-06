package com.piperostool

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.math.min

data class ApkBackupProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val copiedBytes: Long,
    val totalBytes: Long,
    val currentPath: String,
    val startedElapsed: Long
) {
    val percent: Int
        get() = when {
            totalBytes > 0L -> ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            totalFiles > 0 -> (completedFiles * 100 / totalFiles).coerceIn(0, 100)
            else -> 0
        }
    val elapsedMillis: Long get() = SystemClock.elapsedRealtime() - startedElapsed
}

data class ApkBackupResult(val fileCount: Int, val byteCount: Long, val elapsedMillis: Long)

class ApkBackupEngine(
    private val context: Context,
    private val workspace: ApkWorkspace,
    private val destination: DocumentFile
) {
    private data class Source(val archivePath: String, val localFile: File?, val size: Long)
    private data class Target(val source: Source, val uri: Uri)

    fun export(
        requestedPaths: Collection<String>,
        isCancelled: () -> Boolean = { false },
        onProgress: (ApkBackupProgress) -> Unit = {}
    ): ApkBackupResult {
        require(destination.isDirectory && destination.canWrite()) { "Thư mục đích không cho phép ghi" }
        val started = SystemClock.elapsedRealtime()
        val selected = removeNestedSelections(requestedPaths)
        require(selected.isNotEmpty()) { "Chưa chọn tệp hoặc thư mục" }

        val sources = linkedMapOf<String, Source>()
        val directorySelections = linkedSetOf<String>()
        ZipFile(workspace.sourceApk).use { zip ->
            val zipFiles = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            selected.forEach { path ->
                val local = File(workspace.filesDirectory, path)
                val exact = zip.getEntry(path)
                if (local.isFile || exact?.isDirectory == false) {
                    sources[path] = Source(path, local.takeIf(File::isFile), local.takeIf(File::isFile)?.length() ?: exact!!.size.coerceAtLeast(0L))
                } else {
                    val prefix = "$path/"
                    if (local.isDirectory || exact?.isDirectory == true || zipFiles.any { it.name.startsWith(prefix) }) {
                        directorySelections += path
                    }
                    zipFiles.asSequence().filter { it.name.startsWith(prefix) }.forEach { entry ->
                        sources[entry.name] = Source(entry.name, null, entry.size.coerceAtLeast(0L))
                    }
                    if (local.isDirectory) {
                        local.walkTopDown().filter(File::isFile).forEach { file ->
                            val archivePath = file.relativeTo(workspace.filesDirectory).invariantSeparatorsPath
                            sources[archivePath] = Source(archivePath, file, file.length())
                        }
                    }
                }
            }
        }

        val topFolders = directorySelections.associateWith { path ->
            createUniqueDirectory(destination, path.substringAfterLast('/'))
        }
        val directoryCache = mutableMapOf<String, DocumentFile>()
        val targets = sources.values.map { source ->
            check(!isCancelled()) { "Đã hủy backup" }
            val owner = selected.first { source.archivePath == it || source.archivePath.startsWith("$it/") }
            val ownerIsDirectory = owner in directorySelections
            val relative = if (ownerIsDirectory) source.archivePath.removePrefix("$owner/") else source.archivePath.substringAfterLast('/')
            val root = if (ownerIsDirectory) requireNotNull(topFolders[owner]) else destination
            Target(source, createTarget(root, owner, relative, directoryCache).uri)
        }

        if (targets.isEmpty()) {
            return ApkBackupResult(0, 0L, SystemClock.elapsedRealtime() - started)
        }
        val completed = AtomicInteger(0)
        val copiedBytes = AtomicLong(0L)
        val totalBytes = targets.sumOf { it.source.size }
        val workers = min(MAX_WORKERS, targets.size).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val groups = List(workers) { mutableListOf<Target>() }
            targets.forEachIndexed { index, target -> groups[index % workers] += target }
            val futures = groups.filter { it.isNotEmpty() }.map { group ->
                executor.submit<Unit> {
                    ZipFile(workspace.sourceApk).use { zip ->
                        group.forEach { target ->
                            check(!isCancelled()) { "Đã hủy backup" }
                            val input = target.source.localFile?.inputStream()
                                ?: zip.getInputStream(requireNotNull(zip.getEntry(target.source.archivePath)))
                            copy(input, target.uri, copiedBytes, isCancelled)
                            val done = completed.incrementAndGet()
                            onProgress(
                                ApkBackupProgress(done, targets.size, copiedBytes.get(), totalBytes, target.source.archivePath, started)
                            )
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        return ApkBackupResult(targets.size, copiedBytes.get(), SystemClock.elapsedRealtime() - started)
    }

    private fun copy(
        input: InputStream,
        target: Uri,
        copiedBytes: AtomicLong,
        isCancelled: () -> Boolean
    ) {
        input.use { source ->
            val output = context.contentResolver.openOutputStream(target, "w")
                ?: error("Không thể mở tệp đích")
            output.buffered(BUFFER_SIZE).use { sink ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    check(!isCancelled()) { "Đã hủy backup" }
                    val count = source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    copiedBytes.addAndGet(count.toLong())
                }
            }
        }
    }

    private fun createTarget(
        root: DocumentFile,
        owner: String,
        relativePath: String,
        cache: MutableMap<String, DocumentFile>
    ): DocumentFile {
        val parts = relativePath.split('/').filter(String::isNotBlank)
        var directory = root
        var key = owner
        parts.dropLast(1).forEach { name ->
            key += "/$name"
            directory = cache[key] ?: directory.findFile(name)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(name) ?: error("Không thể tạo thư mục $name")
            cache[key] = directory
        }
        val name = parts.lastOrNull() ?: relativePath.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        return createUniqueFile(directory, mime, name)
    }

    private fun createUniqueDirectory(parent: DocumentFile, requestedName: String): DocumentFile {
        val base = requestedName.ifBlank { "backup" }
        var candidate = base
        var suffix = 1
        while (parent.findFile(candidate) != null) candidate = "$base (${suffix++})"
        return parent.createDirectory(candidate) ?: error("Không thể tạo thư mục $candidate")
    }

    private fun createUniqueFile(parent: DocumentFile, mime: String, requestedName: String): DocumentFile {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var candidate = requestedName
        var suffix = 1
        while (parent.findFile(candidate) != null) candidate = "$base (${suffix++})$extension"
        val created = parent.createFile(mime, candidate) ?: error("Không thể tạo $candidate")
        if (created.name != candidate && !created.renameTo(candidate)) error("Không thể giữ đúng tên tệp $candidate")
        return created
    }

    private fun removeNestedSelections(paths: Collection<String>): List<String> {
        val normalized = paths.map { it.trim('/') }.filter(String::isNotEmpty).distinct().sortedBy(String::length)
        return normalized.filter { candidate ->
            normalized.none { parent -> parent.length < candidate.length && candidate.startsWith("$parent/") }
        }
    }

    companion object {
        private const val MAX_WORKERS = 4
        private const val BUFFER_SIZE = 128 * 1024
    }
}
