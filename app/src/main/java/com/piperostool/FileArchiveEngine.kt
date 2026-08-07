package com.piperostool

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class FileArchiveFormat(val label: String, val extension: String) {
    ZIP("ZIP", ".zip"),
    SEVEN_Z("7Z", ".7z"),
    TAR("TAR", ".tar"),
    TAR_GZIP("TAR.GZ", ".tar.gz"),
    TAR_BZIP2("TAR.BZ2", ".tar.bz2"),
    TAR_XZ("TAR.XZ", ".tar.xz"),
    TAR_LZ4("TAR.LZ4", ".tar.lz4"),
    TAR_ZSTD("TAR.ZSTD", ".tar.zstd");

    companion object {
        fun detect(name: String): FileArchiveFormat? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZIP
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> TAR_BZIP2
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> TAR_XZ
                lower.endsWith(".tar.lz4") -> TAR_LZ4
                lower.endsWith(".tar.zstd") || lower.endsWith(".tar.zst") -> TAR_ZSTD
                lower.endsWith(".tar") -> TAR
                lower.endsWith(".7z") -> SEVEN_Z
                lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".xapk") || lower.endsWith(".apks") -> ZIP
                else -> null
            }
        }
    }
}

enum class ArchiveCompressionPreset(val label: String, val numericLevel: Int) {
    FASTEST("Nhanh nhất", 1),
    FAST("Nhanh", 3),
    NORMAL("Bình thường", 5),
    MAXIMUM("Tối đa", 7),
    ULTRA("Siêu nén", 9),
    SUPER("Super nén", 9)
}

data class FileOperationProgress(val completed: Int, val total: Int, val currentName: String) {
    val percent: Int? get() = if (total <= 0) null else (completed * 100 / total).coerceIn(0, 100)
}

object FileArchiveEngine {
    fun compress(
        source: File,
        output: File,
        format: FileArchiveFormat,
        preset: ArchiveCompressionPreset,
        password: String?,
        cancelled: () -> Boolean,
        progress: (FileOperationProgress) -> Unit
    ) {
        require(source.exists()) { "Nguồn không tồn tại" }
        output.parentFile?.mkdirs()
        if (format == FileArchiveFormat.ZIP) {
            compressZip(source, output, preset, password, cancelled, progress)
        } else if (format == FileArchiveFormat.SEVEN_Z) {
            require(password.isNullOrEmpty()) { "Tạo 7Z mã hóa chưa được thư viện hỗ trợ; hãy chọn ZIP AES-256" }
            compressSevenZ(source, output, cancelled, progress)
        } else {
            require(password.isNullOrEmpty()) { "Mật khẩu AES-256 chỉ áp dụng cho ZIP" }
            compressTar(source, output, format, preset, cancelled, progress)
        }
    }

    fun extract(
        archive: File,
        target: File,
        password: String?,
        cancelled: () -> Boolean,
        progress: (FileOperationProgress) -> Unit
    ) {
        target.mkdirs()
        when (FileArchiveFormat.detect(archive.name) ?: error("Định dạng archive chưa hỗ trợ")) {
            FileArchiveFormat.ZIP -> extractZip(archive, target, password, cancelled, progress)
            FileArchiveFormat.SEVEN_Z -> extractSevenZ(archive, target, password, cancelled, progress)
            else -> extractTar(archive, target, cancelled, progress)
        }
    }

