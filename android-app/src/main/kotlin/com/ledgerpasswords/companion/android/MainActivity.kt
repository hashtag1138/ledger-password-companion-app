package com.ledgerpasswords.companion.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ledgerpasswords.companion.android.storage.DiagnosticLogStore
import com.ledgerpasswords.companion.android.storage.LocalVaultStore
import com.ledgerpasswords.companion.android.storage.SyncShadowState
import com.ledgerpasswords.companion.android.storage.SyncShadowStore
import com.ledgerpasswords.companion.android.storage.SyncTargetKind
import com.ledgerpasswords.companion.android.storage.UiPreferences
import com.ledgerpasswords.companion.android.storage.UiPreferencesStore
import com.ledgerpasswords.companion.android.usb.AndroidUsbLedgerTransport
import com.ledgerpasswords.companion.core.LedgerAppCompatibility
import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.capacitySnapshot
import com.ledgerpasswords.companion.core.diff.VaultDiffer
import com.ledgerpasswords.companion.core.edit.VaultEditor
import com.ledgerpasswords.companion.core.model.CharsetFlag
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.core.risk.LedgerPushRiskAssessment
import com.ledgerpasswords.companion.core.risk.LedgerPushRiskPolicy
import com.ledgerpasswords.companion.core.risk.PushRiskDecision
import com.ledgerpasswords.companion.core.risk.PushRiskFinding
import com.ledgerpasswords.companion.core.risk.PushRiskSeverity
import com.ledgerpasswords.companion.core.risk.PushSafetyMode
import com.ledgerpasswords.companion.core.sync.ThreeWayVaultMergePlan
import com.ledgerpasswords.companion.core.sync.ThreeWayVaultMergePlanner
import com.ledgerpasswords.companion.core.sync.VaultMergePlanner
import com.ledgerpasswords.companion.core.validation.ValidationResult
import com.ledgerpasswords.companion.core.validation.VaultValidator
import com.ledgerpasswords.companion.ledger.backup.BackupInspection
import com.ledgerpasswords.companion.ledger.backup.BackupApp
import com.ledgerpasswords.companion.ledger.backup.BackupJsonCodec
import com.ledgerpasswords.companion.ledger.client.LedgerPasswordsClient
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import com.ledgerpasswords.companion.ledger.transport.LedgerTransport
import com.ledgerpasswords.companion.ledger.transport.SpeculosTransport
import java.io.File
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val localVaultStore by lazy { LocalVaultStore(File(filesDir, LOCAL_VAULT_FILE_NAME)) }
    private val syncShadowStore by lazy { SyncShadowStore(File(filesDir, SYNC_SHADOW_FILE_NAME)) }
    private val uiPreferencesStore by lazy { UiPreferencesStore(File(filesDir, UI_PREFERENCES_FILE_NAME)) }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val backupJsonCodec = BackupJsonCodec()
    private val metadataCodec = MetadataCodec()
    private val differ = VaultDiffer()
    private val mergePlanner = VaultMergePlanner()
    private val threeWayMergePlanner = ThreeWayVaultMergePlanner()

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                localVaultMessage = "Import backup.json cancelled."
            } else {
                importBackupFromUri(uri)
            }
        }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) {
                localVaultMessage = "Export backup.json cancelled."
            } else {
                exportBackupToUri(uri)
            }
        }

    private var localVault by mutableStateOf(Vault(source = VaultSource.Local))
    private var localVaultMessage by mutableStateOf("Loading local storage...")
    private var localBackupJsonText by mutableStateOf<String?>(null)
    private var showSyncScreen by mutableStateOf(false)
    private var showSettingsScreen by mutableStateOf(false)
    private var showAboutScreen by mutableStateOf(false)
    private var showDebugScreen by mutableStateOf(false)
    private var entryEditorState by mutableStateOf<EntryEditorState?>(null)
    private var syncUiState by mutableStateOf(SyncUiState())
    private var selectedTransportMode by mutableStateOf(defaultTransportMode())
    private var speculosHost by mutableStateOf(defaultSpeculosHost())
    private var speculosPortText by mutableStateOf(DEFAULT_SPECULOS_PORT.toString())
    private var uiPreferences by mutableStateOf(UiPreferences())
    private var appDialogState by mutableStateOf<AppDialogState?>(null)
    private var hardwareDangerousOverrideEnabled by mutableStateOf(false)
    private var syncShadowState by mutableStateOf<SyncShadowState?>(null)

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                DiagnosticLogStore.info(
                    TAG,
                    "usbReceiver action=${intent.action} device=${intent.usbDeviceOrNull()?.deviceName}",
                )
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val device = intent.usbDeviceOrNull() ?: return
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            refreshUsbState(preferredDevice = device)
                        } else {
                            DiagnosticLogStore.info(TAG, "usb permission denied device=${device.deviceName}")
                            updateSyncState {
                                copy(
                                    status = SyncStatus.CancelledByUser,
                                    statusMessage = "USB permission denied for ${device.productName ?: device.deviceName}",
                                )
                            }
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (selectedTransportMode == SyncTransportMode.Usb) {
                            refreshUsbState(preferredDevice = intent.usbDeviceOrNull(), autoRequestPermission = showSyncScreen)
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (selectedTransportMode != SyncTransportMode.Usb) return
                        val detached = intent.usbDeviceOrNull()
                        val currentDevice = syncUiState.deviceName
                        DiagnosticLogStore.info(
                            TAG,
                            "usb detached detached=${detached?.deviceName} current=$currentDevice",
                        )
                        if (detached == null || detached.deviceName == currentDevice) {
                            syncUiState =
                                syncUiState.copy(
                                    status = SyncStatus.Idle,
                                    statusMessage = "Ledger disconnected. Reconnect the device and open the Passwords app.",
                                    deviceName = null,
                                    appName = null,
                                    appVersion = null,
                                    storageSize = null,
                                    deviceEntries = null,
                                    diffSummary = null,
                                    diffLines = emptyList(),
                                    showVerifyCallToAction = false,
                                )
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogStore.initialize(this)
        DiagnosticLogStore.mark("MainActivity.onCreate")
        loadLocalVault()
        loadSyncShadow()
        loadUiPreferences()
        registerUsbReceiver()
        refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull())
        if (uiPreferences.showExperimentalWarningOnLaunch) {
            appDialogState = AppDialogState.StartupWarning()
        }
        setContent {
            LedgerPasswordsCompanionShell(
                localVault = localVault,
                localVaultMessage = localVaultMessage,
                showSyncScreen = showSyncScreen,
                showSettingsScreen = showSettingsScreen,
                showAboutScreen = showAboutScreen,
                showDebugScreen = showDebugScreen,
                entryEditorState = entryEditorState,
                syncUiState = syncUiState,
                transportMode = selectedTransportMode,
                speculosHost = speculosHost,
                speculosPortText = speculosPortText,
                hardwarePushConfirmationEnabled = uiPreferences.confirmHardwarePush,
                hardwareDangerousOverrideEnabled = hardwareDangerousOverrideEnabled,
                onAddEntry = { openCreateEntry() },
                onEditEntry = { openEditEntry(it) },
                onDismissEditor = { closeEditor() },
                onOpenSettings = { openSettingsScreen() },
                onOpenAbout = { openAboutScreen() },
                onOpenDebug = { openDebugScreen() },
                onBackToHome = { openHomeScreen() },
                onEditorNicknameChanged = { nickname ->
                    updateEntryEditor {
                        coerceNicknameDraft(nickname)
                    }
                },
                onEditorCharsetToggled = { flag, checked ->
                    updateEntryEditor {
                        val nextFlags = selectedFlags.toMutableSet()
                        if (checked) {
                            nextFlags += flag
                        } else {
                            nextFlags -= flag
                        }
                        copy(selectedFlags = nextFlags.toSet(), errorMessage = null)
                    }
                },
                onHardwarePushConfirmationChanged = { enabled ->
                    updateUiPreferences { copy(confirmHardwarePush = enabled) }
                },
                onHardwareDangerousOverrideChanged = { enabled -> hardwareDangerousOverrideEnabled = enabled },
                onSaveEntry = { saveEntry() },
                onDeleteEntry = { requestDeleteEntryConfirmation() },
                onImportBackup = { launchImportBackup() },
                onExportBackup = { launchExportBackup() },
                onOpenSync = { openSyncScreen() },
                onTransportModeChanged = { mode ->
                    selectedTransportMode = mode
                    dismissAppDialog()
                    syncUiState =
                        SyncUiState(
                            statusMessage =
                                when (mode) {
                                    SyncTransportMode.Usb -> "Connect a Ledger and open the Passwords app."
                                    SyncTransportMode.Speculos -> "Configure Speculos then refresh the target."
                                },
                        )
                    refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull(), autoRequestPermission = false)
                },
                onSpeculosHostChanged = { host -> speculosHost = host },
                onSpeculosPortChanged = { port -> speculosPortText = port },
                onRequestPermission = { requestUsbPermission() },
                onRefreshDevice = { refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull()) },
                onSynchronize = { synchronizeVaults() },
                onPullFromLedger = { pullFromLedger() },
                onCompareWithLedger = { compareWithLedger() },
                onPushToLedger = {
                    if (selectedTransportMode == SyncTransportMode.Usb && uiPreferences.confirmHardwarePush) {
                        appDialogState = AppDialogState.PushConfirmation(buildPushConfirmationDialogState())
                    } else {
                        pushToLedger()
                    }
                },
                onVerifyLedger = { verifyLedger() },
                startupWarningEnabled = uiPreferences.showExperimentalWarningOnLaunch,
                onStartupWarningEnabledChanged = { enabled ->
                    updateUiPreferences { copy(showExperimentalWarningOnLaunch = enabled) }
                },
                appDialogState = appDialogState,
                onDismissAppDialog = { dismissAppDialog() },
                onConfirmAppDialog = { confirmAppDialog() },
                onResolveSynchronizationConflictChoice = { choice ->
                    resolveSynchronizationConflict(choice)
                },
                onStartupWarningDismissPreferenceChanged = { checked ->
                    updateStartupWarningDialogPreference(checked)
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DiagnosticLogStore.info(TAG, "onNewIntent action=${intent.action} device=${intent.usbDeviceOrNull()?.deviceName}")
        setIntent(intent)
        refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull(), autoRequestPermission = showSyncScreen)
    }

    override fun onDestroy() {
        DiagnosticLogStore.mark("MainActivity.onDestroy")
        unregisterReceiver(usbReceiver)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun loadLocalVault() {
        val result = localVaultStore.load()
        result.vault?.let { localVault = it }
        localBackupJsonText = result.backupJsonText
        localVaultMessage = result.message
    }

    private fun loadUiPreferences() {
        uiPreferences = uiPreferencesStore.load()
    }

    private fun loadSyncShadow() {
        syncShadowState = syncShadowStore.load()
    }

    private fun updateUiPreferences(transform: UiPreferences.() -> UiPreferences) {
        uiPreferences = uiPreferencesStore.save(uiPreferences.transform())
    }

    private fun openHomeScreen() {
        dismissAppDialog()
        showSyncScreen = false
        showSettingsScreen = false
        showAboutScreen = false
        showDebugScreen = false
        entryEditorState = null
    }

    private fun openCreateEntry() {
        showSyncScreen = false
        showSettingsScreen = false
        showAboutScreen = false
        showDebugScreen = false
        entryEditorState = EntryEditorState()
    }

    private fun openEditEntry(entry: PasswordIdentifier) {
        showSyncScreen = false
        showSettingsScreen = false
        showAboutScreen = false
        showDebugScreen = false
        entryEditorState = EntryEditorState.fromEntry(entry)
    }

    private fun closeEditor() {
        entryEditorState = null
    }

    private fun openSyncScreen() {
        entryEditorState = null
        showSettingsScreen = false
        showAboutScreen = false
        showDebugScreen = false
        showSyncScreen = true
        dismissAppDialog()
        refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull(), autoRequestPermission = false)
    }

    private fun openSettingsScreen() {
        entryEditorState = null
        showSyncScreen = false
        showAboutScreen = false
        showDebugScreen = false
        dismissAppDialog()
        showSettingsScreen = true
    }

    private fun openAboutScreen() {
        entryEditorState = null
        showSyncScreen = false
        showSettingsScreen = false
        showDebugScreen = false
        dismissAppDialog()
        showAboutScreen = true
    }

    private fun openDebugScreen() {
        entryEditorState = null
        showSyncScreen = false
        showSettingsScreen = false
        showAboutScreen = false
        dismissAppDialog()
        showDebugScreen = true
    }

    private fun effectiveStorageSize(): Int = syncUiState.storageSize ?: LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE

    private fun validatorFor(storageSize: Int = effectiveStorageSize()): VaultValidator = VaultValidator(storageSize)

    private fun editorFor(storageSize: Int = effectiveStorageSize()): VaultEditor = VaultEditor(validatorFor(storageSize))

    private fun inspectLocalBackupOrFindings(): Pair<BackupInspection?, MutableList<PushRiskFinding>> {
        val text = localBackupJsonText ?: return null to mutableListOf()
        return runCatching { backupJsonCodec.inspect(text) }
            .fold(
                onSuccess = { inspection -> inspection to inspection.findings.toMutableList() },
                onFailure = { error ->
                    null to
                        mutableListOf(
                            PushRiskFinding(
                                severity = PushRiskSeverity.Block,
                                code = "local_backup_unreadable",
                                message =
                                    "The local raw backup could not be re-read: ${error.message ?: error::class.java.simpleName}.",
                            ),
                        )
                },
            )
    }

    private fun assessLocalPushRisk(
        vault: Vault,
        storageSize: Int,
        mode: PushSafetyMode,
    ): LedgerPushRiskAssessment {
        val (inspection, findings) = inspectLocalBackupOrFindings()
        val normalizedVault = vault.copy(source = VaultSource.Local).sortedByNickname()
        if (inspection != null && inspection.preferredVault.entries != normalizedVault.entries) {
            val severity = if (mode == PushSafetyMode.HardwareSafe) PushRiskSeverity.Block else PushRiskSeverity.Warning
            findings += PushRiskFinding(
                severity = severity,
                code = "local_state_backup_mismatch",
                message = "The displayed local state does not exactly match the preserved raw backup.",
            )
        }
        return LedgerPushRiskPolicy(storageSize).assess(normalizedVault, mode, findings)
    }

    private fun assessMergedPushRisk(
        vault: Vault,
        storageSize: Int,
        mode: PushSafetyMode,
    ): LedgerPushRiskAssessment = LedgerPushRiskPolicy(storageSize).assess(vault.copy(source = VaultSource.Local).sortedByNickname(), mode)

    private fun buildSynchronizationPrompt(
        target: SyncTarget,
        summary: String,
        mergedVault: Vault,
        assessment: LedgerPushRiskAssessment,
    ): DeferredSynchronizationPrompt {
        val dangerousHardwareWrite =
            target is SyncTarget.Usb &&
                assessment.decision == PushRiskDecision.Block &&
                hardwareDangerousOverrideEnabled
        val title = if (dangerousHardwareWrite) "Confirm dangerous synchronization" else "Confirm synchronization"
        val confirmLabel = if (dangerousHardwareWrite) "Force sync" else "Synchronize"
        val body =
            buildString {
                append("Merged result: ${mergedVault.entries.size} identifier")
                if (mergedVault.entries.size > 1) append('s')
                append(". ")
                append(summary)
                append("\n\n")
                append(
                    target.userActionMessage(
                        "The companion will write the merged vault to the Ledger, verify the result, then replace the local vault on the phone.",
                        "The companion will write the merged vault to Speculos, verify the result, then replace the local vault on the phone.",
                    ),
                )
                if (assessment.decision == PushRiskDecision.Warn || dangerousHardwareWrite) {
                    append("\n\n")
                    append(assessment.summaryLines().joinToString(separator = "\n"))
                    if (dangerousHardwareWrite) {
                        append("\n\nThe dangerous override is enabled in Debug, so the hardware-safe block can be bypassed for this write.")
                    }
                }
            }
        return DeferredSynchronizationPrompt(
            title = title,
            body = body,
            confirmLabel = confirmLabel,
            cancelMessage = "Synchronization cancelled. No write was performed.",
            mergedVault = mergedVault,
        )
    }

    private fun matchingSyncShadow(
        target: SyncTarget,
        storageSize: Int,
    ): SyncShadowState? {
        val shadow = syncShadowState ?: return null
        if (shadow.targetKind != syncTargetKind(target) || shadow.storageSize != storageSize) {
            return null
        }
        return when (target) {
            is SyncTarget.Usb -> shadow
            is SyncTarget.Speculos ->
                shadow.takeIf { it.targetDescriptor == syncTargetDescriptor(target) }
        }
    }

    private fun buildSyncShadowState(
        target: SyncTarget,
        vault: Vault,
        storageSize: Int,
    ): SyncShadowState =
        SyncShadowState(
            lastSyncedVault = vault.copy(source = VaultSource.Local).sortedByNickname(),
            targetKind = syncTargetKind(target),
            targetDescriptor = syncTargetDescriptor(target),
            storageSize = storageSize,
            updatedAtEpochMillis = Instant.now().toEpochMilli(),
        )

    private fun syncTargetKind(target: SyncTarget): SyncTargetKind =
        when (target) {
            is SyncTarget.Usb -> SyncTargetKind.Usb
            is SyncTarget.Speculos -> SyncTargetKind.Speculos
        }

    private fun syncTargetDescriptor(target: SyncTarget): String? =
        when (target) {
            is SyncTarget.Usb -> null
            is SyncTarget.Speculos -> "${target.host}:${target.port}"
        }

    private fun buildPushConfirmationDialogState(): PushConfirmationDialogState {
        val assessment = assessLocalPushRisk(localVault, effectiveStorageSize(), PushSafetyMode.HardwareSafe)
        val baseIntro =
            "This action writes to a real Ledger. No automatic readback will run after writing."
        val diffContext =
            syncUiState.diffSummary?.let { "Last known diff: $it." }
                ?: "No recent diff shown. Run \"Compare local with target\" if you want a precise preview of the changes."
        return when (assessment.decision) {
            PushRiskDecision.Allow ->
                PushConfirmationDialogState(
                    title = "Confirm hardware push",
                    body = "$baseIntro\n\n$diffContext\n\nNo additional risk detected by the companion policy.",
                    canConfirm = true,
                )
            PushRiskDecision.Warn ->
                PushConfirmationDialogState(
                    title = "Confirm hardware push",
                    body = "$baseIntro\n\n$diffContext\n\n${assessment.summaryLines().joinToString(separator = "\n")}",
                    canConfirm = true,
                )
            PushRiskDecision.Block ->
                PushConfirmationDialogState(
                    title = if (hardwareDangerousOverrideEnabled) "Dangerous override enabled" else "Hardware push blocked",
                    body =
                        buildString {
                            append(baseIntro)
                            append("\n\n")
                            append(diffContext)
                            append("\n\n")
                            append(assessment.summaryLines().joinToString(separator = "\n"))
                            append("\n\n")
                            if (hardwareDangerousOverrideEnabled) {
                                append("The dangerous override is enabled in Debug. You can force this push despite these blocks.")
                            } else {
                                append("Real push is blocked by the hardware-safe policy. Open Debug to enable the dangerous override if you really want to force the write.")
                            }
                        },
                    canConfirm = hardwareDangerousOverrideEnabled,
                    confirmLabel = "Force push",
                )
        }
    }

    private fun requestDeleteEntryConfirmation() {
        val nickname = entryEditorState?.originalNickname ?: return
        appDialogState = AppDialogState.DeleteEntry(nickname)
    }

    private fun dismissAppDialog() {
        when (val dialog = appDialogState) {
            is AppDialogState.ConfirmBackupImport -> {
                localVaultMessage = "Import of ${dialog.fileName} cancelled. The local vault was not modified."
            }

            is AppDialogState.ConfirmLedgerImport -> {
                syncUiState =
                    syncUiState.copy(
                        status = SyncStatus.CancelledByUser,
                        statusMessage = dialog.cancelMessage,
                    )
            }

            is AppDialogState.ConfirmSynchronization -> {
                syncUiState =
                    syncUiState.copy(
                        status = SyncStatus.CancelledByUser,
                        statusMessage = dialog.cancelMessage,
                    )
            }

            is AppDialogState.ResolveSynchronizationConflict -> {
                syncUiState =
                    syncUiState.copy(
                        status = SyncStatus.CancelledByUser,
                        statusMessage = "Synchronization cancelled during conflict resolution. No write was performed.",
                        showVerifyCallToAction = false,
                    )
            }

            else -> Unit
        }
        appDialogState = null
    }

    private fun confirmAppDialog() {
        when (val dialog = appDialogState) {
            is AppDialogState.StartupWarning -> {
                if (dialog.dontShowAgain) {
                    updateUiPreferences { copy(showExperimentalWarningOnLaunch = false) }
                }
                appDialogState = null
            }

            is AppDialogState.DeleteEntry -> {
                appDialogState = null
                deleteEntry()
            }

            is AppDialogState.PushConfirmation -> {
                appDialogState = null
                pushToLedger()
            }

            is AppDialogState.ConfirmBackupImport -> {
                appDialogState = null
                applyBackupImport(dialog)
            }

            is AppDialogState.ConfirmLedgerImport -> {
                appDialogState = null
                val persistence =
                    applyLocalVault(
                        vault = dialog.importedVault,
                        successMessage = dialog.successMessage,
                        backupJsonText = dialog.backupJsonText,
                    )
                syncUiState =
                    syncUiState.copy(
                        status = SyncStatus.Success,
                        statusMessage =
                            if (persistence.persisted) {
                                dialog.confirmedStatusMessage
                            } else {
                                "${dialog.confirmedStatusMessage} The local replacement could not be persisted."
                            },
                    )
            }

            is AppDialogState.ConfirmSynchronization -> {
                appDialogState = null
                executeSynchronization(dialog)
            }

            is AppDialogState.ResolveSynchronizationConflict -> Unit

            null -> Unit
        }
    }

    private fun updateStartupWarningDialogPreference(checked: Boolean) {
        val dialog = appDialogState
        if (dialog is AppDialogState.StartupWarning) {
            appDialogState = dialog.copy(dontShowAgain = checked)
        }
    }

    private fun applyBackupImport(dialog: AppDialogState.ConfirmBackupImport) {
        val suffix =
            if (dialog.hasRiskFindings) {
                " Some hardware writes will be blocked until this backup has been normalized."
            } else {
                ""
            }
        applyLocalVault(
            vault = dialog.importedVault,
            successMessage = "Local vault imported from ${dialog.fileName}.$suffix",
            backupJsonText = dialog.backupJsonText,
        )
    }

    private fun updateEntryEditor(transform: EntryEditorState.() -> EntryEditorState) {
        entryEditorState = entryEditorState?.transform()
    }

    private fun EntryEditorState.coerceNicknameDraft(candidate: String): EntryEditorState {
        val byteCount = candidate.trim().toByteArray(Charsets.UTF_8).size
        val error =
            if (byteCount > LedgerPasswordsLimits.MAX_NICKNAME_BYTES) {
                "Nickname exceeds ${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} UTF-8 bytes."
            } else {
                null
            }
        return copy(
            nickname = candidate,
            errorMessage = error,
        )
    }

    private fun saveEntry() {
        val draft = entryEditorState ?: return
        val nickname = draft.nickname.trim()
        if (nickname.isBlank()) {
            updateEntryEditor { copy(errorMessage = "Nickname must not be blank.") }
            return
        }
        if (draft.nicknameByteCount > LedgerPasswordsLimits.MAX_NICKNAME_BYTES) {
            updateEntryEditor {
                copy(errorMessage = "Nickname exceeds ${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} UTF-8 bytes.")
            }
            return
        }
        if (draft.selectedFlags.isEmpty()) {
            updateEntryEditor { copy(errorMessage = "Select at least one charset.") }
            return
        }

        val entry = PasswordIdentifier(nickname = nickname, charsets = draft.selectedFlags.toCharsetPolicy())
        val nextVaultResult =
            runCatching {
                if (draft.originalNickname == null) {
                    editorFor().add(localVault, entry)
                } else {
                    val nextEntries =
                        localVault.entries.map { current ->
                            if (current.nickname == draft.originalNickname) entry else current
                        }
                    editorFor().replace(localVault, nextEntries)
                }
            }

        nextVaultResult
            .onSuccess { nextVault ->
                applyLocalVault(
                    vault = nextVault,
                    successMessage =
                        when {
                            draft.originalNickname == null -> "Identifier added and saved locally."
                            draft.originalNickname == nickname -> "Identifier updated and saved locally."
                            else -> "Identifier renamed and saved locally."
                        },
                )
                entryEditorState = null
            }
            .onFailure { error ->
                updateEntryEditor {
                    copy(errorMessage = error.message ?: "Unable to save this identifier.")
                }
            }
    }

    private fun deleteEntry() {
        val originalNickname = entryEditorState?.originalNickname ?: return
        runCatching { editorFor().delete(localVault, originalNickname) }
            .onSuccess { nextVault ->
                applyLocalVault(
                    vault = nextVault,
                    successMessage = "Identifier deleted and local storage updated.",
                )
                entryEditorState = null
            }
            .onFailure { error ->
                updateEntryEditor {
                    copy(errorMessage = error.message ?: "Unable to delete this identifier.")
                }
            }
    }

    private fun launchImportBackup() {
        entryEditorState = null
        showSyncScreen = false
        showSettingsScreen = false
        showAboutScreen = false
        showDebugScreen = false
        importBackupLauncher.launch(arrayOf("application/json", "text/*"))
    }

    private fun launchExportBackup() {
        entryEditorState = null
        showSyncScreen = false
        showSettingsScreen = false
        showAboutScreen = false
        showDebugScreen = false
        exportBackupLauncher.launch(DEFAULT_BACKUP_FILE_NAME)
    }

    private fun importBackupFromUri(uri: Uri) {
        val fileName = displayNameFor(uri) ?: "backup.json"
        runCatching {
            val text =
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Unable to read $fileName.")
            val inspection = backupJsonCodec.inspect(text)
            val importedVault = inspection.preferredVault.copy(source = VaultSource.Local).sortedByNickname()
            val validation = validatorFor().validate(importedVault)
            validation.throwIfInvalid()
            if (localVault.entries.isNotEmpty() && importedVault.entries != localVault.entries) {
                appDialogState =
                    AppDialogState.ConfirmBackupImport(
                        fileName = fileName,
                        importedVault = importedVault,
                        backupJsonText = text,
                        hasRiskFindings = inspection.findings.isNotEmpty(),
                    )
                localVaultMessage = "Import of $fileName is ready. Confirm replacement of the local vault."
            } else {
                applyBackupImport(
                    AppDialogState.ConfirmBackupImport(
                        fileName = fileName,
                        importedVault = importedVault,
                        backupJsonText = text,
                        hasRiskFindings = inspection.findings.isNotEmpty(),
                    ),
                )
            }
        }.onFailure { error ->
            localVaultMessage = error.message ?: "Unable to import backup.json."
        }
    }

    private fun exportBackupToUri(uri: Uri) {
        val validation = validatorFor().validate(localVault)
        if (!validation.isValid) {
            localVaultMessage = validation.toUserMessage()
            return
        }

        val fileName = displayNameFor(uri) ?: DEFAULT_BACKUP_FILE_NAME
        runCatching {
            val json = localBackupJsonText ?: backupJsonCodec.toJson(localVault.copy(source = VaultSource.Local))
            val output =
                contentResolver.openOutputStream(uri)
                    ?: error("Unable to open $fileName for writing.")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            localVaultMessage = "Backup exported to $fileName."
        }.onFailure { error ->
            localVaultMessage = error.message ?: "Unable to export backup.json."
        }
    }

    private fun applyLocalVault(vault: Vault, successMessage: String, backupJsonText: String? = null): LocalPersistenceResult {
        val normalized = vault.copy(source = VaultSource.Local).sortedByNickname()
        localVault = normalized
        val persistedJson =
            runCatching {
                if (backupJsonText != null) {
                    localVaultStore.saveBackupJson(backupJsonText)
                } else {
                    localVaultStore.saveVault(normalized)
                }
            }.getOrNull()
        val persisted = persistedJson != null
        if (persistedJson != null) {
            localBackupJsonText = persistedJson
        } else {
            localBackupJsonText = null
        }
        val message =
            if (persisted) {
                successMessage
            } else {
                "$successMessage The local file could not be persisted."
            }
        localVaultMessage = message
        return LocalPersistenceResult(persisted = persisted)
    }

    private fun registerUsbReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun refreshSelectedTransport(preferredDevice: UsbDevice? = null, autoRequestPermission: Boolean = false) {
        when (selectedTransportMode) {
            SyncTransportMode.Usb -> refreshUsbState(preferredDevice = preferredDevice, autoRequestPermission = autoRequestPermission)
            SyncTransportMode.Speculos -> refreshSpeculosState()
        }
    }

    private fun refreshUsbState(preferredDevice: UsbDevice? = null, autoRequestPermission: Boolean = false) {
        val device = findLedgerDevice(preferredDevice)
        if (device == null) {
            DiagnosticLogStore.info(TAG, "refreshUsbState no-ledger autoRequestPermission=$autoRequestPermission")
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.Idle,
                    statusMessage = "No Ledger detected. Connect the device and open the Passwords app.",
                    deviceName = null,
                    appName = null,
                    appVersion = null,
                    storageSize = null,
                    deviceEntries = null,
                    diffSummary = null,
                    diffLines = emptyList(),
                    showVerifyCallToAction = false,
                )
            return
        }

        val hasPermission = usbManager.hasPermission(device)
        DiagnosticLogStore.info(
            TAG,
            "refreshUsbState device=${device.deviceName} permission=$hasPermission autoRequestPermission=$autoRequestPermission",
        )
        syncUiState =
            syncUiState.copy(
                status = if (hasPermission) SyncStatus.DeviceConnected else SyncStatus.UsbPermissionRequired,
                statusMessage =
                    if (hasPermission) {
                        "Ledger detected. You can import, compare, or export."
                    } else {
                        "USB permission required to access the Ledger."
                    },
                deviceName = device.deviceName,
                showVerifyCallToAction = false,
            )
        if (hasPermission) {
            fetchTargetSummary(SyncTarget.Usb(device))
        } else if (autoRequestPermission) {
            requestUsbPermission(device)
        }
    }

    private fun refreshSpeculosState() {
        val endpoint = currentSpeculosEndpoint() ?: return
        DiagnosticLogStore.info(TAG, "refreshSpeculosState target=${endpoint.label}")
        syncUiState =
            syncUiState.copy(
                status = SyncStatus.DeviceConnected,
                statusMessage = "Connecting to Speculos...",
                deviceName = endpoint.label,
                showVerifyCallToAction = false,
            )
        fetchTargetSummary(SyncTarget.Speculos(endpoint.host, endpoint.port))
    }

    private fun requestUsbPermission() {
        if (selectedTransportMode != SyncTransportMode.Usb) {
            updateSyncState {
                copy(
                    status = SyncStatus.DeviceConnected,
                    statusMessage = "No USB permission required in Speculos mode.",
                    deviceName = currentSpeculosEndpoint()?.label ?: syncUiState.deviceName,
                    showVerifyCallToAction = false,
                )
            }
            return
        }
        val device = findLedgerDevice(intent.usbDeviceOrNull()) ?: run {
            updateSyncState {
                copy(
                    status = SyncStatus.Idle,
                    statusMessage = "No Ledger detected for the permission request.",
                    showVerifyCallToAction = false,
                )
            }
            return
        }
        requestUsbPermission(device)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        DiagnosticLogStore.info(TAG, "requestUsbPermission device=${device.deviceName}")
        usbManager.requestPermission(device, usbPermissionIntent())
        updateSyncState {
            copy(
                status = SyncStatus.UsbPermissionRequired,
                statusMessage = "Android is waiting for your USB permission decision.",
                deviceName = device.deviceName,
                showVerifyCallToAction = false,
            )
        }
    }

    private fun fetchTargetSummary(target: SyncTarget) {
        DiagnosticLogStore.mark("fetchTargetSummary target=${target.label}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.DeviceConnected,
            preMessage =
                when (target) {
                    is SyncTarget.Usb -> "Reading Ledger information..."
                    is SyncTarget.Speculos -> "Reading Speculos information..."
                },
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App open on target: ${info.name}. Open Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    clearDeviceEntries = true,
                    clearDiffSummary = true,
                    clearDiffLines = true,
                    showVerifyCallToAction = false,
                )
            } else {
                val config = client.getAppConfig()
                val compatibilityNotice =
                    if (target is SyncTarget.Usb && !LedgerAppCompatibility.supportsRealDevicePush(info.version)) {
                        " Read-only is recommended until the Passwords app is updated to ${MIN_SAFE_REAL_DEVICE_VERSION_LABEL}+."
                    } else {
                        ""
                    }
                SyncUpdate(
                    status = SyncStatus.DeviceConnected,
                    statusMessage =
                        when (target) {
                            is SyncTarget.Usb -> "Ledger connected, Passwords app ${info.version} open.$compatibilityNotice"
                            is SyncTarget.Speculos -> "Speculos connected, Passwords app ${info.version} open."
                        },
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    clearDeviceEntries = true,
                    clearDiffSummary = true,
                    clearDiffLines = true,
                    showVerifyCallToAction = false,
                )
            }
        }
    }

    private fun pullFromLedger() {
        val target = requireCurrentTarget() ?: return
        val localSnapshot = localVault
        DiagnosticLogStore.mark("pullFromLedger target=${target.label}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.WaitingForLedgerApproval,
            preMessage = target.userActionMessage("Approve the import on the Ledger.", "Importing from Speculos..."),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App open on target: ${info.name}. Open Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    showVerifyCallToAction = false,
                )
            }
            val config = client.getAppConfig()
            updateSyncStateFromWorker(
                SyncUpdate(
                    status = SyncStatus.Dumping,
                    statusMessage = "Reading metadata...",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                ),
            )
            val decoded = metadataCodec.decode(client.dumpMetadatas(config.storageSize))
            val backupJsonText = backupJsonCodec.toJson(decoded, app = BackupApp(name = info.name, version = info.version))
            val importedVault = Vault(entries = decoded.vault.entries, source = VaultSource.Local)
            val requiresConfirmation = localSnapshot.entries.isNotEmpty() && importedVault.entries != localSnapshot.entries
            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage =
                    if (requiresConfirmation) {
                        "Import read from the target. Confirm replacement of the local vault."
                    } else {
                        target.userActionMessage("Import completed from Ledger.", "Import completed from the test target.")
                    },
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = decoded.vault.entries.size,
                clearDiffSummary = true,
                diffLines = emptyList(),
                replaceLocalVault = importedVault,
                replaceLocalBackupJsonText = backupJsonText,
                deferredLocalReplacementPrompt =
                    if (requiresConfirmation) {
                        DeferredLocalReplacementPrompt(
                            title = "Replace local vault?",
                            body =
                                buildString {
                                    append("The target contains ${decoded.vault.entries.size} identifier")
                                    if (decoded.vault.entries.size > 1) append('s')
                                    append(". ")
                                    append("The current local vault contains ${localSnapshot.entries.size}. ")
                                    append("This action will replace the local state saved on the phone.")
                                },
                            confirmLabel = "Replace local",
                            cancelMessage = "Import read from the target, but the local vault was not replaced.",
                            successMessage = "Local vault replaced from the target.",
                        )
                    } else {
                        null
                    },
                showVerifyCallToAction = false,
            )
        }
    }

    private fun compareWithLedger() {
        val target = requireCurrentTarget() ?: return
        val localSnapshot = localVault
        DiagnosticLogStore.mark("compareWithLedger target=${target.label}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.WaitingForLedgerApproval,
            preMessage = target.userActionMessage("Approve the read to compare with the Ledger.", "Reading from Speculos for comparison..."),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App open on target: ${info.name}. Open Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    showVerifyCallToAction = false,
                )
            }
            val config = client.getAppConfig()
            updateSyncStateFromWorker(
                SyncUpdate(
                    status = SyncStatus.Dumping,
                    statusMessage = "Reading metadata...",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                ),
            )
            val deviceVault = metadataCodec.decode(client.dumpMetadatas(config.storageSize)).vault
            val diff = differ.diff(before = deviceVault, after = localSnapshot)
            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = "Comparison completed.",
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = deviceVault.entries.size,
                diffSummary = renderLedgerDiffSummary(diff),
                diffLines = renderLedgerDiffLines(diff),
                showVerifyCallToAction = false,
            )
        }
    }

    private fun synchronizeVaults() {
        val target = requireCurrentTarget() ?: return
        val localSnapshot = localVault.copy(source = VaultSource.Local).sortedByNickname()
        DiagnosticLogStore.mark("synchronizeVaults target=${target.label} localEntries=${localSnapshot.entries.size}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.WaitingForLedgerApproval,
            preMessage =
                target.userActionMessage(
                    "Approve the read to prepare synchronization.",
                    "Preparing synchronization from Speculos...",
                ),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App open on target: ${info.name}. Open Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    showVerifyCallToAction = false,
                )
            }
            val config = client.getAppConfig()
            val shadow = matchingSyncShadow(target, config.storageSize)
            updateSyncStateFromWorker(
                SyncUpdate(
                    status = SyncStatus.Dumping,
                    statusMessage = "Reading metadata for synchronization...",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                ),
            )
            val deviceVault = metadataCodec.decode(client.dumpMetadatas(config.storageSize)).vault.copy(source = VaultSource.LedgerDevice).sortedByNickname()
            val summary: String
            val lines: List<String>
            val mergedVault: Vault

            if (shadow != null) {
                val plan = threeWayMergePlanner.plan(shadow.lastSyncedVault, localSnapshot, deviceVault)
                summary = renderThreeWaySynchronizationSummary(plan)
                lines = renderThreeWaySynchronizationLines(plan)

                if (!plan.hasChanges) {
                    return@performLedgerAction SyncUpdate(
                        status = SyncStatus.Success,
                        statusMessage = "Local and target already match the sync shadow.",
                        appName = info.name,
                        appVersion = info.version,
                        storageSize = config.storageSize,
                        deviceEntries = deviceVault.entries.size,
                        diffSummary = summary,
                        diffLines = lines,
                        replaceSyncShadow = buildSyncShadowState(target, localSnapshot, config.storageSize),
                        showVerifyCallToAction = false,
                    )
                }

                if (!plan.canMerge) {
                    return@performLedgerAction SyncUpdate(
                        status = SyncStatus.ValidationError,
                        statusMessage = "Synchronization requires conflict resolution before any write.",
                        appName = info.name,
                        appVersion = info.version,
                        storageSize = config.storageSize,
                        deviceEntries = deviceVault.entries.size,
                        diffSummary = summary,
                        diffLines = lines,
                        deferredSynchronizationConflictPrompt =
                            DeferredSynchronizationConflictPrompt(
                                pendingConflicts = plan.conflicts,
                                autoMergedEntries = buildAutomaticMergedEntries(plan),
                                summary = summary,
                                lines = lines,
                                storageSize = config.storageSize,
                            ),
                        showVerifyCallToAction = false,
                    )
                }

                mergedVault = requireNotNull(plan.mergedVault)
            } else {
                val plan = mergePlanner.plan(localSnapshot, deviceVault)
                summary = renderSynchronizationSummary(plan)
                lines = renderSynchronizationLines(plan)

                if (!plan.hasChanges) {
                    return@performLedgerAction SyncUpdate(
                        status = SyncStatus.Success,
                        statusMessage = "Local and target are already synchronized.",
                        appName = info.name,
                        appVersion = info.version,
                        storageSize = config.storageSize,
                        deviceEntries = deviceVault.entries.size,
                        diffSummary = summary,
                        diffLines = lines,
                        replaceSyncShadow = buildSyncShadowState(target, localSnapshot, config.storageSize),
                        showVerifyCallToAction = false,
                    )
                }

                if (!plan.canMerge) {
                    return@performLedgerAction SyncUpdate(
                        status = SyncStatus.ValidationError,
                        statusMessage = "Synchronization blocked: resolve the listed conflict(s) manually before writing.",
                        appName = info.name,
                        appVersion = info.version,
                        storageSize = config.storageSize,
                        deviceEntries = deviceVault.entries.size,
                        diffSummary = summary,
                        diffLines = lines,
                        showVerifyCallToAction = false,
                    )
                }

                mergedVault = requireNotNull(plan.mergedVault)
            }

            val assessment = assessMergedPushRisk(mergedVault, config.storageSize, target.pushSafetyMode())
            if (assessment.decision == PushRiskDecision.Block && target is SyncTarget.Usb && !hardwareDangerousOverrideEnabled) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        assessment.summaryLines().joinToString(separator = "\n") +
                            "\nSynchronization stopped before writing. Open Debug to enable the dangerous override if you really want to force the merged hardware write.",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    deviceEntries = deviceVault.entries.size,
                    diffSummary = summary,
                    diffLines = lines,
                    showVerifyCallToAction = false,
                )
            }
            if (!assessment.validation.isValid) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage = assessment.validation.toUserMessage(),
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    deviceEntries = deviceVault.entries.size,
                    diffSummary = summary,
                    diffLines = lines,
                    showVerifyCallToAction = false,
                )
            }

            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = "Synchronization plan ready. Review the merge and confirm the write.",
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = deviceVault.entries.size,
                diffSummary = summary,
                diffLines = lines,
                deferredSynchronizationPrompt = buildSynchronizationPrompt(target, summary, mergedVault, assessment),
                showVerifyCallToAction = false,
            )
        }
    }

    private fun buildAutomaticMergedEntries(plan: ThreeWayVaultMergePlan): List<PasswordIdentifier> =
        buildList {
            addAll(plan.localAdditions)
            addAll(plan.remoteAdditions)
            addAll(plan.localUpdates.map { it.after })
            addAll(plan.remoteUpdates.map { it.after })
            addAll(plan.convergedUpdates.map { it.after })
            addAll(plan.unchanged)
        }.sortedBy { it.nickname.lowercase() }

    private fun resolveSynchronizationConflict(choice: SyncConflictResolutionChoice) {
        val dialog = appDialogState as? AppDialogState.ResolveSynchronizationConflict ?: return
        val currentConflict = dialog.state.currentConflict ?: return
        val resolvedEntry =
            when (choice) {
                SyncConflictResolutionChoice.KeepLocal -> currentConflict.localEntry
                SyncConflictResolutionChoice.KeepTarget -> currentConflict.remoteEntry
            }
        val nextState =
            dialog.state.copy(
                pendingConflicts = dialog.state.pendingConflicts.drop(1),
                chosenEntries = dialog.state.chosenEntries + listOfNotNull(resolvedEntry),
                chosenNotes = dialog.state.chosenNotes + renderThreeWayConflictResolutionLine(currentConflict, choice),
            )
        if (nextState.pendingConflicts.isNotEmpty()) {
            appDialogState = dialog.copy(state = nextState)
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        "Conflict ${nextState.resolvedCount + 1}/${nextState.totalConflictCount} ready for resolution.",
                    diffSummary = nextState.summary,
                    diffLines = nextState.lines,
                    showVerifyCallToAction = false,
                )
            return
        }

        appDialogState = null
        finalizeResolvedSynchronization(nextState)
    }

    private fun finalizeResolvedSynchronization(state: DeferredSynchronizationConflictPrompt) {
        val target = requireCurrentTarget() ?: return
        val mergedVault =
            Vault(
                entries = state.autoMergedEntries + state.chosenEntries,
                source = VaultSource.Local,
            ).sortedByNickname()
        val assessment = assessMergedPushRisk(mergedVault, state.storageSize, target.pushSafetyMode())
        if (assessment.decision == PushRiskDecision.Block && target is SyncTarget.Usb && !hardwareDangerousOverrideEnabled) {
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        assessment.summaryLines().joinToString(separator = "\n") +
                            "\nSynchronization stopped before writing. Open Debug to enable the dangerous override if you really want to force the merged hardware write.",
                    diffSummary = renderResolvedSynchronizationSummary(state),
                    diffLines = renderResolvedSynchronizationLines(state),
                    showVerifyCallToAction = false,
                )
            return
        }
        if (!assessment.validation.isValid) {
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.ValidationError,
                    statusMessage = assessment.validation.toUserMessage(),
                    diffSummary = renderResolvedSynchronizationSummary(state),
                    diffLines = renderResolvedSynchronizationLines(state),
                    showVerifyCallToAction = false,
                )
            return
        }

        val prompt = buildSynchronizationPrompt(target, renderResolvedSynchronizationSummary(state), mergedVault, assessment)
        syncUiState =
            syncUiState.copy(
                status = SyncStatus.Success,
                statusMessage = "All synchronization conflicts resolved. Review the merged write and confirm.",
                diffSummary = renderResolvedSynchronizationSummary(state),
                diffLines = renderResolvedSynchronizationLines(state),
                showVerifyCallToAction = false,
            )
        appDialogState =
            AppDialogState.ConfirmSynchronization(
                title = prompt.title,
                body = prompt.body,
                confirmLabel = prompt.confirmLabel,
                cancelMessage = prompt.cancelMessage,
                mergedVault = mergedVault,
            )
    }

    private fun executeSynchronization(dialog: AppDialogState.ConfirmSynchronization) {
        val target = requireCurrentTarget() ?: return
        val mergedVault = dialog.mergedVault.copy(source = VaultSource.Local).sortedByNickname()
        DiagnosticLogStore.mark("executeSynchronization target=${target.label} mergedEntries=${mergedVault.entries.size}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.Loading,
            preMessage =
                target.userActionMessage(
                    "Writing the merged vault to the Ledger and verifying the result.",
                    "Writing the merged vault to Speculos and verifying the result.",
                ),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App open on target: ${info.name}. Open Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    showVerifyCallToAction = false,
                )
            }
            if (target is SyncTarget.Usb && !LedgerAppCompatibility.supportsRealDevicePush(info.version)) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        "Hardware sync blocked: Passwords app ${info.version} is older than " +
                            "${MIN_SAFE_REAL_DEVICE_VERSION_LABEL}. Update the app on the Ledger before any real write.",
                    appName = info.name,
                    appVersion = info.version,
                    showVerifyCallToAction = false,
                )
            }
            val config = client.getAppConfig()
            val assessment = assessMergedPushRisk(mergedVault, config.storageSize, target.pushSafetyMode())
            if (assessment.decision == PushRiskDecision.Block && target is SyncTarget.Usb && !hardwareDangerousOverrideEnabled) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        assessment.summaryLines().joinToString(separator = "\n") +
                            "\nSynchronization stopped before writing. Open Debug to enable the dangerous override if you really want to force the merged hardware write.",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                )
            }
            if (!assessment.validation.isValid) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage = assessment.validation.toUserMessage(),
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                )
            }
            if (assessment.decision == PushRiskDecision.Warn || (assessment.decision == PushRiskDecision.Block && hardwareDangerousOverrideEnabled)) {
                DiagnosticLogStore.warn(
                    TAG,
                    "executeSynchronization risk decision=${assessment.decision} override=$hardwareDangerousOverrideEnabled findings=${assessment.summaryLines().joinToString(" | ")}",
                )
            }

            val raw = MetadataCodec(config.storageSize).encode(mergedVault)
            client.loadMetadatas(raw)
            updateSyncStateFromWorker(
                SyncUpdate(
                    status = SyncStatus.Verifying,
                    statusMessage = target.userActionMessage("Write completed. Verifying Ledger...", "Write completed. Verifying Speculos..."),
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                ),
            )

            val deviceVault = metadataCodec.decode(client.dumpMetadatas(config.storageSize)).vault
            val diff = differ.diff(before = deviceVault, after = mergedVault)
            if (diff.hasChanges) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage = "Synchronization wrote to the target, but verification did not match the merged result.",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    deviceEntries = deviceVault.entries.size,
                    diffSummary = renderLedgerDiffSummary(diff),
                    diffLines = renderLedgerDiffLines(diff),
                    showVerifyCallToAction = false,
                )
            }

            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = "Synchronization completed. Local and target now share the merged vault.",
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = deviceVault.entries.size,
                diffSummary = renderLedgerDiffSummary(diff),
                diffLines = renderLedgerDiffLines(diff),
                replaceLocalVault = mergedVault,
                replaceSyncShadow = buildSyncShadowState(target, mergedVault, config.storageSize),
                showVerifyCallToAction = false,
            )
        }
    }

    private fun pushToLedger() {
        val target = requireCurrentTarget() ?: return
        val localSnapshot = localVault
        val localAssessment = assessLocalPushRisk(localSnapshot, effectiveStorageSize(), target.pushSafetyMode())
        if (localAssessment.decision == PushRiskDecision.Block && target is SyncTarget.Usb && !hardwareDangerousOverrideEnabled) {
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        localAssessment.summaryLines().joinToString(separator = "\n") +
                            "\nOpen Debug to enable the dangerous override if you really want to force this hardware push.",
                    showVerifyCallToAction = false,
                )
            return
        }
        if (!localAssessment.validation.isValid) {
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.ValidationError,
                    statusMessage = localAssessment.validation.toUserMessage(),
                    showVerifyCallToAction = false,
                )
            return
        }
        DiagnosticLogStore.mark("pushToLedger confirmed target=${target.label} localEntries=${localSnapshot.entries.size}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.Loading,
            preMessage = target.userActionMessage(
                "Writing to the Ledger. Approve only once on the device. " +
                    "No automatic verification will run after the push.",
                "Writing to Speculos.",
            ),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App open on target: ${info.name}. Open Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    showVerifyCallToAction = false,
                )
            }
            if (target is SyncTarget.Usb && !LedgerAppCompatibility.supportsRealDevicePush(info.version)) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        "Hardware push blocked: Passwords app ${info.version} is older than " +
                            "${MIN_SAFE_REAL_DEVICE_VERSION_LABEL}. Update the app on the Ledger " +
                            "before any real write.",
                    appName = info.name,
                    appVersion = info.version,
                    clearDeviceEntries = true,
                    clearDiffLines = true,
                    clearDiffSummary = true,
                    showVerifyCallToAction = false,
                )
            }
            val config = client.getAppConfig()
            val deviceAssessment = assessLocalPushRisk(localSnapshot, config.storageSize, target.pushSafetyMode())
            if (deviceAssessment.decision == PushRiskDecision.Block && target is SyncTarget.Usb && !hardwareDangerousOverrideEnabled) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        deviceAssessment.summaryLines().joinToString(separator = "\n") +
                            "\nOpen Debug to enable the dangerous override if you really want to force this hardware push.",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                )
            }
            if (!deviceAssessment.validation.isValid) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage = deviceAssessment.validation.toUserMessage(),
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    showVerifyCallToAction = false,
                )
            }
            if (deviceAssessment.decision == PushRiskDecision.Warn || (deviceAssessment.decision == PushRiskDecision.Block && hardwareDangerousOverrideEnabled)) {
                DiagnosticLogStore.warn(
                    TAG,
                    "pushToLedger risk decision=${deviceAssessment.decision} override=$hardwareDangerousOverrideEnabled findings=${deviceAssessment.summaryLines().joinToString(" | ")}",
                )
            }
            val backupRaw = localBackupJsonText?.let(backupJsonCodec::rawFromJson)
            val raw = if (backupRaw != null && backupRaw.size == config.storageSize) backupRaw else MetadataCodec(config.storageSize).encode(localSnapshot)
            client.loadMetadatas(raw)
            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = target.userActionMessage(
                    if (hardwareDangerousOverrideEnabled && deviceAssessment.decision == PushRiskDecision.Block) {
                        "Push sent to the Ledger with dangerous override. No automatic readback was performed after writing."
                    } else {
                        "Push sent to the Ledger. No automatic readback was performed after writing."
                    },
                    "Push sent to Speculos.",
                ),
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                clearDeviceEntries = true,
                clearDiffSummary = true,
                clearDiffLines = true,
                showVerifyCallToAction = true,
            )
        }
    }

    private fun verifyLedger() {
        val target = requireCurrentTarget() ?: return
        val localSnapshot = localVault
        DiagnosticLogStore.mark("verifyLedger target=${target.label}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.Verifying,
            preMessage = target.userActionMessage("Verifying Ledger...", "Verifying Speculos..."),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App open on target: ${info.name}. Open Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    showVerifyCallToAction = false,
                )
            }
            val config = client.getAppConfig()
            val deviceVault = metadataCodec.decode(client.dumpMetadatas(config.storageSize)).vault
            val diff = differ.diff(before = deviceVault, after = localSnapshot)
            if (diff.hasChanges) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        "Ledger differs from the local state " +
                            "(local=${localSnapshot.entries.size}, ledger=${deviceVault.entries.size}).",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    deviceEntries = deviceVault.entries.size,
                    diffSummary = renderLedgerDiffSummary(diff),
                    diffLines = renderLedgerDiffLines(diff),
                    showVerifyCallToAction = false,
                )
            }
            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = "Ledger matches the local state.",
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = deviceVault.entries.size,
                diffSummary = renderLedgerDiffSummary(diff),
                diffLines = renderLedgerDiffLines(diff),
                showVerifyCallToAction = false,
            )
        }
    }

    private fun requireCurrentTarget(): SyncTarget? =
        when (selectedTransportMode) {
            SyncTransportMode.Usb -> requireLedgerUsbTarget()
            SyncTransportMode.Speculos -> currentSpeculosEndpoint()?.let { SyncTarget.Speculos(it.host, it.port) }
        }

    private fun requireLedgerUsbTarget(): SyncTarget.Usb? {
        val device = findLedgerDevice(intent.usbDeviceOrNull())
        if (device == null) {
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.Idle,
                    statusMessage = "No Ledger detected.",
                    showVerifyCallToAction = false,
                )
            return null
        }
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return null
        }
        return SyncTarget.Usb(device)
    }

    private fun currentSpeculosEndpoint(): SpeculosEndpoint? {
        val host = speculosHost.trim()
        if (host.isBlank()) {
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.ValidationError,
                    statusMessage = "Enter a Speculos address.",
                    deviceName = null,
                    showVerifyCallToAction = false,
                )
            return null
        }
        val port =
            speculosPortText.trim().toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: run {
                    syncUiState =
                        syncUiState.copy(
                            status = SyncStatus.ValidationError,
                            statusMessage = "Speculos port must be an integer between 1 and 65535.",
                            deviceName = null,
                            showVerifyCallToAction = false,
                        )
                    return null
                }
        return SpeculosEndpoint(host = host, port = port)
    }

    private fun performLedgerAction(
        target: SyncTarget,
        preStatus: SyncStatus,
        preMessage: String,
        work: suspend (LedgerPasswordsClient) -> SyncUpdate,
    ) {
        DiagnosticLogStore.info(
            TAG,
            "performLedgerAction status=$preStatus target=${target.label} localEntries=${localVault.entries.size} message=$preMessage",
        )
        updateSyncState {
            copy(
                status = preStatus,
                statusMessage = preMessage,
                deviceName = target.label,
                showVerifyCallToAction = false,
            )
        }
        executor.execute {
            try {
                openTransport(target).use { transport ->
                    val update = runBlocking { work(LedgerPasswordsClient(transport)) }
                    DiagnosticLogStore.info(
                        TAG,
                        "performLedgerAction success status=${update.status} target=${target.label} message=${update.statusMessage}",
                    )
                    updateSyncStateFromWorker(update)
                }
            } catch (error: Throwable) {
                DiagnosticLogStore.error(
                    TAG,
                    "performLedgerAction failure status=$preStatus target=${target.label}: ${error.message}",
                    error,
                )
                updateSyncStateFromWorker(
                    SyncUpdate(
                        status = SyncStatus.TransportError,
                        statusMessage = error.message ?: error::class.java.simpleName,
                        showVerifyCallToAction = false,
                    ),
                )
            }
        }
    }

    private fun openTransport(target: SyncTarget): LedgerTransport =
        when (target) {
            is SyncTarget.Usb -> AndroidUsbLedgerTransport(usbManager, target.device)
            is SyncTarget.Speculos -> SpeculosTransport(server = target.host, port = target.port)
        }

    private fun updateSyncState(transform: SyncUiState.() -> SyncUiState) {
        syncUiState = syncUiState.transform()
    }

    private fun updateSyncStateFromWorker(update: SyncUpdate) {
        runOnUiThread {
            var statusMessage = update.statusMessage
            var localPersistenceSucceeded = true
            if (update.deferredSynchronizationConflictPrompt != null) {
                appDialogState = AppDialogState.ResolveSynchronizationConflict(update.deferredSynchronizationConflictPrompt)
            } else {
                update.deferredSynchronizationPrompt?.let { prompt ->
                    appDialogState =
                        AppDialogState.ConfirmSynchronization(
                            title = prompt.title,
                            body = prompt.body,
                            confirmLabel = prompt.confirmLabel,
                            cancelMessage = prompt.cancelMessage,
                            mergedVault = prompt.mergedVault,
                        )
                }
            }
            update.replaceLocalVault?.let { replacement ->
                val prompt = update.deferredLocalReplacementPrompt
                if (prompt != null) {
                    appDialogState =
                        AppDialogState.ConfirmLedgerImport(
                            title = prompt.title,
                            body = prompt.body,
                            confirmLabel = prompt.confirmLabel,
                            cancelMessage = prompt.cancelMessage,
                            confirmedStatusMessage = update.statusMessage,
                            successMessage = prompt.successMessage,
                            importedVault = replacement,
                            backupJsonText = update.replaceLocalBackupJsonText,
                        )
                } else {
                    val persistence =
                        applyLocalVault(
                            replacement,
                            "Local vault replaced from the target.",
                            backupJsonText = update.replaceLocalBackupJsonText,
                        )
                    localPersistenceSucceeded = persistence.persisted
                    if (!persistence.persisted) {
                        statusMessage = "${update.statusMessage} The local replacement could not be persisted."
                    }
                }
            }
            if (localPersistenceSucceeded) {
                update.replaceSyncShadow?.let { shadow ->
                    val persistedShadow = runCatching { syncShadowStore.save(shadow) }.getOrNull()
                    if (persistedShadow != null) {
                        syncShadowState = persistedShadow
                    } else {
                        statusMessage = "$statusMessage The sync shadow could not be persisted."
                    }
                }
            }
            syncUiState = applySyncUpdate(syncUiState, update.copy(statusMessage = statusMessage))
        }
    }

    private fun findLedgerDevice(preferredDevice: UsbDevice? = null): UsbDevice? {
        if (preferredDevice?.vendorId == LEDGER_VENDOR_ID) return preferredDevice
        return usbManager.deviceList.values.firstOrNull { it.vendorId == LEDGER_VENDOR_ID }
    }

    private fun usbPermissionIntent(): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun defaultTransportMode(): SyncTransportMode =
        if (isProbablyRunningOnEmulator()) {
            SyncTransportMode.Speculos
        } else {
            SyncTransportMode.Usb
        }

    private fun defaultSpeculosHost(): String =
        if (isProbablyRunningOnEmulator()) {
            EMULATOR_HOST_LOOPBACK
        } else {
            SpeculosTransport.DEFAULT_SERVER
        }

    private fun isProbablyRunningOnEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("sdk built for") ||
            product.contains("sdk") ||
            product.contains("emulator")
    }

    companion object {
        private const val TAG = "LedgerPwUi"
        private const val ACTION_USB_PERMISSION = "com.ledgerpasswords.companion.USB_PERMISSION"
        private const val LEDGER_VENDOR_ID = 0x2C97
        private const val EXPECTED_APP_NAME = "Passwords"
        private const val MIN_SAFE_REAL_DEVICE_VERSION_LABEL = "1.3.1"
        private const val LOCAL_VAULT_FILE_NAME = "local-vault.json"
        private const val SYNC_SHADOW_FILE_NAME = "sync-shadow.properties"
        private const val UI_PREFERENCES_FILE_NAME = "ui-preferences.properties"
        private const val DEFAULT_BACKUP_FILE_NAME = "ledger-passwords-backup.json"
        private const val EMULATOR_HOST_LOOPBACK = "10.0.2.2"
        private const val DEFAULT_SPECULOS_PORT = 10100
        const val DIAGNOSTIC_LOG_FILE_NAME = DiagnosticLogStore.FILE_NAME
    }
}

