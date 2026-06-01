package com.ledgerpasswords.companion.ledger.metadata

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault

data class DecodedMetadata(
    val vault: Vault,
    val erasedEntries: List<PasswordIdentifier>,
    val corruptions: List<MetadataCorruption>,
    val rawHex: String,
)

data class MetadataCorruption(
    val offset: Int,
    val message: String,
)
