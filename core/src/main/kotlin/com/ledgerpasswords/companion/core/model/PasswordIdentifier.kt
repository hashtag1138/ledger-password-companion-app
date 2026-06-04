package com.ledgerpasswords.companion.core.model

import java.time.Instant

data class PasswordIdentifier(
    val nickname: String,
    val charsets: CharsetPolicy = CharsetPolicy.All,
    val localNote: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

fun PasswordIdentifier.sameLedgerRepresentation(other: PasswordIdentifier): Boolean =
    nickname == other.nickname && charsets == other.charsets

fun PasswordIdentifier.toLedgerComparable(): PasswordIdentifier = copy(
    localNote = null,
    createdAt = null,
    updatedAt = null,
)

fun PasswordIdentifier.overlayLocalMetadataFrom(source: PasswordIdentifier?): PasswordIdentifier =
    if (source == null) {
        this
    } else {
        copy(
            localNote = localNote ?: source.localNote,
            createdAt = createdAt ?: source.createdAt,
            updatedAt = updatedAt ?: source.updatedAt,
        )
    }

fun PasswordIdentifier.normalizedLocalMetadata(): PasswordIdentifier = copy(
    localNote = localNote?.takeUnless { it.isBlank() },
)
