package com.piperostool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPasswordPolicyTest {
    @Test
    fun acceptsPasswordThatMeetsConfiguredFirebaseRules() {
        assertTrue(AuthPasswordPolicy.missingRequirements("Piper7!" ).isEmpty())
    }

    @Test
    fun reportsEveryMissingPasswordRequirement() {
        assertEquals(
            listOf(
                R.string.auth_password_rule_uppercase,
                R.string.auth_password_rule_number,
                R.string.auth_password_rule_special
            ),
            AuthPasswordPolicy.missingRequirements("abcdef")
        )
        assertTrue(
            AuthPasswordPolicy.missingRequirements("abcde")
                .contains(R.string.auth_password_rule_minimum)
        )
    }
}
