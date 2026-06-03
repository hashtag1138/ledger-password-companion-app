package com.ledgerpasswords.companion.android

import com.ledgerpasswords.companion.core.diff.VaultDiff
import com.ledgerpasswords.companion.core.model.Vault

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
    val deferredLocalReplacementPrompt: DeferredLocalReplacementPrompt? = null,
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
