package com.ledgerpasswords.companion.android

import com.ledgerpasswords.companion.android.storage.SyncShadowState
import com.ledgerpasswords.companion.core.diff.VaultDiff
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.sync.ThreeWayMergeConflict
import com.ledgerpasswords.companion.core.sync.ThreeWayMergedUpdate
import com.ledgerpasswords.companion.core.sync.ThreeWayVaultMergePlan
import com.ledgerpasswords.companion.core.sync.VaultMergePlan

internal data class SyncUiState(
    val status: SyncStatus = SyncStatus.Idle,
    val statusMessage: String = "Connect a Ledger and open the Passwords app.",
    val deviceName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val storageSize: Int? = null,
    val deviceEntries: Int? = null,
    val diffSummary: String? = null,
    val diffLines: List<String> = emptyList(),
    val showVerifyCallToAction: Boolean = false,
)

internal data class SyncUpdate(
    val status: SyncStatus,
    val statusMessage: String,
    val appName: String? = null,
    val appVersion: String? = null,
    val storageSize: Int? = null,
    val deviceEntries: Int? = null,
    val diffSummary: String? = null,
    val diffLines: List<String>? = null,
    val replaceLocalVault: Vault? = null,
    val replaceLocalBackupJsonText: String? = null,
    val replaceSyncShadow: SyncShadowState? = null,
    val deferredLocalReplacementPrompt: DeferredLocalReplacementPrompt? = null,
    val deferredSynchronizationPrompt: DeferredSynchronizationPrompt? = null,
    val deferredSynchronizationConflictPrompt: DeferredSynchronizationConflictPrompt? = null,
    val clearDeviceEntries: Boolean = false,
    val clearDiffSummary: Boolean = false,
    val clearDiffLines: Boolean = false,
    val showVerifyCallToAction: Boolean? = null,
)

internal data class DeferredLocalReplacementPrompt(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val cancelMessage: String,
    val successMessage: String,
)

internal data class DeferredSynchronizationPrompt(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val cancelMessage: String,
    val mergedVault: Vault,
)

internal enum class SyncConflictResolutionChoice {
    KeepLocal,
    KeepTarget,
}

internal data class DeferredSynchronizationConflictPrompt(
    val pendingConflicts: List<ThreeWayMergeConflict>,
    val autoMergedEntries: List<PasswordIdentifier>,
    val summary: String,
    val lines: List<String>,
    val chosenEntries: List<PasswordIdentifier> = emptyList(),
    val chosenNotes: List<String> = emptyList(),
    val storageSize: Int,
) {
    val currentConflict: ThreeWayMergeConflict? get() = pendingConflicts.firstOrNull()
    val resolvedCount: Int get() = chosenNotes.size
    val totalConflictCount: Int get() = pendingConflicts.size + chosenNotes.size
}

internal enum class SyncStatus {
    Idle,
    UsbPermissionRequired,
    DeviceConnected,
    WrongAppOpened,
    WaitingForLedgerApproval,
    Dumping,
    Loading,
    Verifying,
    WriteDisabled,
    Success,
    CancelledByUser,
    TransportError,
    ValidationError,
}

internal fun applySyncUpdate(previous: SyncUiState, update: SyncUpdate): SyncUiState =
    previous.copy(
        status = update.status,
        statusMessage = update.statusMessage,
        appName = update.appName ?: previous.appName,
        appVersion = update.appVersion ?: previous.appVersion,
        storageSize = update.storageSize ?: previous.storageSize,
        deviceEntries =
            when {
                update.clearDeviceEntries -> null
                update.deviceEntries != null -> update.deviceEntries
                else -> previous.deviceEntries
            },
        diffSummary =
            when {
                update.clearDiffSummary -> null
                update.diffSummary != null -> update.diffSummary
                else -> previous.diffSummary
            },
        diffLines =
            when {
                update.clearDiffLines -> emptyList()
                update.diffLines != null -> update.diffLines
                else -> previous.diffLines
            },
        showVerifyCallToAction = update.showVerifyCallToAction ?: previous.showVerifyCallToAction,
    )

