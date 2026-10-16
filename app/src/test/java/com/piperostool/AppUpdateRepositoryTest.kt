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

    @Test fun updateChannelsOnlyExposeTheirAllowedReleaseTypes() {
        assertTrue(AppUpdateRepository.isEligible("3.7.5", AppUpdateRepository.Channel.STABLE))
        assertTrue(AppUpdateRepository.isEligible("3.7.5.beta", AppUpdateRepository.Channel.BETA))
        assertTrue(!AppUpdateRepository.isEligible("3.7.5.PRE", AppUpdateRepository.Channel.BETA))
        assertTrue(AppUpdateRepository.isEligible("3.7.5.PRE", AppUpdateRepository.Channel.PREVIEW))
    }

    @Test fun defaultUpdateSelectsTheNextAvailableVersionAndListsLaterChoices() {
        fun release(tag: String) = AppRelease(tag, tag, "", "", "https://github.com/Phi574/PiperOSTool-Android/releases/download/$tag/app.apk", 100, null)
        val all = listOf(release("3.5.1.PRE"), release("3.5.2.PRE"), release("3.7.4.PRE"), release("3.7.5"))
        val next = AppUpdateRepository.nextRelease(all, "3.5.1.PRE", AppUpdateRepository.Channel.PREVIEW)
        assertEquals("3.5.2.PRE", next?.tag)
        assertEquals(listOf("3.5.2.PRE", "3.7.4.PRE", "3.7.5"),
            AppUpdateRepository.laterReleases(all, "3.5.1.PRE", AppUpdateRepository.Channel.PREVIEW).map { it.tag })
    }
}
