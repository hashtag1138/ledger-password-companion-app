package com.ledgerpasswords.companion.ledger.transport

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.ledger.apdu.ApduConstants
import com.ledgerpasswords.companion.ledger.apdu.StatusWords

class FakeLedgerTransport(
    private val appName: String = "Passwords",
    private val appVersion: String = "1.3.1",
    private val storageSize: Int = LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE,
    initialMetadatas: ByteArray = ByteArray(storageSize) { 0x00 },
) : LedgerTransport {
    private var metadatas: ByteArray = initialMetadatas.copyOf(storageSize)
    private var dumpOffset: Int = 0
    private var loadOffset: Int = 0
    private val loadBuffer: ByteArray = ByteArray(storageSize) { 0x00 }

    override suspend fun exchange(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ApduResponse = when {
        cla == ApduConstants.CLA_SDK && ins == ApduConstants.INS_GET_APP_INFO -> appInfo()
        cla == ApduConstants.CLA_PASSWORDS && ins == ApduConstants.INS_GET_APP_CONFIG -> appConfig()
        cla == ApduConstants.CLA_PASSWORDS && ins == ApduConstants.INS_DUMP_METADATAS -> dump()
        cla == ApduConstants.CLA_PASSWORDS && ins == ApduConstants.INS_LOAD_METADATAS -> load(p1, data)
        cla != ApduConstants.CLA_PASSWORDS && cla != ApduConstants.CLA_SDK -> ApduResponse(byteArrayOf(), StatusWords.CLA_NOT_SUPPORTED)
        else -> ApduResponse(byteArrayOf(), StatusWords.INS_NOT_SUPPORTED)
    }

    private fun appInfo(): ApduResponse {
        val name = appName.toByteArray(Charsets.US_ASCII)
        val version = appVersion.toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x01, name.size.toByte()) + name + byteArrayOf(version.size.toByte()) + version
        return ApduResponse(payload, StatusWords.OK)
    }

    private fun appConfig(): ApduResponse {
        dumpOffset = 0
        loadOffset = 0
        val size = storageSize
        val payload = byteArrayOf(
            ((size ushr 24) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
            0x00,
            0x00,
        )
        return ApduResponse(payload, StatusWords.OK)
    }

    private fun dump(): ApduResponse {
        val remaining = storageSize - dumpOffset
        val payloadSize = minOf(255, remaining)
        val isLast = dumpOffset + payloadSize >= storageSize
        val flag = if (isLast) ApduConstants.LAST_CHUNK else ApduConstants.MORE_DATA_INCOMING
        val payload = byteArrayOf(flag.toByte()) + metadatas.copyOfRange(dumpOffset, dumpOffset + payloadSize)
        dumpOffset += payloadSize
        if (isLast) dumpOffset = 0
        return ApduResponse(payload, StatusWords.OK)
    }

    private fun load(p1: Int, data: ByteArray): ApduResponse {
        if (p1 != ApduConstants.MORE_DATA_INCOMING && p1 != ApduConstants.LAST_CHUNK) {
            return ApduResponse(byteArrayOf(), StatusWords.WRONG_P1_P2)
        }
        if (loadOffset + data.size > storageSize) {
            loadOffset = 0
            return ApduResponse(byteArrayOf(), StatusWords.WRONG_DATA_LENGTH)
        }
        data.copyInto(loadBuffer, destinationOffset = loadOffset)
        loadOffset += data.size
        if (p1 == ApduConstants.LAST_CHUNK || loadOffset >= storageSize) {
            metadatas = loadBuffer.copyOf(storageSize)
            loadBuffer.fill(0x00)
            loadOffset = 0
        }
        return ApduResponse(byteArrayOf(), StatusWords.OK)
    }
}
