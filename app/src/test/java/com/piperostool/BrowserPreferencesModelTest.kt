package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPreferencesModelTest {
    @Test
    fun invalidThemeFallsBackToSystem() {
        assertEquals(BrowserThemeMode.SYSTEM, BrowserThemeMode.fromPreference("unknown"))
    }

    @Test
    fun everySearchEngineConsumesEncodedQuery() {
        BrowserSessionStore.searchEngines().forEach { engine ->
            val url = engine.searchUrl("piper%20os")
            assertTrue(engine.label, url.startsWith("https://"))
            assertTrue(engine.label, url.contains("piper%20os"))
        }
    }
}
