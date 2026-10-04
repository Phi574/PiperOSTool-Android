package com.piperostool

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppJunkScannerInstrumentedTest {
    @Test fun onlyOldCacheIsOfferedAndChangedFilesArePreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()
        val folder = File(context.cacheDir, "junk-scanner-test").apply { mkdirs() }
        val old = File(folder, "old.tmp").apply {
            writeText("old")
            setLastModified(now - 2L * 24 * 60 * 60 * 1000)
        }
        val recent = File(folder, "recent.tmp").apply { writeText("recent") }
        val userData = File(context.filesDir, "junk-scanner-user-data.txt").apply { writeText("keep") }
        try {
            val found = AppJunkScanner.scan(context, now)
            val item = found.first { it.file == old }
            assertFalse(found.any { it.file == recent || it.file == userData })
            old.writeText("updated")
            assertFalse(AppJunkScanner.delete(context, item))
            assertTrue(old.exists())
            old.setLastModified(now - 2L * 24 * 60 * 60 * 1000)
            val refreshed = AppJunkScanner.scan(context, now).first { it.file == old }
            assertTrue(AppJunkScanner.delete(context, refreshed))
            assertFalse(old.exists())
        } finally {
            old.delete()
            recent.delete()
            folder.delete()
            userData.delete()
        }
    }
}
