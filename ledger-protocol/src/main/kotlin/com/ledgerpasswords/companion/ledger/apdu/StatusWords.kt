package com.ledgerpasswords.companion.ledger.apdu

object StatusWords {
    const val OK = 0x9000
    const val ACTION_CANCELLED = 0x6985
    const val WRONG_P1_P2 = 0x6A86
    const val WRONG_DATA_LENGTH = 0x6A87
    const val INS_NOT_SUPPORTED = 0x6D00
    const val CLA_NOT_SUPPORTED = 0x6E00
    const val METADATAS_PARSING_ERROR = 0x6F10

    fun describe(sw: Int): String = when (sw) {
        OK -> "OK"
        ACTION_CANCELLED -> "Action cancelled"
        WRONG_P1_P2 -> "Wrong P1/P2"
        WRONG_DATA_LENGTH -> "Wrong data length"
        INS_NOT_SUPPORTED -> "INS not supported"
        CLA_NOT_SUPPORTED -> "CLA not supported"
        METADATAS_PARSING_ERROR -> "Metadata parsing error"
        else -> "Unknown status word 0x${sw.toString(16)}"
    }
}

class LedgerStatusException(
    val statusWord: Int,
    message: String = StatusWords.describe(statusWord),
) : IllegalStateException(message)
