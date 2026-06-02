package com.ledgerpasswords.companion.ledger.client

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.ledger.apdu.StatusWords
import com.ledgerpasswords.companion.ledger.apdu.ApduConstants
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import com.ledgerpasswords.companion.ledger.transport.ApduResponse
import com.ledgerpasswords.companion.ledger.transport.FakeLedgerTransport
import com.ledgerpasswords.companion.ledger.transport.LedgerTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LedgerPasswordsClientTest {
    @Test
    fun `getAppInfo and getAppConfig decode fake transport responses`() = runBlocking {
        val client = LedgerPasswordsClient(FakeLedgerTransport(appVersion = "1.3.1"))

        val info = client.getAppInfo()
        val config = client.getAppConfig()

        assertEquals("Passwords", info.name)
        assertEquals("1.3.1", info.version)
        assertEquals(4096, config.storageSize)
        assertEquals(0, config.keyboardType)
        assertEquals(false, config.pressEnterAfterTyping)
    }

    @Test
    fun `loadMetadatas then dumpMetadatas returns identical payload`() = runBlocking {
        val raw = MetadataCodec().encode(
            Vault(
                entries = listOf(
                    PasswordIdentifier("github"),
                    PasswordIdentifier("gmail"),
                    PasswordIdentifier("proton"),
                ),
            ),
        )
        val client = LedgerPasswordsClient(FakeLedgerTransport())

        client.loadMetadatas(raw)
        val dumped = client.dumpMetadatas()

        assertArrayEquals(raw, dumped)
    }

    @Test
    fun `non success status word raises LedgerStatusException`() {
        val transport =
            object : LedgerTransport {
                override suspend fun exchange(
                    cla: Int,
                    ins: Int,
                    p1: Int,
                    p2: Int,
                    data: ByteArray,
                ): ApduResponse = ApduResponse(byteArrayOf(), StatusWords.ACTION_CANCELLED)
            }

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    LedgerPasswordsClient(transport).getAppConfig()
                }
            }

        assertEquals("Action cancelled", error.message)
    }

    @Test
    fun `dumpMetadatas rejects unexpected chunk flags`() {
        val transport =
            object : LedgerTransport {
                override suspend fun exchange(
                    cla: Int,
                    ins: Int,
                    p1: Int,
                    p2: Int,
                    data: ByteArray,
                ): ApduResponse = ApduResponse(byteArrayOf(0x7F, 0x01, 0x02), StatusWords.OK)
            }

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    LedgerPasswordsClient(transport).dumpMetadatas(storageSize = 2)
                }
            }

        assertEquals("Unexpected dump flag: 0x7f", error.message)
    }

    @Test
    fun `dumpMetadatas rejects oversized chunks`() {
        val transport =
            object : LedgerTransport {
                override suspend fun exchange(
                    cla: Int,
                    ins: Int,
                    p1: Int,
                    p2: Int,
                    data: ByteArray,
                ): ApduResponse = ApduResponse(
                    byteArrayOf(
                        ApduConstants.LAST_CHUNK.toByte(),
                        0x01,
                        0x02,
                        0x03,
                    ),
                    StatusWords.OK,
                )
            }

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    LedgerPasswordsClient(transport).dumpMetadatas(storageSize = 2)
                }
            }

        assertEquals("Dump response exceeds expected size: 3 bytes for target 2", error.message)
    }
}
