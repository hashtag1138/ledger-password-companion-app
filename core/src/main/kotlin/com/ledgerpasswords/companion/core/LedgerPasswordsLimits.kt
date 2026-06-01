package com.ledgerpasswords.companion.core

object LedgerPasswordsLimits {
    const val DEFAULT_STORAGE_SIZE: Int = 4096
    const val MAX_METANAME_BYTES: Int = 20
    const val MAX_NICKNAME_BYTES: Int = MAX_METANAME_BYTES - 1
    const val MAX_METADATA_COUNT: Int = DEFAULT_STORAGE_SIZE / (1 + 1 + 1 + MAX_METANAME_BYTES)
}
