package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test fun releaseOrderingIncludesPreviewBuilds() {
        val older = AppUpdateRepository.version("3.4.3")!!
        val preview = AppUpdateRepository.version("v3.4.5.PRE")!!
        val stable = AppUpdateRepository.version("3.4.5")!!
        assertTrue(preview > older)
        assertTrue(stable > preview)
        assertEquals(preview, AppUpdateRepository.version("3.4.5.pre"))
    }

    @Test fun eachReleaseGetsADistinctInstallerUriPath() {
        fun release(tag: String) = AppRelease(tag, tag, "", "", null, 95_000_000,
            "sha256:${"a".repeat(64)}")
        val oldName = AppUpdateRepository.apkFileName(release("v3.5.1.PRE"))
        val newName = AppUpdateRepository.apkFileName(release("v3.5.2.PRE"))
        assertNotEquals(oldName, newName)
        assertTrue(newName.endsWith(".apk"))
        assertTrue(newName.contains("3.5.2.PRE"))
    }
}
