package com.ledgerpasswords.companion.ledger.transport

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.ledger.apdu.ApduConstants
import com.ledgerpasswords.companion.ledger.apdu.StatusWords
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeLedgerTransportTest {
    @Test
    fun `load rejects unsupported p1 values`() = runBlocking {
        val transport = FakeLedgerTransport()

        val response =
            transport.exchange(
                cla = ApduConstants.CLA_PASSWORDS,
                ins = ApduConstants.INS_LOAD_METADATAS,
                p1 = 0x01,
                data = byteArrayOf(0x00),
            )

        assertEquals(StatusWords.WRONG_P1_P2, response.statusWord)
    }

    @Test
    fun `load rejects buffers larger than storage size`() = runBlocking {
        val transport = FakeLedgerTransport()

        val response =
            transport.exchange(
                cla = ApduConstants.CLA_PASSWORDS,
                ins = ApduConstants.INS_LOAD_METADATAS,
                p1 = ApduConstants.LAST_CHUNK,
                data = ByteArray(LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE + 1),
            )

        assertEquals(StatusWords.WRONG_DATA_LENGTH, response.statusWord)
    }
}
