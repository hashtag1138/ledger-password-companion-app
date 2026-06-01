package com.ledgerpasswords.companion.core.validation

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultValidatorTest {
    private val validator = VaultValidator()

    @Test
    fun `valid vault passes`() {
        val vault = Vault(entries = listOf(PasswordIdentifier("github")))
        assertTrue(validator.validate(vault).isValid)
    }

    @Test
    fun `duplicate nickname fails`() {
        val vault = Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("github")))
        assertFalse(validator.validate(vault).isValid)
    }

    @Test
    fun `nickname over 19 utf8 bytes fails`() {
        val vault = Vault(entries = listOf(PasswordIdentifier("abcdefghijklmnopqrst")))
        assertFalse(validator.validate(vault).isValid)
    }
}
