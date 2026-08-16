package com.piperostool

import android.content.Context
import android.os.SystemClock
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PiperApkBuilder(private val context: Context) {
    fun build(
        workspace: ApkWorkspace,
        onProgress: (ApkWorkspaceProgress) -> Unit
    ): File {
        val started = SystemClock.elapsedRealtime()
        val unsigned = File(workspace.outputDirectory, "unsigned.apk")
        val signed = File(
            workspace.outputDirectory,
            "${workspace.root.name.substringAfter('-')}-piper-edited.apk"
        )
        if (workspace.hasDecodedProject) {
            onProgress(ApkWorkspaceProgress("Đang biên dịch resources và Manifest", 10, 100, started))
            ReAndroidApkEngine.build(workspace.decodedDirectory, unsigned)
            onProgress(ApkWorkspaceProgress("Đang ký APK", 92, 100, started))
            sign(unsigned, signed)
            unsigned.delete()
            onProgress(ApkWorkspaceProgress("Hoàn tất", 100, 100, started))
            return signed
        }
        val replacements = workspace.filesDirectory.walkTopDown()
            .filter { it.isFile }
            .associateBy { it.relativeTo(workspace.filesDirectory).invariantSeparatorsPath }

        ZipFile(workspace.sourceApk).use { source ->
            val entries = source.entries().asSequence()
                .filterNot { it.isDirectory || isOldSignature(it.name) }
                .toList()
            val total = entries.size + replacements.keys.count { source.getEntry(it) == null }
            val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(unsigned)))
            ZipOutputStream(counter).use { output ->
                var completed = 0
                entries.forEach { original ->
                    val replacement = replacements[original.name]
                    val bytes = replacement?.readBytes()
                        ?: source.getInputStream(original).use { it.readBytes() }
                    writeEntry(output, counter, original.name, bytes, original.method, original.time)
                    completed++
                    onProgress(
                        ApkWorkspaceProgress(
                            "Đang đóng gói ${original.name}", completed, total, started
                        )
                    )
                }
                replacements.forEach { (name, file) ->
                    if (source.getEntry(name) != null) return@forEach
                    writeEntry(output, counter, name, file.readBytes(), ZipEntry.DEFLATED, file.lastModified())
                    completed++
                    onProgress(ApkWorkspaceProgress("Đang thêm $name", completed, total, started))
                }
            }
        }

        onProgress(ApkWorkspaceProgress("Đang ký APK", 99, 100, started))
        sign(unsigned, signed)
        unsigned.delete()
        onProgress(ApkWorkspaceProgress("Hoàn tất", 100, 100, started))
        return signed
    }

    private fun sign(input: File, output: File) {
        val password = SIGNING_PASSWORD.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        context.resources.openRawResource(R.raw.piper_editor_signing).use {
            keyStore.load(it, password)
        }
        val privateKey = keyStore.getKey(SIGNING_ALIAS, password) as PrivateKey
        val certificate = keyStore.getCertificate(SIGNING_ALIAS) as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder(
            "PiperOS Editor",
            privateKey,
            listOf(certificate)
        ).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
    }

    private fun writeEntry(
        output: ZipOutputStream,
        counter: CountingOutputStream,
        name: String,
        bytes: ByteArray,
        originalMethod: Int,
        time: Long
    ) {
        val stored = originalMethod == ZipEntry.STORED || name.endsWith(".so") || name == "resources.arsc"
        val entry = ZipEntry(name).apply {
            this.time = time.coerceAtLeast(0L)
            method = if (stored) ZipEntry.STORED else ZipEntry.DEFLATED
            if (stored) {
                val crc = CRC32().apply { update(bytes) }
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
                val alignment = if (name.endsWith(".so")) 4096 else 4
                extra = alignmentExtra(counter.count, name.toByteArray(Charsets.UTF_8).size, alignment)
            }
        }
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private fun alignmentExtra(offset: Long, nameLength: Int, alignment: Int): ByteArray? {
        val base = offset + 30 + nameLength
        if (base % alignment == 0L) return null
        var totalExtra = ((alignment - (base % alignment)) % alignment).toInt()
        if (totalExtra in 1..3) totalExtra += alignment
        val payload = totalExtra - 4
        return ByteArray(totalExtra).apply {
            this[0] = 0x35
            this[1] = 0xD9.toByte()
            this[2] = (payload and 0xFF).toByte()
            this[3] = ((payload ushr 8) and 0xFF).toByte()
        }
    }

    private fun isOldSignature(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val upper = name.uppercase()
        return upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
            upper.endsWith(".EC") || upper.endsWith(".SF") ||
            upper.endsWith("MANIFEST.MF")
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            count += length
        }
    }

    companion object {
        private const val SIGNING_ALIAS = "piper_editor"
        private const val SIGNING_PASSWORD = "piperos"
    }
}