internal data class PushConfirmationDialogState(
    val title: String,
    val body: String,
    val canConfirm: Boolean,
    val confirmLabel: String = "Continue",
)

internal sealed interface AppDialogState {
    val title: String
    val body: String
    val canConfirm: Boolean
    val confirmLabel: String
    val dismissLabel: String?

    data class StartupWarning(
        val dontShowAgain: Boolean = false,
    ) : AppDialogState {
        override val title: String = "Experimental warning"
        override val body: String =
            "This application is experimental. On a real device, some writes can cause " +
                "unstable behavior in the Passwords app, or even a reset that requires reconfiguration. " +
                "Prefer reading, diffing, and verification before any hardware write."
        override val canConfirm: Boolean = true
        override val confirmLabel: String = "Continue"
        override val dismissLabel: String? = null
    }

    data class PushConfirmation(
        val state: PushConfirmationDialogState,
    ) : AppDialogState {
        override val title: String get() = state.title
        override val body: String get() = state.body
        override val canConfirm: Boolean get() = state.canConfirm
        override val confirmLabel: String get() = state.confirmLabel
        override val dismissLabel: String = if (state.canConfirm) "Cancel" else "Close"
    }

    data class DeleteEntry(
        val nickname: String,
    ) : AppDialogState {
        override val title: String = "Delete this identifier?"
        override val body: String = "Identifier \"$nickname\" will be removed from the local vault."
        override val canConfirm: Boolean = true
        override val confirmLabel: String = "Delete"
        override val dismissLabel: String = "Cancel"
    }

