package com.ledgerpasswords.companion.core.validation

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `leading whitespace emits warning but stays valid`() {
        val result = validator.validate(Vault(entries = listOf(PasswordIdentifier(" leading"))))

        assertTrue(result.isValid)
        assertTrue(result.hasWarnings)
        assertEquals("leading_or_trailing_whitespace", result.warnings.single().code)
    }

    @Test
    fun `zero width character fails validation`() {
        val result = validator.validate(Vault(entries = listOf(PasswordIdentifier("zero\u200bwidth"))))

        assertFalse(result.isValid)
        assertEquals("dangerous_format_character", result.errors.single().code)
    }

    @Test
    fun `control character fails validation`() {
        val result = validator.validate(Vault(entries = listOf(PasswordIdentifier("line\nbreak"))))

        assertFalse(result.isValid)
        assertEquals("control_character", result.errors.single().code)
    }
}
