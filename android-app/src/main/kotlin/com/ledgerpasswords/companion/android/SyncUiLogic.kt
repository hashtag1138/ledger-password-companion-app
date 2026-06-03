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

internal fun SyncStatus.frenchLabel(): String =
    when (this) {
        SyncStatus.Idle -> "En attente"
        SyncStatus.UsbPermissionRequired -> "Permission USB requise"
        SyncStatus.DeviceConnected -> "Cible prête"
        SyncStatus.WrongAppOpened -> "Mauvaise app"
        SyncStatus.WaitingForLedgerApproval -> "Validation sur Ledger"
        SyncStatus.Dumping -> "Lecture en cours"
        SyncStatus.Loading -> "Écriture en cours"
        SyncStatus.Verifying -> "Vérification en cours"
        SyncStatus.WriteDisabled -> "Écriture désactivée"
        SyncStatus.Success -> "Terminé"
        SyncStatus.CancelledByUser -> "Annulé"
        SyncStatus.TransportError -> "Erreur de transport"
        SyncStatus.ValidationError -> "Blocage de sécurité"
    }

internal fun renderLedgerDiffSummary(diff: VaultDiff): String =
    if (!diff.hasChanges) {
        "Aucune différence entre le local et le Ledger."
    } else {
        buildList {
            if (diff.added.isNotEmpty()) add("${diff.added.size} ajout${if (diff.added.size > 1) "s" else ""}")
            if (diff.removed.isNotEmpty()) add("${diff.removed.size} suppression${if (diff.removed.size > 1) "s" else ""}")
            if (diff.changedCharsets.isNotEmpty()) add("${diff.changedCharsets.size} modification${if (diff.changedCharsets.size > 1) "s" else ""}")
        }.joinToString(" • ")
    }

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
