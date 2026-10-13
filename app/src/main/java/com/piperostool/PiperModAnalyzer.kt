package com.piperostool

import java.io.File
import java.util.zip.ZipFile

data class PiperModScanResult(
    val googleAds: Boolean,
    val licenseVerification: Boolean,
    val inAppPurchases: Boolean,
    val customPatchAvailable: Boolean = false
) {
    val hasSupportedPatchTargets: Boolean
        get() = googleAds || licenseVerification || inAppPurchases || customPatchAvailable
}

/** Read-only indicator scan. This does not modify or bypass an app. */
object PiperModAnalyzer {
    private val adMarkers = listOf(
        "com/google/android/gms/ads", "com.google.android.gms.ads", "com/google/ads/",
        "com/facebook/ads", "com/unity3d/ads", "com/applovin", "com/startapp"
    )
    private val licenseMarkers = listOf(
        "com/android/vending/licensing", "com.android.vending.licensing",
        "licensechecker", "licensevalidator", "servermanagedpolicy"
    )
    private val billingMarkers = listOf(
        "com/android/billingclient", "com.android.billingclient", "BillingClient",
        "com/android/vending/billing", "com.android.vending.billing", "inappbillingservice"
    )

    fun analyze(apk: File): PiperModScanResult {
        val matches = mutableSetOf<String>()
        var scanned = 0L
        runCatching {
            ZipFile(apk).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && DEX_NAME.matches(it.name) }
                for (entry in dexEntries) {
                    if (scanned >= MAX_SCAN_BYTES) break
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        val tail = ByteArray(MAX_MARKER_BYTES)
                        var tailSize = 0
                        while (scanned < MAX_SCAN_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_SCAN_BYTES - scanned).toInt())
                            if (count <= 0) break
                            scanned += count
                            val window = ByteArray(tailSize + count)
                            tail.copyInto(window, endIndex = tailSize)
                            buffer.copyInto(window, destinationOffset = tailSize, endIndex = count)
                            val text = window.toString(Charsets.ISO_8859_1).lowercase()
                            allMarkers.forEach { marker -> if (text.contains(marker)) matches += marker }
                            tailSize = minOf(tail.size, window.size)
                            window.copyInto(tail, destinationOffset = 0, startIndex = window.size - tailSize)
                        }
                    }
                }
            }
        }
        val normalized = matches
        return PiperModScanResult(
            googleAds = normalized.any { it in adMarkers },
            licenseVerification = normalized.any { it in licenseMarkers },
            inAppPurchases = normalized.any { it in billingMarkers }
        )
    }

    internal fun analyzeDexText(vararg dexTexts: String): PiperModScanResult {
        val text = dexTexts.joinToString("\n").lowercase()
        return PiperModScanResult(
            googleAds = adMarkers.any(text::contains),
            licenseVerification = licenseMarkers.any(text::contains),
            inAppPurchases = billingMarkers.any(text::contains)
        )
    }

    private val allMarkers = adMarkers + licenseMarkers + billingMarkers
    private val DEX_NAME = Regex("classes(?:\\d+)?\\.dex")
    private const val BUFFER_SIZE = 32 * 1024
    private const val MAX_SCAN_BYTES = 96L * 1024L * 1024L
    private val MAX_MARKER_BYTES = allMarkers.maxOf { it.length }
}
