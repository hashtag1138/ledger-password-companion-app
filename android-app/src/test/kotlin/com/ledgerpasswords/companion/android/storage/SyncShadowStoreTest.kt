package com.ledgerpasswords.companion.android.storage

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SyncShadowStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load missing file returns null`() {
        val store = SyncShadowStore(tempDir.resolve("sync-shadow.properties").toFile())

        assertNull(store.load())
    }

    @Test
    fun `save and load preserve normalized sync shadow state`() {
        val file = tempDir.resolve("sync-shadow.properties").toFile()
        val store = SyncShadowStore(file)
        val saved =
            store.save(
                SyncShadowState(
                    lastSyncedVault = Vault(
                        entries = listOf(PasswordIdentifier("zeta"), PasswordIdentifier("alpha")),
                        source = VaultSource.LedgerDevice,
                    ),
                    targetKind = SyncTargetKind.Speculos,
                    targetDescriptor = "10.0.2.2:10100",
                    storageSize = 4096,
                    updatedAtEpochMillis = 1_770_000_000_000,
                ),
            )

        val loaded = store.load()

        assertEquals(saved, loaded)
        assertEquals(listOf("alpha", "zeta"), loaded?.lastSyncedVault?.entries?.map { it.nickname })
        assertEquals(VaultSource.Local, loaded?.lastSyncedVault?.source)
    }

    @Test
    fun `load invalid file returns null`() {
        val file = tempDir.resolve("sync-shadow.properties").toFile()
        file.writeText("format=broken\nvault_json_base64=not-base64\n")
        val store = SyncShadowStore(file)

        assertNull(store.load())
    }

    @Test
    fun `clear removes persisted shadow`() {
        val file = tempDir.resolve("sync-shadow.properties").toFile()
        val store = SyncShadowStore(file)
        store.save(
            SyncShadowState(
                lastSyncedVault = Vault(entries = listOf(PasswordIdentifier("github"))),
                targetKind = SyncTargetKind.Usb,
                targetDescriptor = "/dev/bus/usb/001/003",
                storageSize = 4096,
                updatedAtEpochMillis = 1,
            ),
        )

        store.clear()

        assertTrue(!file.exists())
    }
}
