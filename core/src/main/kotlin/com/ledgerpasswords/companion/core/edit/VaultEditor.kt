package com.ledgerpasswords.companion.core.edit

import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.validation.VaultValidator

class VaultEditor(
    private val validator: VaultValidator = VaultValidator(),
) {
    fun add(vault: Vault, entry: PasswordIdentifier): Vault =
        vault.copy(entries = vault.entries + entry).alsoValid()

    fun delete(vault: Vault, nickname: String): Vault =
        vault.copy(entries = vault.entries.filterNot { it.nickname == nickname }).alsoValid()

    fun updateCharsets(vault: Vault, nickname: String, charsets: CharsetPolicy): Vault =
        vault.copy(entries = vault.entries.map { if (it.nickname == nickname) it.copy(charsets = charsets) else it }).alsoValid()

    fun rename(vault: Vault, oldNickname: String, newNickname: String): Vault =
        vault.copy(entries = vault.entries.map { if (it.nickname == oldNickname) it.copy(nickname = newNickname) else it }).alsoValid()

    fun replace(vault: Vault, entries: List<PasswordIdentifier>): Vault =
        vault.copy(entries = entries).alsoValid()

    private fun Vault.alsoValid(): Vault {
        validator.validate(this).throwIfInvalid()
        return this
    }
}
