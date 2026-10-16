package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test fun releaseOrderingIncludesPreviewBuilds() {
        val older = AppUpdateRepository.version("3.4.3")!!
        val preview = AppUpdateRepository.version("v3.4.5.PRE")!!
        val beta = AppUpdateRepository.version("v3.4.5.beta")!!
        val stable = AppUpdateRepository.version("3.4.5")!!
        assertTrue(preview > older)
        assertTrue(beta > preview)
        assertTrue(stable > preview)
        assertTrue(stable > beta)
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

    @Test fun atomFeedFallbackExtractsReleaseTagsAndNotes() {
        val feed = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <link rel="alternate" type="text/html" href="https://github.com/Phi574/PiperOSTool-Android/releases/tag/v3.8.0.beta"/>
                <title>PiperOS Tool 3.8.0.beta</title>
                <updated>2026-10-16T08:36:19Z</updated>
                <content type="html">&lt;p&gt;Sửa lỗi cập nhật &amp;amp; cải tiến.&lt;/p&gt;</content>
              </entry>
              <entry>
                <link rel="alternate" type="text/html" href="https://github.com/Phi574/PiperOSTool-Android/releases/tag/unrelated"/>
                <title>Unrelated</title>
              </entry>
            </feed>
        """.trimIndent()

        val releases = AppUpdateRepository.parseAtomFeed(feed)

        assertEquals(1, releases.size)
        assertEquals("v3.8.0.beta", releases.single().tag)
        assertEquals("PiperOS Tool 3.8.0.beta", releases.single().title)
        assertEquals("2026-10-16", releases.single().published)
        assertTrue(releases.single().description.contains("Sửa lỗi cập nhật"))
    }

    @Test fun expandedAssetsFallbackUsesReleaseScopedApkAndDigest() {
        val digest = "b".repeat(64)
        val html = """
            <a href="/Phi574/PiperOSTool-Android/releases/download/v3.8.0/PiperOS-Tool-3.8.0.apk">APK</a>
            <clipboard-copy value="sha256:$digest"></clipboard-copy>
        """.trimIndent()

        val asset = AppUpdateRepository.parseExpandedAssets("v3.8.0", html)

        assertEquals("https://github.com/Phi574/PiperOSTool-Android/releases/download/v3.8.0/PiperOS-Tool-3.8.0.apk", asset?.first)
        assertEquals("sha256:$digest", asset?.second)
        assertEquals(null, AppUpdateRepository.parseExpandedAssets("v3.7.9", html))
    }

    @Test fun updateChannelsOnlyExposeTheirAllowedReleaseTypes() {
        assertTrue(AppUpdateRepository.isEligible("3.7.5", AppUpdateRepository.Channel.STABLE))
        assertTrue(AppUpdateRepository.isEligible("3.7.5.beta", AppUpdateRepository.Channel.BETA))
        assertTrue(!AppUpdateRepository.isEligible("3.7.5.PRE", AppUpdateRepository.Channel.BETA))
        assertTrue(AppUpdateRepository.isEligible("3.8.0", AppUpdateRepository.Channel.BETA))
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

    @Test fun requiredSigningBaselineCannotBeSkippedInTheUpdatedClient() {
        fun release(tag: String) = AppRelease(tag, tag, "", "", "https://github.com/Phi574/PiperOSTool-Android/releases/download/$tag/app.apk", 100, null)
        val releases = listOf(release("3.7.7"), release("3.7.8"))
        assertTrue(AppUpdateRepository.needsRequiredMigration("3.7.6"))
        assertEquals("3.7.7", AppUpdateRepository.requiredMigrationRelease(releases, "3.7.6")?.tag)
        assertEquals(null, AppUpdateRepository.requiredMigrationRelease(releases, "3.7.7"))
    }
}
