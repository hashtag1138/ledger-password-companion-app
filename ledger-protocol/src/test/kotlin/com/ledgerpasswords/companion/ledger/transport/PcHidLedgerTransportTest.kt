package com.ledgerpasswords.companion.ledger.transport

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PcHidLedgerTransportTest {
    @Test
    fun `exchange writes framed request and decodes APDU response`() = runBlocking {
        val framing = LedgerHidFraming()
        val writtenPackets = mutableListOf<ByteArray>()
        val responseApdu = byteArrayOf(0x01, 0x02, 0x90.toByte(), 0x00.toByte())
        val responsePackets = framing.wrap(responseApdu).iterator()
        val provider =
            object : DeviceProvider {
                override fun open(vendorId: Int, packetSize: Int, readTimeoutMs: Int): DeviceSession =
                    object : DeviceSession {
                        override fun write(packet: ByteArray) {
                            writtenPackets += packet
                        }

                        override fun read(): ByteArray = responsePackets.next()

                        override fun close() = Unit
                    }
            }

        val response =
            PcHidLedgerTransport(deviceProvider = provider).use { transport ->
                transport.exchange(
                    cla = 0xE0,
                    ins = 0x03,
                    p1 = 0x00,
                    p2 = 0x00,
                    data = byteArrayOf(),
                )
            }

        val expectedRequest = framing.wrap(byteArrayOf(0xE0.toByte(), 0x03, 0x00, 0x00, 0x00))
        assertEquals(expectedRequest.size, writtenPackets.size)
        assertArrayEquals(expectedRequest.first(), writtenPackets.first())
        assertArrayEquals(byteArrayOf(0x01, 0x02), response.data)
        assertEquals(0x9000, response.statusWord)
    }
}