    data class ConfirmBackupImport(
        val fileName: String,
        val importedVault: Vault,
        val backupJsonText: String,
        val hasRiskFindings: Boolean,
    ) : AppDialogState {
        override val title: String = "Replace local vault?"
        override val body: String =
            buildString {
                append("File ")
                append(fileName)
                append(" contains ")
                append(importedVault.entries.size)
                append(" identifier")
                if (importedVault.entries.size > 1) append('s')
                append(". The current local state will be replaced.")
                if (hasRiskFindings) {
                    append(" This backup contains weak risk signals that may block a hardware push until it has been normalized.")
                }
            }
        override val canConfirm: Boolean = true
        override val confirmLabel: String = "Import and replace"
        override val dismissLabel: String = "Cancel"
    }

    data class ConfirmLedgerImport(
        override val title: String,
        override val body: String,
        override val confirmLabel: String,
        val cancelMessage: String,
        val confirmedStatusMessage: String,
        val successMessage: String,
        val importedVault: Vault,
        val backupJsonText: String?,
    ) : AppDialogState {
        override val canConfirm: Boolean = true
        override val dismissLabel: String = "Cancel"
    }

    data class ConfirmSynchronization(
        override val title: String,
        override val body: String,
        override val confirmLabel: String,
        val cancelMessage: String,
        val mergedVault: Vault,
    ) : AppDialogState {
        override val canConfirm: Boolean = true
        override val dismissLabel: String = "Cancel"
    }

