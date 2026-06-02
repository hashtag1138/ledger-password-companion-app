package com.ledgerpasswords.companion.ledger.transport

import java.io.ByteArrayOutputStream

/**
 * Framing helper for Ledger's HID APDU transport.
 *
 * Packets produced by this class are fixed-width HID payloads and intentionally exclude any
 * optional HID report-id byte. Host-specific transports should add or strip that byte only if
 * their HID API requires it.
 */
class LedgerHidFraming(
    private val channel: Int = DEFAULT_CHANNEL,
    private val packetSize: Int = DEFAULT_PACKET_SIZE,
) {
    init {
        require(channel in 0x0000..0xFFFF) { "Channel must fit in 16 bits" }
        require(packetSize > FIRST_PACKET_HEADER_SIZE) {
            "Packet size must be greater than $FIRST_PACKET_HEADER_SIZE bytes"
        }
    }

    fun wrap(apdu: ByteArray): List<ByteArray> {
        require(apdu.size <= 0xFFFF) { "APDU payload must fit in 65535 bytes" }

        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var sequenceIndex = 0

        do {
            val packet = ByteArray(packetSize)
            writeUint16(packet, 0, channel)
            packet[2] = TAG_APDU.toByte()
            writeUint16(packet, 3, sequenceIndex)

            val payloadOffset =
                if (sequenceIndex == 0) {
                    writeUint16(packet, 5, apdu.size)
                    FIRST_PACKET_HEADER_SIZE
                } else {
                    NEXT_PACKET_HEADER_SIZE
                }

            val chunkSize = minOf(packetSize - payloadOffset, apdu.size - offset)
            if (chunkSize > 0) {
                apdu.copyInto(packet, destinationOffset = payloadOffset, startIndex = offset, endIndex = offset + chunkSize)
                offset += chunkSize
            }

            packets += packet
            sequenceIndex++
        } while (offset < apdu.size || packets.isEmpty())

        return packets
    }

    fun unwrap(packets: Sequence<ByteArray>): ByteArray {
        val iterator = packets.iterator()
        require(iterator.hasNext()) { "No HID packets to unwrap" }

        val output = ByteArrayOutputStream()
        var expectedLength = -1
        var expectedSequenceIndex = 0

        while (iterator.hasNext()) {
            val packet = iterator.next()
            require(packet.size == packetSize) {
                "Unexpected packet size ${packet.size}, expected $packetSize"
            }

            val actualChannel = readUint16(packet, 0)
            require(actualChannel == channel) {
                "Unexpected HID channel 0x${actualChannel.toString(16)}, expected 0x${channel.toString(16)}"
            }
            val actualTag = packet[2].toInt() and 0xFF
            require(actualTag == TAG_APDU) {
                "Unexpected HID tag 0x${actualTag.toString(16)}, expected 0x${TAG_APDU.toString(16)}"
            }
            val actualSequenceIndex = readUint16(packet, 3)
            require(actualSequenceIndex == expectedSequenceIndex) {
                "Unexpected HID sequence index $actualSequenceIndex, expected $expectedSequenceIndex"
            }

            val payloadOffset =
                if (expectedSequenceIndex == 0) {
                    expectedLength = readUint16(packet, 5)
                    FIRST_PACKET_HEADER_SIZE
                } else {
                    NEXT_PACKET_HEADER_SIZE
                }

            val remaining = expectedLength - output.size()
            val available = packetSize - payloadOffset
            val chunkSize = minOf(remaining, available)
            if (chunkSize > 0) {
                output.write(packet, payloadOffset, chunkSize)
            }

            if (output.size() >= expectedLength) {
                return output.toByteArray()
            }

            expectedSequenceIndex++
        }

        error("Incomplete HID response: expected $expectedLength bytes, got ${output.size()}")
    }

    companion object {
        const val DEFAULT_CHANNEL = 0x0101
        const val DEFAULT_PACKET_SIZE = 64
        const val TAG_APDU = 0x05

        private const val NEXT_PACKET_HEADER_SIZE = 5
        private const val FIRST_PACKET_HEADER_SIZE = 7
    }

    private fun writeUint16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun readUint16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
}
