package com.ledgerpasswords.companion.core.model

import java.time.Instant

data class PasswordIdentifier(
    val nickname: String,
    val charsets: CharsetPolicy = CharsetPolicy.All,
    val localNote: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
