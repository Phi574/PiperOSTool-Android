package com.piperostool

import android.content.Context
import android.content.pm.PackageManager
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal data class AppRelease(
    val tag: String,
    val title: String,
    val description: String,
    val published: String,
    val assetUrl: String?,
    val assetSize: Long,
    val assetDigest: String?
)

internal object AppUpdateRepository {
    private const val API = "https://api.github.com/repos/Phi574/PiperOSTool-Android/releases?per_page=30"
    private const val ASSET_PREFIX = "https://github.com/Phi574/PiperOSTool-Android/releases/download/"
    private const val MAX_APK_BYTES = 500L * 1024 * 1024
    private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[._-](.+))?$", RegexOption.IGNORE_CASE)

    internal data class Version(val major: Int, val minor: Int, val patch: Int, val stage: String) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            val numeric = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
            if (numeric != 0) return numeric
            fun rank(value: String): Int = when {
                value.isBlank() -> 4
                value.startsWith("rc") -> 3
                value.startsWith("beta") -> 2
                else -> 1
            }
            return rank(stage).compareTo(rank(other.stage)).takeIf { it != 0 }
                ?: stage.compareTo(other.stage, ignoreCase = true)
        }
    }

    fun version(value: String): Version? = versionPattern.matchEntire(value.trim())?.let { match ->
        Version(match.groupValues[1].toInt(), match.groupValues[2].toInt(),
            match.groupValues[3].toInt(), match.groupValues[4].lowercase())
    }

    internal fun apkFileName(release: AppRelease): String {
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val digest = release.assetDigest?.removePrefix("sha256:")
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.take(12) ?: release.assetSize.toString()
        return "PiperOS-Tool-$safeTag-$digest.apk"
    }

    suspend fun newestRelease(): AppRelease = withContext(Dispatchers.IO) {
        val connection = open(API)
        try {
            if (connection.responseCode != 200) throw IOException("GitHub trả về HTTP ${connection.responseCode} khi kiểm tra bản cập nhật")
            val json = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(8192)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(chars)
                    if (count < 0) break
                    if (text.length + count > 2_000_000) throw IOException("Danh sách phiên bản quá lớn")
                    text.append(chars, 0, count)
                }
                JSONArray(text.toString())
            }
            (0 until json.length()).mapNotNull { index ->
                val item = json.getJSONObject(index)
                val tag = item.optString("tag_name")
                if (item.optBoolean("draft") || version(tag) == null) return@mapNotNull null
                val assets = item.optJSONArray("assets") ?: JSONArray()
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { candidate ->
                        candidate.optString("name").endsWith(".apk", ignoreCase = true) &&
                            candidate.optString("browser_download_url").startsWith(ASSET_PREFIX)
                    }
                AppRelease(tag, item.optString("name").ifBlank { tag },
                    item.optString("body").ifBlank { "Bản phát hành $tag" },
                    item.optString("published_at").take(10),
                    asset?.optString("browser_download_url"), asset?.optLong("size") ?: 0L,
                    asset?.optString("digest")?.takeIf { it.startsWith("sha256:") })
            }.maxWithOrNull(compareBy { version(it.tag) })
                ?: throw IOException("GitHub chưa có bản phát hành hợp lệ")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(context: Context, release: AppRelease, onProgress: suspend (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = release.assetUrl ?: throw IOException("Bản phát hành chưa có tệp APK")
            if (!url.startsWith(ASSET_PREFIX)) throw IOException("Đường dẫn APK không thuộc kho phát hành chính thức")
            if (release.assetSize <= 0 || release.assetSize > MAX_APK_BYTES) throw IOException("Dung lượng APK không hợp lệ")
            val directory = File(context.cacheDir, "app-update").apply { mkdirs() }
            val complete = File(directory, apkFileName(release))
            val partial = File(directory, "${complete.name}.part")
            partial.delete()
            complete.delete()
            val connection = open(url)
            try {
                if (connection.responseCode != 200) throw IOException("Tải APK thất bại: HTTP ${connection.responseCode}")
                val expectedLength = connection.contentLengthLong
                if (expectedLength > MAX_APK_BYTES || (expectedLength > 0 && expectedLength != release.assetSize))
                    throw IOException("Dung lượng tải xuống khác thông tin bản phát hành")
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var lastReport = 0L
                connection.inputStream.use { source ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > MAX_APK_BYTES) throw IOException("APK vượt quá giới hạn dung lượng")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (bytes - lastReport >= 512 * 1024) {
                                lastReport = bytes
                                withContext(Dispatchers.Main) { onProgress(bytes, release.assetSize) }
                            }
                        }
                    }
                }
                if (bytes != release.assetSize) throw IOException("APK tải chưa đủ: $bytes/${release.assetSize} byte")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = release.assetDigest?.removePrefix("sha256:")
                if (expected != null && !actual.equals(expected, ignoreCase = true))
                    throw IOException("Mã SHA-256 của APK không khớp với GitHub")
                if (!partial.renameTo(complete)) throw IOException("Không thể lưu APK đã tải")
                directory.listFiles()?.filter { candidate ->
                    candidate != complete && (candidate.name.endsWith(".apk") || candidate.name.endsWith(".apk.part"))
                }?.forEach { it.delete() }
                withContext(Dispatchers.Main) { onProgress(bytes, release.assetSize) }
                complete
            } catch (error: Exception) {
                partial.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }

    fun verifyDownloadedApk(context: Context, release: AppRelease, file: File) {
        val verifier = ApkVerifier.Builder(file).build().verify()
        if (!verifier.isVerified || verifier.signerCertificates.isEmpty())
            throw IOException("APK bị sửa đổi hoặc chữ ký APK không hợp lệ")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("Tệp tải xuống không phải APK hợp lệ")
        if (archive.packageName != context.packageName)
            throw IOException("APK thuộc ứng dụng khác: ${archive.packageName}")
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val currentSigners = installed.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val downloadedSigners = verifier.signerCertificates.map { sha256(it.encoded) }.toSet()
        if (currentSigners.isEmpty() || currentSigners != downloadedSigners)
            throw IOException("Chữ ký không khớp với bản đang cài. Bản debug và bản ký khóa khác không thể cập nhật đè; hãy dùng APK cùng khóa ký.")
        if (archive.longVersionCode <= installed.longVersionCode)
            throw IOException("Mã phiên bản APK (${archive.longVersionCode}) không mới hơn bản đang cài (${installed.longVersionCode})")
        if (version(archive.versionName.orEmpty()) != version(release.tag))
            throw IOException("Phiên bản trong APK không khớp với bản phát hành ${release.tag}")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun open(value: String): HttpURLConnection = (URL(value).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 25_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "PiperOS-Tool-Updater")
    }
}
