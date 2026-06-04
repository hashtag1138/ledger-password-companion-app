package com.ledgerpasswords.companion.core.model

data class Vault(
    val entries: List<PasswordIdentifier> = emptyList(),
    val source: VaultSource = VaultSource.Local,
) {
    fun sortedByNickname(): Vault = copy(entries = entries.sortedBy { it.nickname.lowercase() })
}

fun Vault.toLedgerComparable(): Vault = copy(
    entries = entries.map { it.toLedgerComparable() }.sortedBy { it.nickname.lowercase() },
)

fun Vault.hasSameLedgerEntries(other: Vault): Boolean = toLedgerComparable().entries == other.toLedgerComparable().entries

fun Vault.overlayLocalMetadataFrom(source: Vault): Vault {
    val sourceByNickname = source.entries.associateBy { it.nickname }
    return copy(
        entries =
            entries.map { entry ->
                entry
                    .overlayLocalMetadataFrom(sourceByNickname[entry.nickname])
                    .normalizedLocalMetadata()
            },
    )
}

enum class VaultSource {
    Local,
    LedgerDevice,
    BackupFile,
    TestFixture,
}
