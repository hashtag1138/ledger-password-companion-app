package com.ledgerpasswords.companion.core.diff

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault

data class VaultDiff(
    val added: List<PasswordIdentifier>,
    val removed: List<PasswordIdentifier>,
    val changedCharsets: List<ChangedEntry>,
    val unchanged: List<PasswordIdentifier>,
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || changedCharsets.isNotEmpty()
}

data class ChangedEntry(
    val before: PasswordIdentifier,
    val after: PasswordIdentifier,
)

class VaultDiffer {
    fun diff(before: Vault, after: Vault): VaultDiff {
        val beforeByName = before.entries.associateBy { it.nickname }
        val afterByName = after.entries.associateBy { it.nickname }

        val added = after.entries.filter { it.nickname !in beforeByName }
        val removed = before.entries.filter { it.nickname !in afterByName }
        val changed = after.entries.mapNotNull { newEntry ->
            val oldEntry = beforeByName[newEntry.nickname] ?: return@mapNotNull null
            if (oldEntry.charsets != newEntry.charsets) ChangedEntry(oldEntry, newEntry) else null
        }
        val unchanged = after.entries.filter { newEntry ->
            val oldEntry = beforeByName[newEntry.nickname]
            oldEntry != null && oldEntry.charsets == newEntry.charsets
        }

        return VaultDiff(added = added, removed = removed, changedCharsets = changed, unchanged = unchanged)
    }
}
