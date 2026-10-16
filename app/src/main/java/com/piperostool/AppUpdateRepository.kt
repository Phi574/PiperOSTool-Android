package com.piperostool

import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private const val API = "https://api.github.com/repos/Phi574/PiperOSTool-Android/releases?per_page=100"
    private const val ATOM = "https://github.com/Phi574/PiperOSTool-Android/releases.atom"
    private const val RELEASE_PAGE_PREFIX = "https://github.com/Phi574/PiperOSTool-Android/releases/expanded_assets/"
    private const val ASSET_PREFIX = "https://github.com/Phi574/PiperOSTool-Android/releases/download/"
    private const val MAX_APK_BYTES = 500L * 1024 * 1024
    const val REQUIRED_BASELINE = "3.7.7"
    private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[._-](.+))?$", RegexOption.IGNORE_CASE)
    enum class Channel { STABLE, BETA, PREVIEW }

    internal data class Version(val major: Int, val minor: Int, val patch: Int, val stage: String) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            val numeric = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
            if (numeric != 0) return numeric
            fun rank(value: String): Int = when {
                value.isBlank() -> 5
                value.startsWith("rc") -> 4
                value.startsWith("beta") -> 3
                else -> 2
            }
            return rank(stage).compareTo(rank(other.stage)).takeIf { it != 0 }
                ?: stage.compareTo(other.stage, ignoreCase = true)
        }
    }

    fun version(value: String): Version? = versionPattern.matchEntire(value.trim())?.let { match ->
        Version(match.groupValues[1].toInt(), match.groupValues[2].toInt(),
            match.groupValues[3].toInt(), match.groupValues[4].lowercase())
    }

    fun channel(tag: String): Channel = when {
        version(tag)?.stage.orEmpty().contains("beta", ignoreCase = true) -> Channel.BETA
        version(tag)?.stage.orEmpty().isBlank() -> Channel.STABLE
        else -> Channel.PREVIEW
    }

    fun selectedChannel(preferences: SharedPreferences): Channel {
        if (!preferences.getBoolean("update_program_enabled", false)) return Channel.STABLE
        return runCatching {
        Channel.valueOf(preferences.getString("update_channel", Channel.STABLE.name) ?: Channel.STABLE.name)
        }.getOrDefault(Channel.STABLE)
    }

    fun isEligible(tag: String, selected: Channel): Boolean = when (selected) {
        Channel.STABLE -> channel(tag) == Channel.STABLE
        Channel.BETA -> channel(tag) != Channel.PREVIEW
        Channel.PREVIEW -> true
    }

    fun nextRelease(releases: List<AppRelease>, current: String, channel: Channel): AppRelease? {
        val local = version(current) ?: return null
        return releases.asSequence()
            .filter { isEligible(it.tag, channel) }
            .filter { candidate -> version(candidate.tag)?.let { it > local } == true }
            .minByOrNull { version(it.tag)!! }
    }

    fun requiredMigrationRelease(releases: List<AppRelease>, current: String): AppRelease? {
        val installed = version(current) ?: return null
        val baseline = version(REQUIRED_BASELINE) ?: return null
        if (installed >= baseline) return null
        return releases.firstOrNull { version(it.tag) == baseline }
    }

    fun needsRequiredMigration(current: String): Boolean =
        version(current)?.let { installed -> version(REQUIRED_BASELINE)?.let { installed < it } } == true

    fun laterReleases(releases: List<AppRelease>, current: String, channel: Channel): List<AppRelease> {
        val local = version(current) ?: return emptyList()
        return releases.filter { isEligible(it.tag, channel) && version(it.tag)?.let { v -> v > local } == true }
            .sortedBy { version(it.tag) }
    }

    internal fun apkFileName(release: AppRelease): String {
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val digest = release.assetDigest?.removePrefix("sha256:")
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.take(12) ?: release.assetSize.toString()
        return "PiperOS-Tool-$safeTag-$digest.apk"
    }

    suspend fun releases(): List<AppRelease> = withContext(Dispatchers.IO) {
        try {
            apiReleases()
        } catch (apiError: Exception) {
            runCatching { atomReleases() }.getOrElse { feedError ->
                throw IOException(
                    "GitHub API lỗi (${apiError.message}); nguồn dự phòng cũng lỗi (${feedError.message})",
                    feedError
                )
            }
        }
    }

    private fun apiReleases(): List<AppRelease> {
        val all = mutableListOf<AppRelease>()
        for (page in 1..10) {
            val connection = open("$API&page=$page")
            val json = try {
                if (connection.responseCode != 200) throw IOException("GitHub trả về HTTP ${connection.responseCode} khi kiểm tra bản cập nhật")
                connection.inputStream.bufferedReader().use { reader ->
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
            } finally { connection.disconnect() }
            if (json.length() == 0) break
            all += (0 until json.length()).mapNotNull { index ->
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
            }
            if (json.length() < 100) break
        }
        return all.distinctBy { it.tag.lowercase() }.sortedBy { version(it.tag) }
            .ifEmpty { throw IOException("GitHub chưa có bản phát hành hợp lệ") }
    }

    private suspend fun atomReleases(): List<AppRelease> {
        val atomConnection = open(ATOM).apply {
            setRequestProperty("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
        }
        val xml = try {
            if (atomConnection.responseCode != 200)
                throw IOException("GitHub Releases feed trả về HTTP ${atomConnection.responseCode}")
            atomConnection.inputStream.bufferedReader().use { it.readText().take(2_000_001) }
        } finally {
            atomConnection.disconnect()
        }
        if (xml.length > 2_000_000) throw IOException("Danh sách GitHub Releases quá lớn")
        val entries = parseAtomFeed(xml)
        if (entries.isEmpty()) throw IOException("GitHub Releases feed không có phiên bản hợp lệ")

        return coroutineScope {
            val semaphore = Semaphore(4)
            entries.map { item ->
                async(Dispatchers.IO) { semaphore.withPermit { runCatching { resolveReleaseAsset(item) }.getOrDefault(item) } }
            }.awaitAll().filter { it.assetUrl != null }
                .distinctBy { it.tag.lowercase() }
                .sortedBy { version(it.tag) }
                .ifEmpty { throw IOException("GitHub Releases feed không có APK để tải") }
        }
    }

    private fun resolveReleaseAsset(release: AppRelease): AppRelease {
        val connection = open(RELEASE_PAGE_PREFIX + release.tag).apply {
            setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
        }
        val html = try {
            if (connection.responseCode != 200) return release
            connection.inputStream.bufferedReader().use { it.readText().take(2_000_001) }
        } finally {
            connection.disconnect()
        }
        if (html.length > 2_000_000) return release
        val asset = parseExpandedAssets(release.tag, html) ?: return release
        return release.copy(assetUrl = asset.first, assetSize = 0L, assetDigest = asset.second)
    }

    internal fun parseAtomFeed(xml: String): List<AppRelease> =
        Regex("<entry\\b[^>]*>(.*?)</entry>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml).mapNotNull { match ->
                val entry = match.groupValues[1]
                val href = Regex("<link[^>]+rel=[\"']alternate[\"'][^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(entry)?.groupValues?.get(1).orEmpty()
                val tag = Regex("/releases/tag/([^/?&#\"']+)", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.let(::decodeXml).orEmpty()
                if (version(tag) == null) return@mapNotNull null
                fun field(name: String): String = Regex("<$name(?:\\s+[^>]*)?>(.*?)</$name>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(entry)?.groupValues?.get(1)?.let(::decodeXml).orEmpty()
                val htmlNotes = field("content")
                val notes = htmlNotes.replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), "\n")
                    .replace(Regex("<[^>]+>"), " ").replace(Regex("[ \\t]+"), " ")
                    .replace(Regex("\\n[ \\t]+"), "\n").trim()
                AppRelease(
                    tag = tag,
                    title = field("title").ifBlank { "PiperOS Tool $tag" },
                    description = notes.ifBlank { "Bản phát hành $tag" },
                    published = field("updated").take(10),
                    assetUrl = null,
                    assetSize = 0L,
                    assetDigest = null
                )
            }.toList()

    internal fun parseExpandedAssets(tag: String, html: String): Pair<String, String?>? {
        val path = Regex("href=[\"'](/Phi574/PiperOSTool-Android/releases/download/([^/]+)/([^\"']+\\.apk))[\"']",
            RegexOption.IGNORE_CASE).findAll(html).firstOrNull { match ->
            decodeXml(match.groupValues[2]).equals(tag, ignoreCase = true)
        } ?: return null
        val digest = Regex("sha256:([a-f0-9]{64})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        return ("https://github.com" + decodeXml(path.groupValues[1])) to digest?.let { "sha256:$it" }
    }

    private fun decodeXml(value: String): String = value
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

    suspend fun download(context: Context, release: AppRelease, onProgress: suspend (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = release.assetUrl ?: throw IOException("Bản phát hành chưa có tệp APK")
            if (!url.startsWith(ASSET_PREFIX)) throw IOException("Đường dẫn APK không thuộc kho phát hành chính thức")
            if (release.assetSize < 0 || release.assetSize > MAX_APK_BYTES) throw IOException("Dung lượng APK không hợp lệ")
            val directory = File(context.filesDir, "app-update").apply { mkdirs() }
            val complete = File(directory, apkFileName(release))
            val partial = File(directory, "${complete.name}.part")
            partial.delete()
            complete.delete()
            val connection = open(url)
            try {
                if (connection.responseCode != 200) throw IOException("Tải APK thất bại: HTTP ${connection.responseCode}")
                val expectedLength = connection.contentLengthLong
                val totalSize = release.assetSize.takeIf { it > 0 } ?: expectedLength
                if (totalSize <= 0 || totalSize > MAX_APK_BYTES || expectedLength > MAX_APK_BYTES ||
                    (expectedLength > 0 && release.assetSize > 0 && expectedLength != release.assetSize))
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
                                withContext(Dispatchers.Main) { onProgress(bytes, totalSize) }
                            }
                        }
                    }
                }
                if (bytes != totalSize) throw IOException("APK tải chưa đủ: $bytes/$totalSize byte")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = release.assetDigest?.removePrefix("sha256:")
                if (expected != null && !actual.equals(expected, ignoreCase = true))
                    throw IOException("Mã SHA-256 của APK không khớp với GitHub")
                if (!partial.renameTo(complete)) throw IOException("Không thể lưu APK đã tải")
                directory.listFiles()?.filter { candidate ->
                    candidate != complete && (candidate.name.endsWith(".apk") || candidate.name.endsWith(".apk.part"))
                }?.forEach { it.delete() }
                withContext(Dispatchers.Main) { onProgress(bytes, totalSize) }
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
        val directSignerMatch = currentSigners.isNotEmpty() && currentSigners == downloadedSigners
        val lineage = verifier.signingCertificateLineage
        val trustedRotation = currentSigners.size == 1 && downloadedSigners.size == 1 &&
            lineage != null &&
            lineage.certificatesInLineage.any { sha256(it.encoded) == currentSigners.single() } &&
            lineage.certificatesInLineage.lastOrNull()?.let { sha256(it.encoded) } == downloadedSigners.single()
        if (!directSignerMatch && !trustedRotation)
            throw IOException("Chữ ký không khớp với bản đang cài. Bản debug và bản ký khóa khác không thể cập nhật đè; hãy dùng APK cùng khóa ký.")
        if (archive.longVersionCode <= installed.longVersionCode)
            throw IOException("Mã phiên bản APK (${archive.longVersionCode}) không mới hơn bản đang cài (${installed.longVersionCode})")
        if (version(archive.versionName.orEmpty()) != version(release.tag))
            throw IOException("Phiên bản trong APK không khớp với bản phát hành ${release.tag}")
    }

    fun downloadedApk(context: Context, release: AppRelease): File =
        File(File(context.filesDir, "app-update"), apkFileName(release))

    fun deleteDownloadedApk(context: Context, release: AppRelease): Boolean {
        val file = downloadedApk(context, release)
        val partial = File(file.parentFile, "${file.name}.part")
        val partialDeleted = !partial.exists() || partial.delete()
        return (!file.exists() || file.delete()) && partialDeleted
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
