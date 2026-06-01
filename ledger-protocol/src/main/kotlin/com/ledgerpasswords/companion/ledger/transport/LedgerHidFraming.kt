package com.ledgerpasswords.companion.ledger.transport

/**
 * TODO: implement Ledger HID framing.
 *
 * This class should convert a raw APDU payload into 64-byte Ledger HID packets and reassemble
 * incoming packets into the APDU response. Keep it platform-neutral so the PC HID transport and
 * Android USB transport can share it.
 */
class LedgerHidFraming(
    private val channel: Int = DEFAULT_CHANNEL,
    private val packetSize: Int = DEFAULT_PACKET_SIZE,
) {
    fun wrap(apdu: ByteArray): List<ByteArray> {
        TODO("Implement Ledger HID APDU framing")
    }

    fun unwrap(packets: Sequence<ByteArray>): ByteArray {
        TODO("Implement Ledger HID APDU reassembly")
    }

    companion object {
        const val DEFAULT_CHANNEL = 0x0101
        const val DEFAULT_PACKET_SIZE = 64
    }
}
