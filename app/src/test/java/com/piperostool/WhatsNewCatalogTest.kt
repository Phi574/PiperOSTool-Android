package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewCatalogTest {
    @Test fun headingSuffixDoesNotHideCurrentOfficialRelease() {
        val releases = WhatsNewCatalog.parse("""
            # Changelog
            ## 3.7.9 — Bản chính thức
            - Bản ổn định.
            ## 3.7.8.PRE
            - Bản xem trước.
        """.trimIndent())

        assertEquals(listOf("3.7.9", "3.7.8.PRE"), releases.map { it.version })
        assertEquals("3.7.9", WhatsNewCatalog.currentRelease(releases, "3.7.9")?.version)
        assertNotNull(WhatsNewCatalog.currentRelease(releases, "v3.7.8.PRE"))
    }

    @Test fun listIsSortedNewestFirstAndKeepsAllChannelItems() {
        val releases = WhatsNewCatalog.parse("""
            ## 3.7.6 — Bản chính thức
            - Stable.
            ## 3.8.0.PRE
            - Preview.
            ## 3.7.9.beta
            - Beta.
            ## 3.7.9
            - Stable newer than beta.
        """.trimIndent())

        assertEquals(listOf("3.8.0.PRE", "3.7.9", "3.7.9.beta", "3.7.6"), releases.map { it.version })
        assertEquals(AppUpdateRepository.Channel.PREVIEW, releases[0].channel)
        assertEquals(AppUpdateRepository.Channel.STABLE, releases[1].channel)
        assertEquals(AppUpdateRepository.Channel.BETA, releases[2].channel)
    }

    @Test fun versionWithSuffixCanMatchOnlyTheSameCurrentChannel() {
        val releases = WhatsNewCatalog.parse("""
            ## 3.8.0.beta — Beta
            - Beta notes.
            ## 3.8.0.PRE — Preview
            - Preview notes.
        """.trimIndent())

        assertEquals("3.8.0.beta", WhatsNewCatalog.currentRelease(releases, "3.8.0.beta")?.version)
        assertNull(WhatsNewCatalog.currentRelease(releases, "3.8.0"))
    }

    @Test fun remoteNotesFillMissingCurrentReleaseAndKeepBundledDetails() {
        val local = WhatsNewCatalog.parse("""
            ## 3.8.0 — Stable
            - Bundled detailed notes.
        """.trimIndent())
        val remote = listOf(
            AppRelease("v3.8.1", "PiperOS Tool 3.8.1", "Hotfix notes.\n- Firebase Analytics integration.", "2026-10-16", null, 0L, null),
            AppRelease("3.8.0", "PiperOS Tool 3.8.0", "Short release body.", "2026-10-15", null, 0L, null)
        )

        val merged = WhatsNewCatalog.mergeLocalAndRemote(local, remote)

        assertEquals(listOf("3.8.1", "3.8.0"), merged.map { it.version })
        assertEquals("Hotfix notes.\nFirebase Analytics integration.", merged.first().details)
        assertEquals("Bundled detailed notes.", merged.last().details)
        assertEquals("3.8.1", WhatsNewCatalog.currentRelease(merged, "3.8.1")?.version)
    }
}