    data class ResolveSynchronizationConflict(
        val state: DeferredSynchronizationConflictPrompt,
    ) : AppDialogState {
        override val title: String
            get() = "Resolve synchronization conflict ${state.resolvedCount + 1}/${state.totalConflictCount}"
        override val body: String
            get() =
                buildString {
                    val conflict = state.currentConflict
                    if (conflict != null) {
                        append("Choose which side should win for ")
                        append(conflict.nickname)
                        append(".\n\n")
                        append(renderConflictResolutionSide("Local", conflict.localEntry))
                        append('\n')
                        append(renderConflictResolutionSide("Target", conflict.remoteEntry))
                    }
                    if (state.chosenNotes.isNotEmpty()) {
                        append("\n\nResolved so far:")
                        state.chosenNotes.forEach { line ->
                            append("\n")
                            append(line)
                        }
                    }
                }
        override val canConfirm: Boolean = false
        override val confirmLabel: String = "Keep local"
        override val dismissLabel: String? = null
    }
}

private fun renderConflictResolutionSide(
    label: String,
    entry: PasswordIdentifier?,
): String =
    when (entry) {
        null -> "$label: removed"
        else -> "$label: ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]"
    }

internal data class EntryEditorState(
    val originalNickname: String? = null,
    val nickname: String = "",
    val selectedFlags: Set<CharsetFlag> = CharsetFlag.entries.toSet(),
    val errorMessage: String? = null,
) {
    val isCreation: Boolean get() = originalNickname == null
    val nicknameByteCount: Int get() = nickname.trim().toByteArray(Charsets.UTF_8).size

    companion object {
        fun fromEntry(entry: PasswordIdentifier): EntryEditorState = EntryEditorState(
            originalNickname = entry.nickname,
            nickname = entry.nickname,
            selectedFlags = entry.charsets.toFlags(),
        )
    }
}

