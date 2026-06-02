package com.ledgerpasswords.companion.android.usb

import android.hardware.usb.UsbRequest
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.ledgerpasswords.companion.android.storage.DiagnosticLogStore
import com.ledgerpasswords.companion.ledger.transport.ApduResponse
import com.ledgerpasswords.companion.ledger.transport.LedgerHidFraming
import com.ledgerpasswords.companion.ledger.transport.LedgerTransport
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class AndroidUsbLedgerTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val packetSize: Int = LedgerHidFraming.DEFAULT_PACKET_SIZE,
    private val framing: LedgerHidFraming = LedgerHidFraming(packetSize = packetSize),
) : LedgerTransport {
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    override suspend fun exchange(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ApduResponse {
        val exchangeId = NEXT_EXCHANGE_ID.incrementAndGet()
        try {
            val session = ensureOpen()
            val apdu = byteArrayOf(
                cla.toByte(),
                ins.toByte(),
                p1.toByte(),
                p2.toByte(),
                data.size.toByte(),
            ) + data

            val packets = framing.wrap(apdu)
            DiagnosticLogStore.info(
                TAG,
                "exchange#$exchangeId tx ${describeApdu(cla, ins, p1, p2, data.size)} " +
                    "packets=${packets.size} device=${device.deviceName}",
            )
            packets.forEachIndexed { index, packet ->
                session.writePacket(exchangeId, index, packet)
            }

            val response = framing.unwrap(generateSequence { session.readPacket(exchangeId) })
            require(response.size >= 2) { "Ledger USB response is missing the status word" }
            val dataEnd = response.size - 2
            val statusWord = ((response[dataEnd].toInt() and 0xFF) shl 8) or (response[dataEnd + 1].toInt() and 0xFF)
            DiagnosticLogStore.info(
                TAG,
                "exchange#$exchangeId rx sw=${statusWord.toHex16()} dataLen=$dataEnd totalLen=${response.size}",
            )
            return ApduResponse(
                data = response.copyOfRange(0, dataEnd),
                statusWord = statusWord,
            )
        } catch (error: Throwable) {
            DiagnosticLogStore.error(TAG, "exchange#$exchangeId failed on ${device.deviceName}: ${error.message}", error)
            throw error
        }
    }

    override fun close() {
        val currentConnection = connection
        val currentInterface = claimedInterface
        inEndpoint = null
        outEndpoint = null
        claimedInterface = null
        connection = null

        if (currentConnection != null && currentInterface != null) {
            runCatching { currentConnection.releaseInterface(currentInterface) }
        }
        DiagnosticLogStore.info(TAG, "close device=${device.deviceName} interface=${currentInterface?.id}")
        currentConnection?.close()
    }

    private fun ensureOpen(): AndroidUsbSession {
        val currentConnection = connection
        val currentInterface = claimedInterface
        val currentInEndpoint = inEndpoint
        val currentOutEndpoint = outEndpoint
        if (currentConnection != null && currentInterface != null && currentInEndpoint != null && currentOutEndpoint != null) {
            return AndroidUsbSession(currentConnection, currentInEndpoint, currentOutEndpoint, packetSize, readTimeoutMs)
        }

        require(usbManager.hasPermission(device)) { "Missing USB permission for device ${device.deviceName}" }
        val openedConnection = usbManager.openDevice(device) ?: error("Failed to open USB device ${device.deviceName}")
        DiagnosticLogStore.info(
            TAG,
            "open device=${device.deviceName} vendor=0x${device.vendorId.toHex16()} " +
                "product=0x${device.productId.toHex16()} interfaces=${device.interfaceCount}",
        )
        logDeviceTopology(device)
        val interfaceSelection = findLedgerInterface(device)
            ?: run {
                openedConnection.close()
                error("No compatible Ledger USB interface found on ${device.deviceName}")
            }
        val ledgerInterface = interfaceSelection.usbInterface

        if (!openedConnection.claimInterface(ledgerInterface, true)) {
            openedConnection.close()
            error("Failed to claim USB interface ${ledgerInterface.id} on ${device.deviceName}")
        }
        DiagnosticLogStore.info(
            TAG,
            "claimed interface=${ledgerInterface.id} class=${ledgerInterface.interfaceClass} " +
                "subclass=${ledgerInterface.interfaceSubclass} protocol=${ledgerInterface.interfaceProtocol} " +
                "in=${interfaceSelection.inEndpoint.describeEndpoint()} out=${interfaceSelection.outEndpoint.describeEndpoint()}",
        )

        connection = openedConnection
        claimedInterface = ledgerInterface
        inEndpoint = interfaceSelection.inEndpoint
        outEndpoint = interfaceSelection.outEndpoint
        return AndroidUsbSession(
            openedConnection,
            interfaceSelection.inEndpoint,
            interfaceSelection.outEndpoint,
            packetSize,
            readTimeoutMs,
            )
    }

    private fun logDeviceTopology(device: UsbDevice) {
        (0 until device.interfaceCount).forEach { index ->
            val usbInterface = device.getInterface(index)
            val endpoints =
                (0 until usbInterface.endpointCount)
                    .joinToString(separator = ", ") { endpointIndex ->
                        usbInterface.getEndpoint(endpointIndex).describeEndpoint()
                    }
            DiagnosticLogStore.debug(
                TAG,
                "interface=${usbInterface.id} class=${usbInterface.interfaceClass} " +
                    "subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol} " +
                    "endpoints=[$endpoints]",
            )
        }
    }

    private fun findLedgerInterface(device: UsbDevice): InterfaceSelection? =
        (0 until device.interfaceCount)
            .map(device::getInterface)
            .mapNotNull { usbInterface ->
                val endpoints = findEndpoints(usbInterface) ?: return@mapNotNull null
                InterfaceSelection(
                    usbInterface = usbInterface,
                    inEndpoint = endpoints.first,
                    outEndpoint = endpoints.second,
                )
            }
            .sortedWith(
                compareByDescending<InterfaceSelection> { selection ->
                    if (selection.usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) 1 else 0
                }.thenByDescending { selection ->
                    minOf(selection.inEndpoint.maxPacketSize, selection.outEndpoint.maxPacketSize)
                }.thenBy { it.usbInterface.id },
            )
            .firstOrNull()

    private fun findEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type !in SUPPORTED_ENDPOINT_TYPES) continue
            if (endpoint.maxPacketSize < packetSize) continue
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> if (inEndpoint == null) inEndpoint = endpoint
                UsbConstants.USB_DIR_OUT -> if (outEndpoint == null) outEndpoint = endpoint
            }
        }

        return if (inEndpoint != null && outEndpoint != null) {
            inEndpoint to outEndpoint
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "LedgerPwUsb"
        private const val DEFAULT_READ_TIMEOUT_MS = 10_000L
        private val NEXT_EXCHANGE_ID = AtomicInteger(1)
        private val SUPPORTED_ENDPOINT_TYPES =
            setOf(
                UsbConstants.USB_ENDPOINT_XFER_INT,
                UsbConstants.USB_ENDPOINT_XFER_BULK,
            )
    }
}

