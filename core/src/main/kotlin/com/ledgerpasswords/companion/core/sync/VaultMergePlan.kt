package com.ledgerpasswords.companion.core.sync

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource

data class VaultMergeConflict(
    val nickname: String,
    val localEntry: PasswordIdentifier,
    val remoteEntry: PasswordIdentifier,
    val reason: String,
)

data class VaultMergePlan(
    val localOnly: List<PasswordIdentifier>,
    val remoteOnly: List<PasswordIdentifier>,
    val identical: List<PasswordIdentifier>,
    val conflicts: List<VaultMergeConflict>,
    val mergedVault: Vault?,
) {
    val hasChanges: Boolean get() = localOnly.isNotEmpty() || remoteOnly.isNotEmpty() || conflicts.isNotEmpty()
    val canMerge: Boolean get() = conflicts.isEmpty()
}

class VaultMergePlanner {
    fun plan(local: Vault, remote: Vault): VaultMergePlan {
        val localByNickname = local.entries.associateBy { it.nickname }
        val remoteByNickname = remote.entries.associateBy { it.nickname }

        val localOnly = local.entries.filter { it.nickname !in remoteByNickname }
        val remoteOnly = remote.entries.filter { it.nickname !in localByNickname }
        val identical = mutableListOf<PasswordIdentifier>()
        val conflicts = mutableListOf<VaultMergeConflict>()

        local.entries.forEach { localEntry ->
            val remoteEntry = remoteByNickname[localEntry.nickname] ?: return@forEach
            if (localEntry.charsets == remoteEntry.charsets) {
                identical += localEntry
            } else {
                conflicts +=
                    VaultMergeConflict(
                        nickname = localEntry.nickname,
                        localEntry = localEntry,
                        remoteEntry = remoteEntry,
                        reason = "Local and target disagree on the charset policy for this identifier.",
                    )
            }
        }

        val mergedVault =
            if (conflicts.isEmpty()) {
                Vault(
                    entries = (identical + localOnly + remoteOnly).sortedBy { it.nickname.lowercase() },
                    source = VaultSource.Local,
                )
            } else {
                null
            }

        return VaultMergePlan(
            localOnly = localOnly,
            remoteOnly = remoteOnly,
            identical = identical,
            conflicts = conflicts,
            mergedVault = mergedVault,
        )
    }
}
