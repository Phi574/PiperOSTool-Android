package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Test

class TotpCodeGeneratorTest {
    @Test
    fun matchesRfc6238Sha1EightDigitVector() {
        // Base32 for ASCII "12345678901234567890"; RFC 6238 test time 59 seconds.
        assertEquals(
            "94287082",
            TotpCodeGenerator.generate("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 8, 30, 59)
        )
    }

    @Test
    fun matchesRfc4226HotpVectors() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("755224", TotpCodeGenerator.generateHotp(secret, "SHA1", 6, 0))
        assertEquals("287082", TotpCodeGenerator.generateHotp(secret, "SHA1", 6, 1))
    }

    @Test
    fun steamGuardCodeHasFiveCharactersFromItsAlphabet() {
        val code = TotpCodeGenerator.generateSteam("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59)
        assertEquals(5, code.length)
        org.junit.Assert.assertTrue(code.all { it in "23456789BCDFGHJKMNPQRTVWXY" })
    }
}
