package com.ledgerpasswords.companion.ledger.apdu

object ApduConstants {
    const val CLA_SDK = 0xB0
    const val CLA_PASSWORDS = 0xE0

    const val INS_GET_APP_INFO = 0x01
    const val INS_GET_APP_CONFIG = 0x03
    const val INS_DUMP_METADATAS = 0x04
    const val INS_LOAD_METADATAS = 0x05

    const val P1_DEFAULT = 0x00
    const val P2_DEFAULT = 0x00

    const val MORE_DATA_INCOMING = 0x00
    const val LAST_CHUNK = 0xFF

    const val MAX_LOAD_CHUNK_SIZE = 0xFF
}