internal fun SyncStatus.displayLabel(): String =
    when (this) {
        SyncStatus.Idle -> "Idle"
        SyncStatus.UsbPermissionRequired -> "USB permission required"
        SyncStatus.DeviceConnected -> "Target ready"
        SyncStatus.WrongAppOpened -> "Wrong app"
        SyncStatus.WaitingForLedgerApproval -> "Waiting for Ledger approval"
        SyncStatus.Dumping -> "Reading"
        SyncStatus.Loading -> "Writing"
        SyncStatus.Verifying -> "Verifying"
        SyncStatus.WriteDisabled -> "Writing disabled"
        SyncStatus.Success -> "Done"
        SyncStatus.CancelledByUser -> "Cancelled"
        SyncStatus.TransportError -> "Transport error"
        SyncStatus.ValidationError -> "Safety block"
    }

internal fun renderLedgerDiffSummary(diff: VaultDiff): String =
    if (!diff.hasChanges) {
        "No difference between local and Ledger."
    } else {
        buildList {
            if (diff.added.isNotEmpty()) add("${diff.added.size} addition${if (diff.added.size > 1) "s" else ""}")
            if (diff.removed.isNotEmpty()) add("${diff.removed.size} removal${if (diff.removed.size > 1) "s" else ""}")
            if (diff.changedCharsets.isNotEmpty()) add("${diff.changedCharsets.size} charset change${if (diff.changedCharsets.size > 1) "s" else ""}")
        }.joinToString(" • ")
    }

