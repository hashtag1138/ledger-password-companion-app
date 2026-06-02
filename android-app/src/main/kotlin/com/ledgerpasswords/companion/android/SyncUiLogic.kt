package com.ledgerpasswords.companion.android

import com.ledgerpasswords.companion.core.diff.VaultDiff
import com.ledgerpasswords.companion.core.model.Vault

internal data class SyncUiState(
    val status: SyncStatus = SyncStatus.Idle,
    val statusMessage: String = "Branche un Ledger et ouvre l'app Passwords.",
    val deviceName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val storageSize: Int? = null,
    val deviceEntries: Int? = null,
    val diffLines: List<String> = emptyList(),
)

internal data class SyncUpdate(
    val status: SyncStatus,
    val statusMessage: String,
    val appName: String? = null,
    val appVersion: String? = null,
    val storageSize: Int? = null,
    val deviceEntries: Int? = null,
    val diffLines: List<String>? = null,
    val replaceLocalVault: Vault? = null,
    val replaceLocalBackupJsonText: String? = null,
    val clearDeviceEntries: Boolean = false,
    val clearDiffLines: Boolean = false,
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
        diffLines =
            when {
                update.clearDiffLines -> emptyList()
                update.diffLines != null -> update.diffLines
                else -> previous.diffLines
            },
    )

internal fun renderLedgerDiffLines(diff: VaultDiff): List<String> =
    if (!diff.hasChanges) {
        listOf("Aucune différence entre le local et le Ledger.")
    } else {
        buildList {
            diff.added.forEach { entry ->
                add("Local seulement: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            diff.removed.forEach { entry ->
                add("Ledger seulement: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
            }
            diff.changedCharsets.forEach { change ->
                add(
                    "Charsets différents: ${change.after.nickname} " +
                        "[Ledger=${change.before.charsets.toLedgerNames().joinToString(",")}] -> " +
                        "[Local=${change.after.charsets.toLedgerNames().joinToString(",")}]",
                )
            }
        }
    }
