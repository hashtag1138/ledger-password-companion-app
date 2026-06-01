package com.ledgerpasswords.companion.android.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.ledgerpasswords.companion.ledger.transport.ApduResponse
import com.ledgerpasswords.companion.ledger.transport.LedgerTransport

/**
 * TODO: real Android USB transport.
 *
 * Implementation outline:
 *
 * 1. request UsbManager permission before constructing this transport;
 * 2. open the UsbDevice;
 * 3. find Ledger interface and IN/OUT endpoints;
 * 4. claim interface;
 * 5. APDU -> LedgerHidFraming.wrap -> OUT endpoint;
 * 6. IN endpoint -> LedgerHidFraming.unwrap -> ApduResponse.
 */
class AndroidUsbLedgerTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) : LedgerTransport {
    override suspend fun exchange(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ApduResponse {
        TODO("Implement Android USB Ledger transport")
    }

    override fun close() {
        // TODO close UsbDeviceConnection when implemented.
    }
}