    private fun compressZip(source: File, output: File, preset: ArchiveCompressionPreset, password: String?, cancelled: () -> Boolean, progress: (FileOperationProgress) -> Unit) {
        val files = source.walkTopDown().filter { it.isFile }.toList().ifEmpty { listOf(source) }
        val zip = ZipFile(output, password?.takeIf { it.isNotEmpty() }?.toCharArray())
        val parameters = ZipParameters().apply {
            compressionLevel = when (preset) {
                ArchiveCompressionPreset.FASTEST -> CompressionLevel.FASTEST
                ArchiveCompressionPreset.FAST -> CompressionLevel.FAST
                ArchiveCompressionPreset.NORMAL -> CompressionLevel.NORMAL
                ArchiveCompressionPreset.MAXIMUM -> CompressionLevel.MAXIMUM
                else -> CompressionLevel.ULTRA
            }
            if (!password.isNullOrEmpty()) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        val base = source.parentFile ?: source
        files.forEachIndexed { index, file ->
            check(!cancelled()) { "Đã hủy" }
            parameters.fileNameInZip = file.relativeTo(base).invariantSeparatorsPath
            zip.addFile(file, parameters)
            progress(FileOperationProgress(index + 1, files.size, file.name))
        }
    }

    private fun compressSevenZ(source: File, output: File, cancelled: () -> Boolean, progress: (FileOperationProgress) -> Unit) {
        val files = source.walkTopDown().filter { it.isFile }.toList()
        val base = source.parentFile ?: source
        SevenZOutputFile(output).use { archive ->
            files.forEachIndexed { index, file ->
                check(!cancelled()) { "Đã hủy" }
                val entry = archive.createArchiveEntry(file, file.relativeTo(base).invariantSeparatorsPath)
                archive.putArchiveEntry(entry)
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        archive.write(buffer, 0, count)
                    }
                }
                archive.closeArchiveEntry()
                progress(FileOperationProgress(index + 1, files.size, file.name))
            }
        }
    }

    private fun compressTar(source: File, output: File, format: FileArchiveFormat, preset: ArchiveCompressionPreset, cancelled: () -> Boolean, progress: (FileOperationProgress) -> Unit) {
        val files = source.walkTopDown().filter { it.isFile }.toList()
        val base = source.parentFile ?: source
        FileOutputStream(output).buffered(BUFFER_SIZE).use { raw ->
            compressorOutput(raw, format, preset).use { compressed ->
                TarArchiveOutputStream(compressed).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    files.forEachIndexed { index, file ->
                        check(!cancelled()) { "Đã hủy" }
                        val entry = TarArchiveEntry(file, file.relativeTo(base).invariantSeparatorsPath)
                        tar.putArchiveEntry(entry)
                        file.inputStream().buffered().use { it.copyTo(tar, BUFFER_SIZE) }
                        tar.closeArchiveEntry()
                        progress(FileOperationProgress(index + 1, files.size, file.name))
                    }
                    tar.finish()
                }
            }
        }
    }

    private fun extractZip(archive: File, target: File, password: String?, cancelled: () -> Boolean, progress: (FileOperationProgress) -> Unit) {
        val zip = ZipFile(archive, password?.toCharArray())
        val headers = zip.fileHeaders.filterNot { it.isDirectory }
        headers.forEachIndexed { index, header ->
            check(!cancelled()) { "Đã hủy" }
            safeOutput(target, header.fileName)
            zip.extractFile(header, target.absolutePath)
            progress(FileOperationProgress(index + 1, headers.size, header.fileName))
        }
    }

    private fun extractSevenZ(archive: File, target: File, password: String?, cancelled: () -> Boolean, progress: (FileOperationProgress) -> Unit) {
        val seven = if (password.isNullOrEmpty()) SevenZFile(archive) else SevenZFile(archive, password.toCharArray())
        seven.use { input ->
            var completed = 0
            while (true) {
                val entry = input.nextEntry ?: break
                check(!cancelled()) { "Đã hủy" }
                val output = safeOutput(target, entry.name)
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var remaining = entry.size
                        while (remaining > 0L) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    completed++
                    progress(FileOperationProgress(completed, 0, entry.name))
                }
            }
        }
    }

    private fun extractTar(archive: File, target: File, cancelled: () -> Boolean, progress: (FileOperationProgress) -> Unit) {
        FileInputStream(archive).buffered(BUFFER_SIZE).use { raw ->
            compressorInput(raw, FileArchiveFormat.detect(archive.name)!!).use { decompressed ->
                TarArchiveInputStream(decompressed).use { tar ->
                    var completed = 0
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        check(!cancelled()) { "Đã hủy" }
                        val output = safeOutput(target, entry.name)
                        if (entry.isDirectory) output.mkdirs() else {
                            output.parentFile?.mkdirs()
                            output.outputStream().buffered().use { tar.copyTo(it, BUFFER_SIZE) }
                            completed++
                            progress(FileOperationProgress(completed, 0, entry.name))
                        }
                    }
                }
            }
        }
    }

    private fun compressorOutput(output: OutputStream, format: FileArchiveFormat, preset: ArchiveCompressionPreset): OutputStream = when (format) {
        FileArchiveFormat.TAR -> output
        FileArchiveFormat.TAR_GZIP -> GzipCompressorOutputStream(output)
        FileArchiveFormat.TAR_BZIP2 -> BZip2CompressorOutputStream(output, preset.numericLevel.coerceIn(1, 9))
        FileArchiveFormat.TAR_XZ -> XZCompressorOutputStream(output, preset.numericLevel.coerceIn(0, 9))
        FileArchiveFormat.TAR_LZ4 -> FramedLZ4CompressorOutputStream(output)
        FileArchiveFormat.TAR_ZSTD -> ZstdCompressorOutputStream(output, preset.numericLevel.coerceIn(1, 9))
        else -> error("Không phải TAR")
    }

    private fun compressorInput(input: InputStream, format: FileArchiveFormat): InputStream = when (format) {
        FileArchiveFormat.TAR -> input
        FileArchiveFormat.TAR_GZIP -> GzipCompressorInputStream(input)
        FileArchiveFormat.TAR_BZIP2 -> BZip2CompressorInputStream(input)
        FileArchiveFormat.TAR_XZ -> XZCompressorInputStream(input)
        FileArchiveFormat.TAR_LZ4 -> FramedLZ4CompressorInputStream(input)
        FileArchiveFormat.TAR_ZSTD -> ZstdCompressorInputStream(input)
        else -> error("Không phải TAR")
    }

    private fun safeOutput(root: File, name: String): File {
        val output = File(root, name)
        val rootPath = root.canonicalPath + File.separator
        check(output.canonicalPath.startsWith(rootPath)) { "Archive chứa đường dẫn không an toàn" }
        return output
    }

    private const val BUFFER_SIZE = 128 * 1024
}