private data class LocalPersistenceResult(
    val persisted: Boolean,
)

internal enum class SyncTransportMode {
    Usb,
    Speculos,
}

private sealed interface SyncTarget {
    val label: String

    data class Usb(
        val device: UsbDevice,
    ) : SyncTarget {
        override val label: String get() = device.deviceName
    }

    data class Speculos(
        val host: String,
        val port: Int,
    ) : SyncTarget {
        override val label: String get() = "Speculos $host:$port"
    }
}

private data class SpeculosEndpoint(
    val host: String,
    val port: Int,
) {
    val label: String get() = "Speculos $host:$port"
}

internal object UiTags {
    const val HomeAddEntry = "home_add_entry"
    const val HomeOpenSync = "home_open_sync"
    const val EntryNicknameField = "entry_nickname_field"
    const val EntrySave = "entry_save"
    const val DialogStartupDontShowAgain = "dialog_startup_dont_show_again"
    const val SyncBack = "sync_back"
    const val SyncScroll = "sync_scroll"
    const val SyncStatusMessage = "sync_status_message"
    const val SyncDiffSection = "sync_diff_section"
    const val SyncWriteSection = "sync_write_section"
    const val SyncTransportUsb = "sync_transport_usb"
    const val SyncTransportSpeculos = "sync_transport_speculos"
    const val SyncSpeculosHost = "sync_speculos_host"
    const val SyncSpeculosPort = "sync_speculos_port"
    const val SyncRefresh = "sync_refresh"
    const val SyncSynchronize = "sync_synchronize"
    const val SyncPull = "sync_pull"
    const val SyncPush = "sync_push"
    const val SyncVerify = "sync_verify"
    const val SettingsPushConfirmationSwitch = "settings_push_confirmation_switch"
    const val SettingsStartupWarningSwitch = "settings_startup_warning_switch"
}

