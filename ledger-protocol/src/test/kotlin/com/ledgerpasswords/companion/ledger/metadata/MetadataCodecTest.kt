package com.ledgerpasswords.companion.ledger.metadata

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertArrayEquals
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

    @Test
    fun `decode separates erased entries from active entries`() {
        val raw =
            (
                metadataEntry(kind = MetadataCodec.META_ACTIVE, nickname = "github") +
                    metadataEntry(kind = MetadataCodec.META_ERASED, nickname = "old-entry")
                ).padToStorage()

        val decoded = codec.decode(raw)

        assertEquals(listOf("github"), decoded.vault.entries.map { it.nickname })
        assertEquals(listOf("old-entry"), decoded.erasedEntries.map { it.nickname })
        assertTrue(decoded.corruptions.isEmpty())
    }

    @Test
    fun `decode reports nickname lengths above the ledger limit`() {
        val raw = metadataEntry(kind = MetadataCodec.META_ACTIVE, nickname = "abcdefghijklmnopqrst").padToStorage()

        val decoded = codec.decode(raw)

        assertEquals(listOf("abcdefghijklmnopqrst"), decoded.vault.entries.map { it.nickname })
        assertEquals(1, decoded.corruptions.size)
        assertTrue(decoded.corruptions.single().message.contains("Nickname data too long"))
    }

    @Test
    fun `decode reports entry overflow when metadata exceeds the raw buffer`() {
        val raw = byteArrayOf(0x14, 0x00, 0xFF.toByte(), 'a'.code.toByte())

        val decoded = codec.decode(raw)

        assertTrue(decoded.vault.entries.isEmpty())
        assertEquals(1, decoded.corruptions.size)
        assertTrue(decoded.corruptions.single().message.contains("overflows raw metadata buffer"))
    }

    @Test
    fun `encode writes a compact metadata buffer with zero terminator`() {
        val raw = codec.encode(Vault(entries = listOf(PasswordIdentifier("github"))))
        val expectedPrefix = metadataEntry(kind = MetadataCodec.META_ACTIVE, nickname = "github")

        assertArrayEquals(expectedPrefix, raw.copyOfRange(0, expectedPrefix.size))
        assertEquals(0x00, raw[expectedPrefix.size].toInt() and 0xFF)
        assertEquals(0x00, raw[expectedPrefix.size + 1].toInt() and 0xFF)
    }

    private fun metadataEntry(kind: Int, nickname: String, charset: Int = 0xFF): ByteArray {
        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
        val len = 1 + nicknameBytes.size
        return byteArrayOf(len.toByte(), kind.toByte(), charset.toByte()) + nicknameBytes
    }

    private fun ByteArray.padToStorage(size: Int = 4096): ByteArray = this + ByteArray(size - this.size)
}
