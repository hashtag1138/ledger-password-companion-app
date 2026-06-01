package com.ledgerpasswords.companion.core.model

data class Vault(
    val entries: List<PasswordIdentifier> = emptyList(),
    val source: VaultSource = VaultSource.Local,
) {
    fun sortedByNickname(): Vault = copy(entries = entries.sortedBy { it.nickname.lowercase() })
}

enum class VaultSource {
    Local,
    LedgerDevice,
    BackupFile,
    TestFixture,
}
