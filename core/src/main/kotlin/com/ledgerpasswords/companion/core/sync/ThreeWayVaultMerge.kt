package com.ledgerpasswords.companion.core.sync

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.core.model.sameLedgerRepresentation

enum class ThreeWayConflictReason {
    AddedDifferently,
    ChangedDifferently,
    LocalRemovedRemoteChanged,
    LocalChangedRemoteRemoved,
}

data class ThreeWayMergeConflict(
    val nickname: String,
    val baseEntry: PasswordIdentifier?,
    val localEntry: PasswordIdentifier?,
    val remoteEntry: PasswordIdentifier?,
    val reason: ThreeWayConflictReason,
)

data class ThreeWayMergedUpdate(
    val before: PasswordIdentifier,
    val after: PasswordIdentifier,
)

data class ThreeWayVaultMergePlan(
    val localAdditions: List<PasswordIdentifier>,
    val remoteAdditions: List<PasswordIdentifier>,
    val localUpdates: List<ThreeWayMergedUpdate>,
    val remoteUpdates: List<ThreeWayMergedUpdate>,
    val convergedUpdates: List<ThreeWayMergedUpdate>,
    val localRemovals: List<PasswordIdentifier>,
    val remoteRemovals: List<PasswordIdentifier>,
    val unchanged: List<PasswordIdentifier>,
    val conflicts: List<ThreeWayMergeConflict>,
    val mergedVault: Vault?,
) {
    val hasChanges: Boolean
        get() =
            localAdditions.isNotEmpty() ||
                remoteAdditions.isNotEmpty() ||
                localUpdates.isNotEmpty() ||
                remoteUpdates.isNotEmpty() ||
                convergedUpdates.isNotEmpty() ||
                localRemovals.isNotEmpty() ||
                remoteRemovals.isNotEmpty() ||
                conflicts.isNotEmpty()

    val canMerge: Boolean get() = conflicts.isEmpty()
}

class ThreeWayVaultMergePlanner {
    fun plan(
        base: Vault,
        local: Vault,
        remote: Vault,
    ): ThreeWayVaultMergePlan {
        val baseByNickname = base.entries.associateBy { it.nickname }
        val localByNickname = local.entries.associateBy { it.nickname }
        val remoteByNickname = remote.entries.associateBy { it.nickname }

        val localAdditions = mutableListOf<PasswordIdentifier>()
        val remoteAdditions = mutableListOf<PasswordIdentifier>()
        val localUpdates = mutableListOf<ThreeWayMergedUpdate>()
        val remoteUpdates = mutableListOf<ThreeWayMergedUpdate>()
        val convergedUpdates = mutableListOf<ThreeWayMergedUpdate>()
        val localRemovals = mutableListOf<PasswordIdentifier>()
        val remoteRemovals = mutableListOf<PasswordIdentifier>()
        val unchanged = mutableListOf<PasswordIdentifier>()
        val conflicts = mutableListOf<ThreeWayMergeConflict>()
        val mergedEntries = mutableListOf<PasswordIdentifier>()

        val nicknames = (baseByNickname.keys + localByNickname.keys + remoteByNickname.keys).toSortedSet()
        nicknames.forEach { nickname ->
            val baseEntry = baseByNickname[nickname]
            val localEntry = localByNickname[nickname]
            val remoteEntry = remoteByNickname[nickname]

            when {
                baseEntry == null -> {
                    when {
                        localEntry != null && remoteEntry == null -> {
                            localAdditions += localEntry
                            mergedEntries += localEntry
                        }
                        localEntry == null && remoteEntry != null -> {
                            remoteAdditions += remoteEntry
                            mergedEntries += remoteEntry
                        }
                        localEntry != null && remoteEntry != null -> {
                            if (sameLedgerRepresentation(localEntry, remoteEntry)) {
                                convergedUpdates += ThreeWayMergedUpdate(before = localEntry, after = localEntry)
                                mergedEntries += localEntry
                            } else {
                                conflicts +=
                                    ThreeWayMergeConflict(
                                        nickname = nickname,
                                        baseEntry = null,
                                        localEntry = localEntry,
                                        remoteEntry = remoteEntry,
                                        reason = ThreeWayConflictReason.AddedDifferently,
                                    )
                            }
                        }
                    }
                }

                localEntry == null && remoteEntry == null -> Unit

                localEntry == null && remoteEntry != null -> {
                    if (sameLedgerRepresentation(baseEntry, remoteEntry)) {
                        localRemovals += baseEntry
                    } else {
                        conflicts +=
                            ThreeWayMergeConflict(
                                nickname = nickname,
                                baseEntry = baseEntry,
                                localEntry = null,
                                remoteEntry = remoteEntry,
                                reason = ThreeWayConflictReason.LocalRemovedRemoteChanged,
                            )
                    }
                }

                localEntry != null && remoteEntry == null -> {
                    if (sameLedgerRepresentation(baseEntry, localEntry)) {
                        remoteRemovals += baseEntry
                    } else {
                        conflicts +=
                            ThreeWayMergeConflict(
                                nickname = nickname,
                                baseEntry = baseEntry,
                                localEntry = localEntry,
                                remoteEntry = null,
                                reason = ThreeWayConflictReason.LocalChangedRemoteRemoved,
                            )
                    }
                }

                sameLedgerRepresentation(baseEntry, localEntry) && sameLedgerRepresentation(baseEntry, remoteEntry) -> {
                    unchanged.add(localEntry!!)
                    mergedEntries.add(localEntry)
                }

                !sameLedgerRepresentation(baseEntry, localEntry) && sameLedgerRepresentation(baseEntry, remoteEntry) -> {
                    localUpdates += ThreeWayMergedUpdate(before = baseEntry!!, after = localEntry!!)
                    mergedEntries.add(localEntry)
                }

                sameLedgerRepresentation(baseEntry, localEntry) && !sameLedgerRepresentation(baseEntry, remoteEntry) -> {
                    remoteUpdates += ThreeWayMergedUpdate(before = baseEntry!!, after = remoteEntry!!)
                    mergedEntries.add(remoteEntry)
                }

                sameLedgerRepresentation(localEntry, remoteEntry) -> {
                    convergedUpdates += ThreeWayMergedUpdate(before = baseEntry!!, after = localEntry!!)
                    mergedEntries.add(localEntry)
                }

                else -> {
                    conflicts +=
                        ThreeWayMergeConflict(
                            nickname = nickname,
                            baseEntry = baseEntry,
                            localEntry = localEntry,
                            remoteEntry = remoteEntry,
                            reason = ThreeWayConflictReason.ChangedDifferently,
                        )
                }
            }
        }

        val mergedVault =
            if (conflicts.isEmpty()) {
                Vault(entries = mergedEntries.sortedBy { it.nickname.lowercase() }, source = VaultSource.Local)
            } else {
                null
            }

        return ThreeWayVaultMergePlan(
            localAdditions = localAdditions,
            remoteAdditions = remoteAdditions,
            localUpdates = localUpdates,
            remoteUpdates = remoteUpdates,
            convergedUpdates = convergedUpdates,
            localRemovals = localRemovals,
            remoteRemovals = remoteRemovals,
            unchanged = unchanged,
            conflicts = conflicts,
            mergedVault = mergedVault,
        )
    }
}

private fun sameLedgerRepresentation(
    left: PasswordIdentifier?,
    right: PasswordIdentifier?,
): Boolean =
    when {
        left == null && right == null -> true
        left == null || right == null -> false
        else -> left.sameLedgerRepresentation(right)
    }
