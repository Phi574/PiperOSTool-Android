package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleAuthMigrationCodecTest {
    @Test
    fun migrationQrRoundTripsMultipleTotpEntriesAndBatchMetadata() {
        val entries = listOf(
            GoogleAuthMigrationCodec.Entry("alice@example.com", "Example", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 6, 2),
            GoogleAuthMigrationCodec.Entry("backup", "", "JBSWY3DPEHPK3PXP", "SHA256", 8, 2)
        )

        val decoded = GoogleAuthMigrationCodec.decode(
            GoogleAuthMigrationCodec.encode(entries, batchSize = 3, batchIndex = 1, batchId = 42)
        )

        assertEquals(entries, decoded.entries)
        assertEquals(3, decoded.batchSize)
        assertEquals(1, decoded.batchIndex)
        assertEquals(42L, decoded.batchId)
    }
}
