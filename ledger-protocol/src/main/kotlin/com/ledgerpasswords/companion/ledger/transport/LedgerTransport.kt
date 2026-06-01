package com.ledgerpasswords.companion.ledger.transport

interface LedgerTransport : AutoCloseable {
    suspend fun exchange(
        cla: Int,
        ins: Int,
        p1: Int = 0x00,
        p2: Int = 0x00,
        data: ByteArray = byteArrayOf(),
    ): ApduResponse

    override fun close() = Unit
}

data class ApduResponse(
    val data: ByteArray,
    val statusWord: Int,
)
