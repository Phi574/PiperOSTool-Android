package com.piperostool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPackageAnalyzerTest {
    @Test
    fun detectsKnownAdLicenseAndBillingMarkersCaseInsensitively() {
        val result = AppPackageAnalyzer.analyzeDexText(
            "com/google/android/gms/ads",
            "LicenseChecker ServerManagedPolicy",
            "com/android/billingclient/BillingClient"
        )

        assertTrue(result.googleAds)
        assertTrue(result.licenseVerification)
        assertTrue(result.inAppPurchases)
        assertTrue(result.hasDetectedComponents)
    }

    @Test
    fun reportsNoMarkersForUnrelatedDexText() {
        val result = AppPackageAnalyzer.analyzeDexText("com/example/plain/application")

        assertFalse(result.googleAds)
        assertFalse(result.licenseVerification)
        assertFalse(result.inAppPurchases)
        assertFalse(result.hasDetectedComponents)
    }
}