internal fun renderLedgerDiffLines(diff: VaultDiff): List<String> =
    if (!diff.hasChanges) {
        listOf("No difference between local and Ledger.")
    } else {
        buildList {
            diff.added.forEach { entry ->
                add("Local only: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            diff.removed.forEach { entry ->
                add("Ledger only: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            diff.changedCharsets.forEach { change ->
                add(
                    "Different charsets: ${change.after.nickname} " +
                        "[Ledger=${change.before.charsets.toLedgerNames().joinToString(",")}] -> " +
                        "[Local=${change.after.charsets.toLedgerNames().joinToString(",")}]",
                )
            }
        }
    }

internal fun renderSynchronizationSummary(plan: VaultMergePlan): String =
    if (!plan.hasChanges) {
        "Already synchronized."
    } else {
        buildList {
            if (plan.localOnly.isNotEmpty()) add("${plan.localOnly.size} local-only")
            if (plan.remoteOnly.isNotEmpty()) add("${plan.remoteOnly.size} target-only")
            if (plan.identical.isNotEmpty()) add("${plan.identical.size} unchanged")
            if (plan.conflicts.isNotEmpty()) add("${plan.conflicts.size} conflict${if (plan.conflicts.size > 1) "s" else ""}")
        }.joinToString(" • ")
    }

internal fun renderSynchronizationLines(plan: VaultMergePlan): List<String> =
    if (!plan.hasChanges) {
        listOf("No change required. Local and target already match.")
    } else {
        buildList {
            plan.localOnly.forEach { entry ->
                add("Keep local only: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            plan.remoteOnly.forEach { entry ->
                add("Import from target: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            plan.conflicts.forEach { conflict ->
                add(
                    "Conflict: ${conflict.nickname} " +
                        "[Local=${conflict.localEntry.charsets.toLedgerNames().joinToString(",")}] -> " +
                        "[Target=${conflict.remoteEntry.charsets.toLedgerNames().joinToString(",")}]",
                )
            }
        }
    }

internal fun renderThreeWaySynchronizationSummary(plan: ThreeWayVaultMergePlan): String =
    if (!plan.hasChanges) {
        "Already synchronized."
    } else {
        buildList {
            if (plan.localAdditions.isNotEmpty()) add("${plan.localAdditions.size} local addition${if (plan.localAdditions.size > 1) "s" else ""}")
            if (plan.remoteAdditions.isNotEmpty()) add("${plan.remoteAdditions.size} target addition${if (plan.remoteAdditions.size > 1) "s" else ""}")
            if (plan.localUpdates.isNotEmpty()) add("${plan.localUpdates.size} local update${if (plan.localUpdates.size > 1) "s" else ""}")
            if (plan.remoteUpdates.isNotEmpty()) add("${plan.remoteUpdates.size} target update${if (plan.remoteUpdates.size > 1) "s" else ""}")
            if (plan.convergedUpdates.isNotEmpty()) add("${plan.convergedUpdates.size} converged update${if (plan.convergedUpdates.size > 1) "s" else ""}")
            if (plan.localRemovals.isNotEmpty()) add("${plan.localRemovals.size} local removal${if (plan.localRemovals.size > 1) "s" else ""}")
            if (plan.remoteRemovals.isNotEmpty()) add("${plan.remoteRemovals.size} target removal${if (plan.remoteRemovals.size > 1) "s" else ""}")
            if (plan.conflicts.isNotEmpty()) add("${plan.conflicts.size} conflict${if (plan.conflicts.size > 1) "s" else ""}")
        }.joinToString(" • ")
    }

internal fun renderThreeWaySynchronizationLines(plan: ThreeWayVaultMergePlan): List<String> =
    if (!plan.hasChanges) {
        listOf("No change required. Local and target already match the sync shadow.")
    } else {
        buildList {
            plan.localAdditions.forEach { entry ->
                add("Keep local addition: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            plan.remoteAdditions.forEach { entry ->
                add("Import target addition: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            plan.localUpdates.forEach { update ->
                add(renderThreeWayUpdateLine("Keep local update", update))
            }
            plan.remoteUpdates.forEach { update ->
                add(renderThreeWayUpdateLine("Import target update", update))
            }
            plan.convergedUpdates.forEach { update ->
                add(renderThreeWayUpdateLine("Converged update", update))
            }
            plan.localRemovals.forEach { entry ->
                add("Keep local removal: ${entry.nickname}")
            }
            plan.remoteRemovals.forEach { entry ->
                add("Keep target removal: ${entry.nickname}")
            }
            plan.conflicts.forEach { conflict ->
                add(renderThreeWayConflictLine(conflict))
            }
        }
    }

internal fun renderResolvedSynchronizationSummary(prompt: DeferredSynchronizationConflictPrompt): String =
    buildString {
        append("Manual resolution complete")
        if (prompt.chosenNotes.isNotEmpty()) {
            append(" • ")
            append(prompt.chosenNotes.size)
            append(" conflict")
            if (prompt.chosenNotes.size > 1) append('s')
            append(" resolved")
        }
    }

internal fun renderResolvedSynchronizationLines(prompt: DeferredSynchronizationConflictPrompt): List<String> =
    prompt.lines.filterNot { it.startsWith("Conflict: ") } + prompt.chosenNotes

internal fun renderThreeWayConflictResolutionLine(
    conflict: ThreeWayMergeConflict,
    choice: SyncConflictResolutionChoice,
): String =
    buildString {
        append("Resolved: ${conflict.nickname} -> ")
        when (choice) {
            SyncConflictResolutionChoice.KeepLocal -> {
                append(
                    when (val entry = conflict.localEntry) {
                        null -> "keep local removal"
                        else -> "keep local [${entry.charsets.toLedgerNames().joinToString(",")}]"
                    },
                )
            }

            SyncConflictResolutionChoice.KeepTarget -> {
                append(
                    when (val entry = conflict.remoteEntry) {
                        null -> "keep target removal"
                        else -> "keep target [${entry.charsets.toLedgerNames().joinToString(",")}]"
                    },
                )
            }
        }
    }

private fun renderThreeWayUpdateLine(
    label: String,
    update: ThreeWayMergedUpdate,
): String =
    "$label: ${update.after.nickname} " +
        "[Before=${update.before.charsets.toLedgerNames().joinToString(",")}] -> " +
        "[After=${update.after.charsets.toLedgerNames().joinToString(",")}]"

private fun renderThreeWayConflictLine(conflict: ThreeWayMergeConflict): String =
    buildString {
        append("Conflict: ${conflict.nickname} ")
        val localEntry = conflict.localEntry
        append(
            when {
                localEntry == null -> "[Local=removed]"
                else -> "[Local=${localEntry.charsets.toLedgerNames().joinToString(",")}]"
            },
        )
        append(" vs ")
        val remoteEntry = conflict.remoteEntry
        append(
            when {
                remoteEntry == null -> "[Target=removed]"
                else -> "[Target=${remoteEntry.charsets.toLedgerNames().joinToString(",")}]"
            },
        )
    }
