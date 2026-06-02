package com.ledgerpasswords.companion.ledger.backup

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.ledger.metadata.DecodedMetadata
import com.ledgerpasswords.companion.ledger.metadata.Hex
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import com.ledgerpasswords.companion.ledger.metadata.MetadataCorruption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupJsonCodecTest {
    private val codec = BackupJsonCodec()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fromJson ignores unknown keys and parses all sets`() {
        val text =
            """
            {
              "format": "ledger-passwords-companion.v1",
              "storage_size": 4096,
              "parsed": [
                {
                  "nickname": "github",
                  "charsets": ["UPPERCASE", "LOWERCASE", "NUMBERS"]
                },
                {
                  "nickname": "gmail",
                  "charsets": ["ALL_SETS"]
                }
              ],
              "unexpected_field": {
                "nested": true
              }
            }
            """.trimIndent()

        val vault = codec.fromJson(text)

        assertEquals(VaultSource.BackupFile, vault.source)
        assertEquals(listOf("github", "gmail"), vault.entries.map { it.nickname })
        assertEquals(CharsetPolicy.fromCli("upper,lower,numbers"), vault.entries.first().charsets)
        assertEquals(CharsetPolicy.All, vault.entries.last().charsets)
    }

    @Test
    fun `rawFromJson prefers raw metadata over parsed entries`() {
        val embeddedRaw = MetadataCodec().encode(Vault(entries = listOf(PasswordIdentifier("from-raw"))))
        val text =
            """
            {
              "format": "ledger-passwords-companion.v1",
              "storage_size": 4096,
              "parsed": [
                {
                  "nickname": "from-parsed",
                  "charsets": ["ALL_SETS"]
                }
              ],
              "raw_metadatas": "${Hex.encode(embeddedRaw)}"
            }
            """.trimIndent()

        val raw = codec.rawFromJson(text)

        assertArrayEquals(embeddedRaw, raw)
    }

    @Test
    fun `toJson includes erased entries corruptions and raw metadata`() {
        val decoded = DecodedMetadata(
            vault = Vault(
                entries = listOf(
                    PasswordIdentifier(
                        nickname = "github",
                        charsets = CharsetPolicy.fromCli("upper,lower,numbers"),
                    ),
                ),
                source = VaultSource.LedgerDevice,
            ),
            erasedEntries = listOf(PasswordIdentifier("old-entry")),
            corruptions = listOf(MetadataCorruption(offset = 12, message = "Unknown metadata kind")),
            rawHex = "deadc0de",
        )

        val output = codec.toJson(decoded, app = BackupApp(name = "Passwords", version = "1.3.1"))
        val file = json.decodeFromString<BackupFile>(output)

        assertEquals(BackupJsonCodec.FORMAT, file.format)
        assertEquals(LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE, file.storageSize)
        assertEquals("Passwords", file.app?.name)
        assertEquals("1.3.1", file.app?.version)
        assertEquals(listOf("github"), file.parsed.map { it.nickname })
        assertEquals(listOf("old-entry"), file.erased.map { it.nickname })
        assertEquals(listOf("offset=12: Unknown metadata kind"), file.corruptions)
        assertEquals("deadc0de", file.rawMetadatas)
        assertTrue(output.contains("\"nicknames_erased_but_still_stored\""))
    }

    @Test
    fun `inspect flags parsed raw mismatch`() {
        val embeddedRaw = MetadataCodec().encode(Vault(entries = listOf(PasswordIdentifier("from-raw"))))
        val text =
            """
            {
              "format": "ledger-passwords-companion.v1",
              "storage_size": 4096,
              "parsed": [
                {
                  "nickname": "from-parsed",
                  "charsets": ["ALL_SETS"]
                }
              ],
              "raw_metadatas": "${Hex.encode(embeddedRaw)}"
            }
            """.trimIndent()

        val inspection = codec.inspect(text)

        assertTrue(inspection.hasRawMetadatas)
        assertEquals(listOf("from-raw"), inspection.preferredVault.entries.map { it.nickname })
        assertTrue(inspection.findings.any { it.code == "parsed_raw_mismatch" })
    }

    @Test
    fun `inspect flags raw not roundtrip stable`() {
        val raw = MetadataCodec().encode(Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("gmail"))))
        raw[9] = (raw[9] + 1).toByte()
        val text =
            """
            {
              "format": "ledger-passwords-companion.v1",
              "storage_size": 4096,
              "parsed": [],
              "raw_metadatas": "${Hex.encode(raw)}"
            }
            """.trimIndent()

        val inspection = codec.inspect(text)

        assertTrue(inspection.findings.any { it.code == "parsed_raw_mismatch" })
        assertTrue(inspection.findings.any { it.code == "raw_not_roundtrip_stable" })
    }
}
