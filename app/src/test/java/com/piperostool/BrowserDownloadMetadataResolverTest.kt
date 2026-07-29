package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDownloadMetadataResolverTest {
    @Test
    fun apkMimeReplacesGenericEndpointExtension() {
        val result = BrowserDownloadMetadataResolver.resolve(
            BrowserDownloadMetadataResolver.Input(
                url = "https://example.test/download.php?id=7",
                responseMimeType = "application/vnd.android.package-archive"
            )
        )

        assertEquals("download.apk", result.fileName)
        assertEquals("application/vnd.android.package-archive", result.mimeType)
    }

    @Test
    fun utf8ContentDispositionHasHighestPriority() {
        val result = BrowserDownloadMetadataResolver.resolve(
            BrowserDownloadMetadataResolver.Input(
                url = "https://example.test/file",
                suggestedFileName = "fallback.bin",
                responseContentDisposition =
                    "attachment; filename*=UTF-8''PiperOS%20b%E1%BA%A3n%20m%E1%BB%9Bi.apk",
                responseMimeType = "application/vnd.android.package-archive"
            )
        )

        assertEquals("PiperOS bản mới.apk", result.fileName)
    }

    @Test
    fun queryFileNameWorksWithGenericMime() {
        val result = BrowserDownloadMetadataResolver.resolve(
            BrowserDownloadMetadataResolver.Input(
                url = "https://example.test/get?token=x&filename=archive%2E7z",
                declaredMimeType = "application/octet-stream"
            )
        )

        assertEquals("archive.7z", result.fileName)
        assertEquals("application/x-7z-compressed", result.mimeType)
    }

    @Test
    fun officeMimeAddsCorrectExtension() {
        val result = BrowserDownloadMetadataResolver.resolve(
            BrowserDownloadMetadataResolver.Input(
                url = "https://example.test/report",
                responseMimeType =
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )

        assertEquals("report.xlsx", result.fileName)
    }

    @Test
    fun existingSpecificExtensionIsPreserved() {
        val result = BrowserDownloadMetadataResolver.resolve(
            BrowserDownloadMetadataResolver.Input(
                url = "https://example.test/manual.pdf",
                declaredMimeType = "application/octet-stream"
            )
        )

        assertEquals("manual.pdf", result.fileName)
        assertEquals("application/pdf", result.mimeType)
    }

    @Test
    fun androidBundleExtensionIsPreserved() {
        val result = BrowserDownloadMetadataResolver.resolve(
            BrowserDownloadMetadataResolver.Input(
                url = "https://example.test/releases/PiperOS.xapk",
                responseMimeType = "application/zip"
            )
        )

        assertEquals("PiperOS.xapk", result.fileName)
        assertEquals("application/zip", result.mimeType)
    }

    @Test
    fun contentDispositionWithLanguageDecodesCorrectly() {
        val result = BrowserDownloadMetadataResolver.resolve(
            BrowserDownloadMetadataResolver.Input(
                url = "https://example.test/download",
                responseContentDisposition =
                    "attachment; filename*=UTF-8'en'PiperOS%20Terminal.deb",
                responseMimeType = "application/x-debian-package"
            )
        )

        assertEquals("PiperOS Terminal.deb", result.fileName)
    }
}
