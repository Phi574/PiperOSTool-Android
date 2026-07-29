package com.piperostool

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object BrowserDownloadMetadataResolver {
    data class Input(
        val url: String,
        val contentDisposition: String? = null,
        val declaredMimeType: String? = null,
        val suggestedFileName: String? = null,
        val responseUrl: String? = null,
        val responseContentDisposition: String? = null,
        val responseMimeType: String? = null
    )

    data class Result(
        val fileName: String,
        val mimeType: String?
    )

    fun resolve(input: Input): Result {
        val dispositionName = sequenceOf(
            input.responseContentDisposition,
            input.contentDisposition
        ).mapNotNull(::fileNameFromContentDisposition).firstOrNull()

        val candidate = sequenceOf(
            dispositionName,
            input.suggestedFileName,
            fileNameFromQuery(input.responseUrl),
            fileNameFromQuery(input.url),
            fileNameFromUrl(input.responseUrl),
            fileNameFromUrl(input.url)
        ).mapNotNull(::cleanCandidate).firstOrNull() ?: DEFAULT_FILE_NAME

        val declaredMime = sequenceOf(input.responseMimeType, input.declaredMimeType)
            .mapNotNull(::normalizeMimeType)
            .firstOrNull()
        val currentExtension = extensionOf(candidate)
        val mimeType = declaredMime ?: mimeTypeForExtension(currentExtension)
        val expectedExtension = extensionForMimeType(mimeType)

        val fileName = when {
            expectedExtension == null -> candidate
            currentExtension.isBlank() -> "$candidate.$expectedExtension"
            currentExtension in REPLACEABLE_EXTENSIONS ->
                "${candidate.substringBeforeLast('.')}.$expectedExtension"
            else -> candidate
        }

        return Result(
            fileName = fileName.take(MAX_FILE_NAME_LENGTH).trimEnd('.', ' ')
                .ifBlank { DEFAULT_FILE_NAME },
            mimeType = mimeType ?: mimeTypeForExtension(extensionOf(fileName))
        )
    }

    internal fun fileNameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null

        FILENAME_STAR.find(header)?.groupValues?.getOrNull(1)?.let { encoded ->
            val rawValue = encoded.trim().trim('"')
            val firstSeparator = rawValue.indexOf('\'')
            val secondSeparator = if (firstSeparator >= 0) {
                rawValue.indexOf('\'', firstSeparator + 1)
            } else {
                -1
            }
            val value = if (secondSeparator >= 0) {
                rawValue.substring(secondSeparator + 1)
            } else {
                rawValue
            }
            decode(value)?.let { return it }
        }
        return FILENAME.find(header)?.groupValues?.let { groups ->
            groups.getOrNull(1)?.takeIf(String::isNotBlank)
                ?: groups.getOrNull(2)?.trim()
        }
    }

    private fun fileNameFromQuery(url: String?): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        query.split('&').forEach { item ->
            val key = item.substringBefore('=').lowercase()
            if (key in FILE_NAME_QUERY_KEYS) {
                decode(item.substringAfter('=', ""))?.let { value ->
                    cleanCandidate(value)?.let { return it }
                }
            }
        }
        return null
    }

    private fun fileNameFromUrl(url: String?): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        return decode(path.substringAfterLast('/'))
    }

    private fun cleanCandidate(value: String?): String? {
        val decoded = decode(value)?.trim()?.takeIf(String::isNotBlank) ?: return null
        return decoded
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(INVALID_FILE_NAME_CHARS, "_")
            .trim()
            .trim('.')
            .takeIf(String::isNotBlank)
    }

    private fun decode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun normalizeMimeType(value: String?): String? {
        val mimeType = value?.substringBefore(';')?.trim()?.lowercase()
        return mimeType?.takeUnless { it.isBlank() || it in GENERIC_MIME_TYPES }
    }

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    private fun mimeTypeForExtension(extension: String): String? =
        EXTENSION_TO_MIME[extension]

    private fun extensionForMimeType(mimeType: String?): String? =
        mimeType?.let(MIME_TO_EXTENSION::get)

    private val MIME_TO_EXTENSION = mapOf(
        "application/vnd.android.package-archive" to "apk",
        "application/x-android-package" to "apk",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/x-rar-compressed" to "rar",
        "application/vnd.rar" to "rar",
        "application/x-7z-compressed" to "7z",
        "application/x-tar" to "tar",
        "application/gzip" to "gz",
        "application/x-gzip" to "gz",
        "application/x-bzip2" to "bz2",
        "application/x-xz" to "xz",
        "application/x-debian-package" to "deb",
        "application/x-rpm" to "rpm",
        "application/java-archive" to "jar",
        "application/x-java-archive" to "jar",
        "application/x-msdownload" to "exe",
        "application/x-msi" to "msi",
        "application/x-iso9660-image" to "iso",
        "application/x-apple-diskimage" to "dmg",
        "application/x-bittorrent" to "torrent",
        "application/wasm" to "wasm",
        "application/vnd.sqlite3" to "sqlite",
        "application/pdf" to "pdf",
        "application/epub+zip" to "epub",
        "application/json" to "json",
        "application/xml" to "xml",
        "text/plain" to "txt",
        "text/html" to "html",
        "text/css" to "css",
        "text/csv" to "csv",
        "application/javascript" to "js",
        "text/javascript" to "js",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/avif" to "avif",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/ogg" to "ogg",
        "audio/flac" to "flac",
        "audio/aac" to "aac",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "audio/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/quicktime" to "mov",
        "video/x-msvideo" to "avi",
        "video/mpeg" to "mpeg",
        "font/ttf" to "ttf",
        "font/otf" to "otf",
        "font/woff" to "woff",
        "font/woff2" to "woff2"
    )
    private val EXTENSION_TO_MIME = MIME_TO_EXTENSION.entries
        .associate { (mime, extension) -> extension to mime }
        .plus(
            mapOf(
                "htm" to "text/html",
                "jpeg" to "image/jpeg",
                "m4v" to "video/mp4",
                "oga" to "audio/ogg",
                "yaml" to "application/yaml",
                "yml" to "application/yaml",
                "xapk" to "application/zip",
                "apkm" to "application/zip",
                "apks" to "application/zip",
                "db" to "application/vnd.sqlite3",
                "sqlite3" to "application/vnd.sqlite3"
            )
        )

    private val GENERIC_MIME_TYPES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/download",
        "application/x-download",
        "application/force-download"
    )
    private val REPLACEABLE_EXTENSIONS = setOf(
        "bin", "dat", "download", "tmp", "php", "asp", "aspx", "jsp", "cgi", "do", "action"
    )
    private val FILE_NAME_QUERY_KEYS = setOf(
        "filename", "file_name", "file", "download", "name", "title"
    )
    private val FILENAME_STAR = Regex(
        """(?i)filename\*\s*=\s*([^;]+)"""
    )
    private val FILENAME = Regex(
        """(?i)filename\s*=\s*(?:"([^"]+)"|([^;]+))"""
    )
    private val INVALID_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private const val DEFAULT_FILE_NAME = "PiperOS-download"
    private const val MAX_FILE_NAME_LENGTH = 180
}
