package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperSetEditPolicyTest {
    @Test fun acceptsOnlySimpleSettingsKeys() {
        assertTrue(PiperSetEditPolicy.isValidKey("screen_brightness"))
        assertTrue(PiperSetEditPolicy.isValidKey("vendor.setting-2"))
        assertFalse(PiperSetEditPolicy.isValidKey("key; reboot"))
        assertFalse(PiperSetEditPolicy.isValidKey("has space"))
    }

    @Test fun shellQuotingKeepsUserValueAsOneArgument() {
        assertEquals("'hello'\\'' world'", PiperSetEditPolicy.shellQuote("hello' world"))
    }

    @Test fun labelsSystemValuesAndMarksSensitiveOrPrivilegedTablesUnsafe() {
        assertEquals(PiperSetEditPolicy.Risk.RELATIVE_SAFE, PiperSetEditPolicy.risk(PiperSetEditPolicy.Table.SYSTEM, "screen_brightness"))
        assertEquals(PiperSetEditPolicy.Risk.UNSAFE, PiperSetEditPolicy.risk(PiperSetEditPolicy.Table.SYSTEM, "adb_enabled"))
        assertEquals(PiperSetEditPolicy.Risk.UNSAFE, PiperSetEditPolicy.risk(PiperSetEditPolicy.Table.GLOBAL, "animator_duration_scale"))
    }

    @Test fun parsesValuesContainingEqualsAndSkipsMalformedRows() {
        assertEquals(
            listOf("a" to "x=y", "b" to ""),
            PiperSetEditPolicy.parseSettingsList("a=x=y\nmalformed\nb=\n")
        )
    }

    @Test fun snapshotDiffRestoresChangedAndMissingKeysOnly() {
        val saved = mapOf("keep" to "same", "changed" to "before", "missing_now" to "restore")
        val current = mapOf("keep" to "same", "changed" to "after", "new_since_backup" to "remove")

        val diff = PiperSetEditPolicy.diffSnapshot(saved, current)

        assertEquals(mapOf("changed" to "before", "missing_now" to "restore"), diff.valuesToRestore)
        assertEquals(setOf("new_since_backup"), diff.keysToDelete)
        assertEquals(3, diff.changedCount)
    }
}
