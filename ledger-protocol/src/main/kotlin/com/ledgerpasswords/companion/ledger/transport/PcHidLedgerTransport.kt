package com.ledgerpasswords.companion.ledger.transport

import org.hid4java.HidDevice
import org.hid4java.HidException
import org.hid4java.HidServices
import org.hid4java.HidServicesSpecification

class PcHidLedgerTransport internal constructor(
    private val vendorId: Int = LEDGER_VENDOR_ID,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val packetSize: Int = LedgerHidFraming.DEFAULT_PACKET_SIZE,
    private val framing: LedgerHidFraming = LedgerHidFraming(packetSize = packetSize),
    private val deviceProvider: DeviceProvider,
) : LedgerTransport {
    private var session: DeviceSession? = null

    constructor(
        vendorId: Int = LEDGER_VENDOR_ID,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        packetSize: Int = LedgerHidFraming.DEFAULT_PACKET_SIZE,
        framing: LedgerHidFraming = LedgerHidFraming(packetSize = packetSize),
    ) : this(vendorId, readTimeoutMs, packetSize, framing, Hid4JavaDeviceProvider())

    override suspend fun exchange(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ApduResponse {
        val apdu = byteArrayOf(
            cla.toByte(),
            ins.toByte(),
            p1.toByte(),
            p2.toByte(),
            data.size.toByte(),
        ) + data

        val device = session ?: openSession().also { session = it }
        framing.wrap(apdu).forEach { packet ->
            device.write(packet)
        }

        val response = framing.unwrap(generateSequence { device.read() })
        require(response.size >= 2) { "Ledger HID response is missing the status word" }
        val dataEnd = response.size - 2
        val sw = ((response[dataEnd].toInt() and 0xFF) shl 8) or (response[dataEnd + 1].toInt() and 0xFF)
        return ApduResponse(response.copyOfRange(0, dataEnd), sw)
    }

    override fun close() {
        session?.close()
        session = null
    }

    private fun openSession(): DeviceSession = deviceProvider.open(vendorId, packetSize, readTimeoutMs)

    companion object {
        const val LEDGER_VENDOR_ID = 0x2C97
        const val LEDGER_USAGE_PAGE = 0xFFA0
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
    }
}

internal interface DeviceProvider {
    fun open(vendorId: Int, packetSize: Int, readTimeoutMs: Int): DeviceSession
}

internal interface DeviceSession : AutoCloseable {
    fun write(packet: ByteArray)
    fun read(): ByteArray
}

internal class Hid4JavaDeviceProvider : DeviceProvider {
    override fun open(vendorId: Int, packetSize: Int, readTimeoutMs: Int): DeviceSession {
        val specification =
            HidServicesSpecification().apply {
                setAutoStart(false)
                setAutoShutdown(false)
                setAutoDataRead(false)
            }
        val services = HidServices(specification).also { it.start() }
        val ledgerDevices = services.attachedHidDevices.filter { hidDevice -> hidDevice.vendorId == vendorId }
        val device =
            ledgerDevices
                .sortedWith(
                    compareByDescending<HidDevice> { hidDevice ->
                        if (hidDevice.usagePage == PcHidLedgerTransport.LEDGER_USAGE_PAGE) 1 else 0
                    }.thenByDescending { hidDevice ->
                        if (hidDevice.interfaceNumber > 0) 1 else 0
                    }.thenBy { hidDevice ->
                        hidDevice.interfaceNumber
                    },
                ).firstOrNull()
                ?: run {
                    services.shutdown()
                    error("No Ledger HID device found for vendor 0x${vendorId.toString(16)}")
                }

        if (!device.open()) {
            services.shutdown()
            error("Failed to open Ledger HID device at path ${device.path}")
        }

        return Hid4JavaDeviceSession(device, services, packetSize, readTimeoutMs)
    }
}

internal class Hid4JavaDeviceSession(
    private val device: HidDevice,
    private val services: HidServices,
    private val packetSize: Int,
    private val readTimeoutMs: Int,
) : DeviceSession {
    override fun write(packet: ByteArray) {
        require(packet.size == packetSize) { "Unexpected HID packet size ${packet.size}, expected $packetSize" }
        val written = device.write(packet, packetSize, REPORT_ID, false)
        if (written < 0) {
            error(device.lastErrorMessage ?: "Failed to write to Ledger HID device")
        }
    }

    override fun read(): ByteArray {
        val buffer = ByteArray(packetSize + 1)
        val bytesRead = device.read(buffer, readTimeoutMs)
        require(bytesRead > 0) { "Timed out waiting for a Ledger HID response packet" }

        return when {
            bytesRead == packetSize -> buffer.copyOf(packetSize)
            bytesRead == packetSize + 1 && buffer[0] == REPORT_ID -> buffer.copyOfRange(1, packetSize + 1)
            else -> error("Unexpected Ledger HID packet length $bytesRead")
        }
    }

    override fun close() {
        try {
            device.close()
        } finally {
            try {
                services.shutdown()
            } catch (_: HidException) {
                // Best-effort cleanup.
            }
        }
    }

    companion object {
        private const val REPORT_ID: Byte = 0x00
    }
}
