package com.ledgerpasswords.companion.ledger.metadata

object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    fun decode(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() }.removePrefix("0x")
        require(clean.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
