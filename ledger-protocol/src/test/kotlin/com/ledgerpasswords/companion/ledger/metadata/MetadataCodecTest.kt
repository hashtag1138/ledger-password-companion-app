package com.ledgerpasswords.companion.ledger.metadata

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataCodecTest {
    private val codec = MetadataCodec()

    @Test
    fun `decode existing metadata fixture`() {
        val raw = Hex.decode("0a000770617373776f7264310a000770617373776f7264320a000770617373776f726433") + ByteArray(4096 - 36)
        val decoded = codec.decode(raw)
        assertEquals(listOf("password1", "password2", "password3"), decoded.vault.entries.map { it.nickname })
        assertTrue(decoded.corruptions.isEmpty())
    }

    @Test
    fun `encode then decode roundtrip`() {
        val vault = Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("gmail")))
        val raw = codec.encode(vault)
        val decoded = codec.decode(raw)
        assertEquals(vault.entries.map { it.nickname }, decoded.vault.entries.map { it.nickname })
    }
}
