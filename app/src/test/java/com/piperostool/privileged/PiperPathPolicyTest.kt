package com.piperostool.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperPathPolicyTest {
    @Test
    fun canonicalRejectsRelativeAndNulPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            PiperPathPolicy.canonical("data/local/tmp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PiperPathPolicy.canonical("/data/local/\u0000tmp")
        }
    }

    @Test
    fun protectedRootsRequireExplicitSystemWrite() {
        assertTrue(PiperPathPolicy.isProtected("/system/etc/hosts"))
        assertFalse(PiperPathPolicy.isProtected("/storage/emulated/0/Download"))
        assertThrows(SecurityException::class.java) {
            PiperPathPolicy.requireWriteAllowed("/vendor/etc", false)
        }
        assertEquals(
            "/vendor/etc",
            PiperPathPolicy.requireWriteAllowed("/vendor/etc", true)
        )
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'/data/a'\\''b'", PiperPathPolicy.shellQuote("/data/a'b"))
    }
}
