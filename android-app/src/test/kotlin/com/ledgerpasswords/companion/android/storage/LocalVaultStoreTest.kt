package com.ledgerpasswords.companion.android.storage

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.ledger.metadata.Hex
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalVaultStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load missing file returns empty local vault`() {
        val store = LocalVaultStore(tempDir.resolve("local-vault.json").toFile())

        val result = store.load()

        assertNotNull(result.vault)
        assertEquals(VaultSource.Local, result.vault?.source)
        assertTrue(result.vault?.entries?.isEmpty() == true)
        assertEquals("Local vault is empty. Add an identifier or import from Ledger.", result.message)
        assertNull(result.backupJsonText)
    }

    @Test
    fun `saveVault normalizes source sorts entries and persists exact json`() {
        val file = tempDir.resolve("local-vault.json").toFile()
        val store = LocalVaultStore(file)

        val json = store.saveVault(
            Vault(
                entries = listOf(PasswordIdentifier("zeta"), PasswordIdentifier("alpha")),
                source = VaultSource.LedgerDevice,
            ),
        )
        val result = store.load()

        assertEquals(listOf("alpha", "zeta"), result.vault?.entries?.map { it.nickname })
        assertEquals(VaultSource.Local, result.vault?.source)
        assertEquals(json, file.readText())
        assertEquals(json, result.backupJsonText)
    }

    @Test
    fun `load prefers raw metadata over parsed entries and preserves original backup text`() {
        val file = tempDir.resolve("local-vault.json").toFile()
        val raw = MetadataCodec().encode(Vault(entries = listOf(PasswordIdentifier("from-raw"))))
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
              "raw_metadatas": "${Hex.encode(raw)}"
            }
            """.trimIndent()
        file.writeText(text)
        val store = LocalVaultStore(file)

        val result = store.load()

        assertEquals(listOf("from-raw"), result.vault?.entries?.map { it.nickname })
        assertEquals(VaultSource.Local, result.vault?.source)
        assertEquals(text, result.backupJsonText)
    }

    @Test
    fun `load preserves local metadata while preferring raw ledger content`() {
        val file = tempDir.resolve("local-vault.json").toFile()
        val raw = MetadataCodec().encode(Vault(entries = listOf(PasswordIdentifier("from-raw"))))
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
              "raw_metadatas": "${Hex.encode(raw)}",
              "local_metadata": {
                "entries": [
                  {
                    "nickname": "from-raw",
                    "info": "Keep me local"
                  }
                ]
              }
            }
            """.trimIndent()
        file.writeText(text)
        val store = LocalVaultStore(file)

        val result = store.load()

        assertEquals(listOf("from-raw"), result.vault?.entries?.map { it.nickname })
        assertEquals("Keep me local", result.vault?.entries?.single()?.localNote)
        assertEquals(text, result.backupJsonText)
    }

    @Test
    fun `load invalid json preserves corrupt copy and resets to empty local vault`() {
        val file = tempDir.resolve("local-vault.json").toFile()
        file.writeText("{ this is not valid json")
        val store = LocalVaultStore(file)

        val result = store.load()
        val corruptCopies =
            tempDir.toFile().listFiles { _, name ->
                name.startsWith("local-vault.corrupt-") && name.endsWith(".json")
            }.orEmpty()

        assertNotNull(result.vault)
        assertEquals(emptyList<String>(), result.vault?.entries?.map { it.nickname })
        assertTrue(result.message.contains("Local storage was invalid"))
        assertNull(result.backupJsonText)
        assertEquals(1, corruptCopies.size)
        assertEquals("{ this is not valid json", corruptCopies.single().readText())
    }

    @Test
    fun `load io failure keeps vault unset instead of replacing it with empty data`() {
        val directory = tempDir.resolve("existing-directory").toFile().apply { mkdirs() }
        val store = LocalVaultStore(directory)

        val result = store.load()

        assertNull(result.vault)
        assertNull(result.backupJsonText)
        assertTrue(result.message.contains("Unable to read"))
    }
}
