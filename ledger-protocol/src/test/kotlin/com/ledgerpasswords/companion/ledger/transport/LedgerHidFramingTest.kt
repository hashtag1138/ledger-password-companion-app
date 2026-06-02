package com.ledgerpasswords.companion.ledger.transport

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerHidFramingTest {
    private val framing = LedgerHidFraming()

    @Test
    fun `wrap encodes a single packet APDU with length in first frame`() {
        val apdu = byteArrayOf(0xE0.toByte(), 0x03, 0x00, 0x00, 0x00)

        val packets = framing.wrap(apdu)

        assertEquals(1, packets.size)
        val packet = packets.single()
        assertEquals(64, packet.size)
        assertEquals(0x01, packet[0].toInt() and 0xFF)
        assertEquals(0x01, packet[1].toInt() and 0xFF)
        assertEquals(LedgerHidFraming.TAG_APDU, packet[2].toInt() and 0xFF)
        assertEquals(0x00, packet[3].toInt() and 0xFF)
        assertEquals(0x00, packet[4].toInt() and 0xFF)
        assertEquals(0x00, packet[5].toInt() and 0xFF)
        assertEquals(apdu.size, packet[6].toInt() and 0xFF)
        assertArrayEquals(apdu, packet.copyOfRange(7, 12))
        assertTrue(packet.copyOfRange(12, packet.size).all { it == 0.toByte() })
    }

    @Test
    fun `wrap splits long APDUs over multiple sequenced packets`() {
        val apdu = ByteArray(70) { it.toByte() }

        val packets = framing.wrap(apdu)

        assertEquals(2, packets.size)
        assertEquals(0x00, packets[0][3].toInt() and 0xFF)
        assertEquals(0x00, packets[0][4].toInt() and 0xFF)
        assertEquals(0x00, packets[0][5].toInt() and 0xFF)
        assertEquals(70, packets[0][6].toInt() and 0xFF)
        assertArrayEquals(apdu.copyOfRange(0, 57), packets[0].copyOfRange(7, 64))

        assertEquals(0x01, packets[1][0].toInt() and 0xFF)
        assertEquals(0x01, packets[1][1].toInt() and 0xFF)
        assertEquals(LedgerHidFraming.TAG_APDU, packets[1][2].toInt() and 0xFF)
        assertEquals(0x00, packets[1][3].toInt() and 0xFF)
        assertEquals(0x01, packets[1][4].toInt() and 0xFF)
        assertArrayEquals(apdu.copyOfRange(57, 70), packets[1].copyOfRange(5, 18))
        assertTrue(packets[1].copyOfRange(18, packets[1].size).all { it == 0.toByte() })
    }

    @Test
    fun `unwrap reconstructs a multi packet APDU`() {
        val apdu = ByteArray(130) { (it * 3).toByte() }
        val packets = framing.wrap(apdu)

        val unwrapped = framing.unwrap(packets.asSequence())

        assertArrayEquals(apdu, unwrapped)
    }

    @Test
    fun `unwrap rejects invalid sequence indexes`() {
        val apdu = ByteArray(70) { it.toByte() }
        val packets = framing.wrap(apdu).map { it.copyOf() }
        packets[1][4] = 0x02

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                framing.unwrap(packets.asSequence())
            }

        assertTrue(error.message!!.contains("Unexpected HID sequence index"))
    }

    @Test
    fun `unwrap rejects truncated packet streams`() {
        val apdu = ByteArray(70) { it.toByte() }
        val packets = framing.wrap(apdu)

        val error =
            assertThrows(IllegalStateException::class.java) {
                framing.unwrap(sequenceOf(packets.first()))
            }

        assertTrue(error.message!!.contains("Incomplete HID response"))
    }
}