private data class InterfaceSelection(
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
)

private class AndroidUsbSession(
    private val connection: UsbDeviceConnection,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val packetSize: Int,
    private val readTimeoutMs: Long,
) {
    fun writePacket(exchangeId: Int, packetIndex: Int, packet: ByteArray) {
        require(packet.size == packetSize) { "Unexpected USB packet size ${packet.size}, expected $packetSize" }
        DiagnosticLogStore.debug(
            TAG,
            "exchange#$exchangeId write packet=$packetIndex seq=${packet.sequenceIndex()} endpoint=${outEndpoint.describeEndpoint()} size=${packet.size}",
        )
        val transferred = transfer(outEndpoint, packet)
        require(transferred.size == packet.size) {
            "Short USB write to endpoint ${outEndpoint.address}: wrote ${transferred.size} of ${packet.size} bytes"
        }
    }

    fun readPacket(exchangeId: Int): ByteArray {
        val bufferSize = maxOf(packetSize, inEndpoint.maxPacketSize)
        val raw = transfer(inEndpoint, ByteArray(bufferSize))
        val normalized =
            when {
            raw.size == packetSize -> raw
            raw.size == packetSize + 1 && raw.first() == 0.toByte() -> raw.copyOfRange(1, raw.size)
            else -> error("Unexpected USB packet length ${raw.size} from endpoint ${inEndpoint.address}")
        }
        DiagnosticLogStore.debug(
            TAG,
            "exchange#$exchangeId read seq=${normalized.sequenceIndex()} endpoint=${inEndpoint.describeEndpoint()} " +
                "rawSize=${raw.size} normalizedSize=${normalized.size}",
        )
        return normalized
    }

    private fun transfer(endpoint: UsbEndpoint, payload: ByteArray): ByteArray {
        val request = UsbRequest()
        require(request.initialize(connection, endpoint)) { "Failed to initialize USB request for endpoint ${endpoint.address}" }
        try {
            val buffer =
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    ByteBuffer.wrap(payload.copyOf())
                } else {
                    ByteBuffer.allocate(payload.size)
                }
            require(request.queue(buffer)) { "Failed to queue USB request for endpoint ${endpoint.address}" }
            val completed =
                try {
                    connection.requestWait(readTimeoutMs)
                } catch (error: TimeoutException) {
                    error("Timed out waiting for USB endpoint ${endpoint.address}")
                }
            require(completed === request) { "Unexpected USB request completion on endpoint ${completed?.endpoint?.address}" }
            val transferred = buffer.position()
            return if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                payload.copyOf(transferred)
            } else {
                buffer.array().copyOf(transferred)
            }
        } finally {
            request.close()
        }
    }

    companion object {
        private const val TAG = "LedgerPwUsb"
    }
}

private fun describeApdu(cla: Int, ins: Int, p1: Int, p2: Int, dataLength: Int): String =
    "cla=${cla.toHex8()} ins=${ins.toHex8()} op=${describeInstruction(cla, ins)} " +
        "p1=${p1.toHex8()} p2=${p2.toHex8()} dataLen=$dataLength"

private fun describeInstruction(cla: Int, ins: Int): String =
    when (cla to ins) {
        0xB0 to 0x01 -> "get_app_info"
        0xE0 to 0x03 -> "get_app_config"
        0xE0 to 0x04 -> "dump_metadatas"
        0xE0 to 0x05 -> "load_metadatas"
        else -> "unknown"
    }

private fun UsbEndpoint.describeEndpoint(): String =
    "0x${address.toHex8()}/${directionName()}/${typeName()}/max=$maxPacketSize"

private fun UsbEndpoint.directionName(): String =
    when (direction) {
        UsbConstants.USB_DIR_IN -> "IN"
        UsbConstants.USB_DIR_OUT -> "OUT"
        else -> direction.toString()
    }

private fun UsbEndpoint.typeName(): String =
    when (type) {
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
        else -> type.toString()
    }

private fun ByteArray.sequenceIndex(): Int? =
    if (size >= 5) {
        ((this[3].toInt() and 0xFF) shl 8) or (this[4].toInt() and 0xFF)
    } else {
        null
    }

private fun Int.toHex8(): String = "0x%02X".format(this and 0xFF)

private fun Int.toHex16(): String = "0x%04X".format(this and 0xFFFF)