private fun CharsetPolicy.toFlags(): Set<CharsetFlag> =
    if (isAll) {
        CharsetFlag.entries.toSet()
    } else {
        CharsetFlag.entries.filterTo(linkedSetOf()) { bitmask and it.bit != 0 }
    }

private fun Set<CharsetFlag>.toCharsetPolicy(): CharsetPolicy =
    if (size == CharsetFlag.entries.size) {
        CharsetPolicy.All
    } else {
        CharsetPolicy.fromFlags(sortedBy { it.ordinal })
    }

private fun SyncTarget.userActionMessage(forUsb: String, forSpeculos: String): String =
    when (this) {
        is SyncTarget.Usb -> forUsb
        is SyncTarget.Speculos -> forSpeculos
    }

private fun SyncTarget.pushSafetyMode(): PushSafetyMode =
    when (this) {
        is SyncTarget.Usb -> PushSafetyMode.HardwareSafe
        is SyncTarget.Speculos -> PushSafetyMode.Standard
    }

private fun ValidationResult.toUserMessage(): String =
    if (isValid) {
        "OK"
    } else {
        issues.joinToString(separator = "\n") { it.message }
    }

private fun MainActivity.displayNameFor(uri: Uri): String? {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (columnIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(columnIndex)
        }
    }
    return uri.lastPathSegment
}

private fun Intent.usbDeviceOrNull(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
