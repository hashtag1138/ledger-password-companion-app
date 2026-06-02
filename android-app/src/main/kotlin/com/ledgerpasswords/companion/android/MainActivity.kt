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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val localVaultStore by lazy { LocalVaultStore(File(filesDir, LOCAL_VAULT_FILE_NAME)) }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val backupJsonCodec = BackupJsonCodec()
    private val metadataCodec = MetadataCodec()
    private val differ = VaultDiffer()

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                localVaultMessage = "Import backup.json annulé."
            } else {
                importBackupFromUri(uri)
            }
        }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) {
                localVaultMessage = "Export backup.json annulé."
            } else {
                exportBackupToUri(uri)
            }
        }

    private var localVault by mutableStateOf(Vault(source = VaultSource.Local))
    private var localVaultMessage by mutableStateOf("Chargement du stockage local...")
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
    private var pushConfirmationDialogState by mutableStateOf<PushConfirmationDialogState?>(null)
    private var hardwarePushConfirmationEnabled by mutableStateOf(true)
    private var hardwareDangerousOverrideEnabled by mutableStateOf(false)

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
                                    statusMessage = "Permission USB refusée pour ${device.productName ?: device.deviceName}",
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
                                    statusMessage = "Ledger déconnecté. Rebranche le device et ouvre l'app Passwords.",
                                    deviceName = null,
                                    appName = null,
                                    appVersion = null,
                                    storageSize = null,
                                    deviceEntries = null,
                                    diffLines = emptyList(),
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
        registerUsbReceiver()
        refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull())
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
                hardwarePushConfirmationEnabled = hardwarePushConfirmationEnabled,
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
                onHardwarePushConfirmationChanged = { enabled -> hardwarePushConfirmationEnabled = enabled },
                onHardwareDangerousOverrideChanged = { enabled -> hardwareDangerousOverrideEnabled = enabled },
                onSaveEntry = { saveEntry() },
                onDeleteEntry = { deleteEntry() },
                onImportBackup = { launchImportBackup() },
                onExportBackup = { launchExportBackup() },
                onOpenSync = { openSyncScreen() },
                onTransportModeChanged = { mode ->
                    selectedTransportMode = mode
                    pushConfirmationDialogState = null
                    syncUiState =
                        SyncUiState(
                            statusMessage =
                                when (mode) {
                                    SyncTransportMode.Usb -> "Branche un Ledger et ouvre l'app Passwords."
                                    SyncTransportMode.Speculos -> "Configure Speculos puis rafraîchis la cible."
                                },
                        )
                    refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull(), autoRequestPermission = false)
                },
                onSpeculosHostChanged = { host -> speculosHost = host },
                onSpeculosPortChanged = { port -> speculosPortText = port },
                onRequestPermission = { requestUsbPermission() },
                onRefreshDevice = { refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull()) },
                onPullFromLedger = { pullFromLedger() },
                onCompareWithLedger = { compareWithLedger() },
                onPushToLedger = {
                    if (selectedTransportMode == SyncTransportMode.Usb && hardwarePushConfirmationEnabled) {
                        pushConfirmationDialogState = buildPushConfirmationDialogState()
                    } else {
                        pushToLedger()
                    }
                },
                onVerifyLedger = { verifyLedger() },
                pushConfirmationDialogState = pushConfirmationDialogState,
                onDismissPushConfirmation = { pushConfirmationDialogState = null },
                onConfirmPushToLedger = {
                    pushConfirmationDialogState = null
                    pushToLedger()
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

    private fun openHomeScreen() {
        pushConfirmationDialogState = null
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
        pushConfirmationDialogState = null
        refreshSelectedTransport(preferredDevice = intent.usbDeviceOrNull(), autoRequestPermission = false)
    }

    private fun openSettingsScreen() {
        entryEditorState = null
        showSyncScreen = false
        showAboutScreen = false
        showDebugScreen = false
        pushConfirmationDialogState = null
        showSettingsScreen = true
    }

    private fun openAboutScreen() {
        entryEditorState = null
        showSyncScreen = false
        showSettingsScreen = false
        showDebugScreen = false
        pushConfirmationDialogState = null
        showAboutScreen = true
    }

    private fun openDebugScreen() {
        entryEditorState = null
        showSyncScreen = false
        showSettingsScreen = false
        showAboutScreen = false
        pushConfirmationDialogState = null
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
                                    "Le backup brut local n'a pas pu être relu: ${error.message ?: error::class.java.simpleName}.",
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
                message = "L'état local affiché ne correspond pas exactement au backup brut conservé.",
            )
        }
        return LedgerPushRiskPolicy(storageSize).assess(normalizedVault, mode, findings)
    }

    private fun buildPushConfirmationDialogState(): PushConfirmationDialogState {
        val assessment = assessLocalPushRisk(localVault, effectiveStorageSize(), PushSafetyMode.HardwareSafe)
        val baseIntro =
            "Cette action écrit sur un vrai Ledger. Aucun readback automatique ne sera lancé après l'écriture."
        return when (assessment.decision) {
            PushRiskDecision.Allow ->
                PushConfirmationDialogState(
                    title = "Confirmer le push matériel",
                    body = "$baseIntro\n\nAucun risque additionnel détecté par la policy companion.",
                    canConfirm = true,
                )
            PushRiskDecision.Warn ->
                PushConfirmationDialogState(
                    title = "Confirmer le push matériel",
                    body = "$baseIntro\n\n${assessment.summaryLines().joinToString(separator = "\n")}",
                    canConfirm = true,
                )
            PushRiskDecision.Block ->
                PushConfirmationDialogState(
                    title = if (hardwareDangerousOverrideEnabled) "Override dangereux actif" else "Push matériel bloqué",
                    body =
                        buildString {
                            append(baseIntro)
                            append("\n\n")
                            append(assessment.summaryLines().joinToString(separator = "\n"))
                            append("\n\n")
                            if (hardwareDangerousOverrideEnabled) {
                                append("L'override dangereux est actif dans Debug. Tu peux forcer ce push malgré ces blocages.")
                            } else {
                                append("Le push réel est bloqué par la policy hardware-safe. Ouvre Debug pour activer l'override dangereux si tu veux vraiment forcer l'écriture.")
                            }
                        },
                    canConfirm = hardwareDangerousOverrideEnabled,
                    confirmLabel = "Forcer le push",
                )
        }
    }

    private fun updateEntryEditor(transform: EntryEditorState.() -> EntryEditorState) {
        entryEditorState = entryEditorState?.transform()
    }

    private fun EntryEditorState.coerceNicknameDraft(candidate: String): EntryEditorState {
        val byteCount = candidate.trim().toByteArray(Charsets.UTF_8).size
        val error =
            if (byteCount > LedgerPasswordsLimits.MAX_NICKNAME_BYTES) {
                "Le nickname dépasse ${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} octets UTF-8."
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
            updateEntryEditor { copy(errorMessage = "Le nickname ne doit pas être vide.") }
            return
        }
        if (draft.nicknameByteCount > LedgerPasswordsLimits.MAX_NICKNAME_BYTES) {
            updateEntryEditor {
                copy(errorMessage = "Le nickname dépasse ${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} octets UTF-8.")
            }
            return
        }
        if (draft.selectedFlags.isEmpty()) {
            updateEntryEditor { copy(errorMessage = "Sélectionne au moins un charset.") }
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
                            draft.originalNickname == null -> "Identifiant ajouté et sauvegardé localement."
                            draft.originalNickname == nickname -> "Identifiant mis à jour et sauvegardé localement."
                            else -> "Identifiant renommé et sauvegardé localement."
                        },
                )
                entryEditorState = null
            }
            .onFailure { error ->
                updateEntryEditor {
                    copy(errorMessage = error.message ?: "Impossible d'enregistrer cet identifiant.")
                }
            }
    }

    private fun deleteEntry() {
        val originalNickname = entryEditorState?.originalNickname ?: return
        runCatching { editorFor().delete(localVault, originalNickname) }
            .onSuccess { nextVault ->
                applyLocalVault(
                    vault = nextVault,
                    successMessage = "Identifiant supprimé et stockage local mis à jour.",
                )
                entryEditorState = null
            }
            .onFailure { error ->
                updateEntryEditor {
                    copy(errorMessage = error.message ?: "Impossible de supprimer cet identifiant.")
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
                    ?: error("Impossible de lire $fileName.")
            val inspection = backupJsonCodec.inspect(text)
            val importedVault = inspection.preferredVault.copy(source = VaultSource.Local).sortedByNickname()
            val validation = validatorFor().validate(importedVault)
            validation.throwIfInvalid()
            val suffix =
                if (inspection.findings.isEmpty()) {
                    ""
                } else {
                    " Certaines écritures matérielles seront bloquées tant que ce backup n'aura pas été normalisé."
                }
            applyLocalVault(importedVault, "Vault local importé depuis $fileName.$suffix", backupJsonText = text)
        }.onFailure { error ->
            localVaultMessage = error.message ?: "Import backup.json impossible."
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
                    ?: error("Impossible d'ouvrir $fileName en écriture.")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            localVaultMessage = "Backup exporté vers $fileName."
        }.onFailure { error ->
            localVaultMessage = error.message ?: "Export backup.json impossible."
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
                "$successMessage Le fichier local n'a pas pu être persisté."
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
                    statusMessage = "Aucun Ledger détecté. Branche le device et ouvre l'app Passwords.",
                    deviceName = null,
                    appName = null,
                    appVersion = null,
                    storageSize = null,
                    deviceEntries = null,
                    diffLines = emptyList(),
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
                        "Ledger détecté. Tu peux importer, comparer ou exporter."
                    } else {
                        "Permission USB requise pour accéder au Ledger."
                    },
                deviceName = device.deviceName,
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
                statusMessage = "Connexion à Speculos en cours...",
                deviceName = endpoint.label,
            )
        fetchTargetSummary(SyncTarget.Speculos(endpoint.host, endpoint.port))
    }

    private fun requestUsbPermission() {
        if (selectedTransportMode != SyncTransportMode.Usb) {
            updateSyncState {
                copy(
                    status = SyncStatus.DeviceConnected,
                    statusMessage = "Aucune permission USB requise en mode Speculos.",
                    deviceName = currentSpeculosEndpoint()?.label ?: syncUiState.deviceName,
                )
            }
            return
        }
        val device = findLedgerDevice(intent.usbDeviceOrNull()) ?: run {
            updateSyncState {
                copy(
                    status = SyncStatus.Idle,
                    statusMessage = "Aucun Ledger détecté pour la demande de permission.",
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
                statusMessage = "Android attend ta décision pour la permission USB.",
                deviceName = device.deviceName,
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
                    is SyncTarget.Usb -> "Lecture des informations du Ledger..."
                    is SyncTarget.Speculos -> "Lecture des informations de Speculos..."
                },
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App ouverte sur la cible : ${info.name}. Ouvre Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                    clearDeviceEntries = true,
                    clearDiffLines = true,
                )
            } else {
                val config = client.getAppConfig()
                val compatibilityNotice =
                    if (target is SyncTarget.Usb && !LedgerAppCompatibility.supportsRealDevicePush(info.version)) {
                        " Lecture seule recommandée tant que l'app Passwords n'est pas mise à jour en ${MIN_SAFE_REAL_DEVICE_VERSION_LABEL}+."
                    } else {
                        ""
                    }
                SyncUpdate(
                    status = SyncStatus.DeviceConnected,
                    statusMessage =
                        when (target) {
                            is SyncTarget.Usb -> "Ledger connecté, app Passwords ${info.version} ouverte.$compatibilityNotice"
                            is SyncTarget.Speculos -> "Speculos connecté, app Passwords ${info.version} ouverte."
                        },
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    clearDeviceEntries = true,
                    clearDiffLines = true,
                )
            }
        }
    }

    private fun pullFromLedger() {
        val target = requireCurrentTarget() ?: return
        DiagnosticLogStore.mark("pullFromLedger target=${target.label}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.WaitingForLedgerApproval,
            preMessage = target.userActionMessage("Valide l'import sur le Ledger.", "Import depuis Speculos en cours..."),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App ouverte sur la cible : ${info.name}. Ouvre Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                )
            }
            val config = client.getAppConfig()
            updateSyncStateFromWorker(
                SyncUpdate(
                    status = SyncStatus.Dumping,
                    statusMessage = "Lecture des metadata en cours...",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                ),
            )
            val decoded = metadataCodec.decode(client.dumpMetadatas(config.storageSize))
            val backupJsonText = backupJsonCodec.toJson(decoded, app = BackupApp(name = info.name, version = info.version))
            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = target.userActionMessage("Import terminé depuis le Ledger.", "Import terminé depuis Speculos."),
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = decoded.vault.entries.size,
                diffLines = emptyList(),
                replaceLocalVault = Vault(entries = decoded.vault.entries, source = VaultSource.Local),
                replaceLocalBackupJsonText = backupJsonText,
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
            preMessage = target.userActionMessage("Valide la lecture pour comparer avec le Ledger.", "Lecture de Speculos pour comparaison..."),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App ouverte sur la cible : ${info.name}. Ouvre Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                )
            }
            val config = client.getAppConfig()
            updateSyncStateFromWorker(
                SyncUpdate(
                    status = SyncStatus.Dumping,
                    statusMessage = "Lecture des metadata en cours...",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                ),
            )
            val deviceVault = metadataCodec.decode(client.dumpMetadatas(config.storageSize)).vault
            val diff = differ.diff(before = deviceVault, after = localSnapshot)
            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = "Comparaison terminée.",
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = deviceVault.entries.size,
                diffLines = renderLedgerDiffLines(diff),
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
                            "\nOuvre Debug pour activer l'override dangereux si tu veux vraiment forcer ce push matériel.",
                )
            return
        }
        if (!localAssessment.validation.isValid) {
            syncUiState =
                syncUiState.copy(
                    status = SyncStatus.ValidationError,
                    statusMessage = localAssessment.validation.toUserMessage(),
                )
            return
        }
        DiagnosticLogStore.mark("pushToLedger confirmed target=${target.label} localEntries=${localSnapshot.entries.size}")
        performLedgerAction(
            target = target,
            preStatus = SyncStatus.Loading,
            preMessage = target.userActionMessage(
                "Écriture vers le Ledger en cours. Valide une seule fois sur le device. " +
                    "Aucune vérification automatique ne sera lancée après le push.",
                "Écriture vers Speculos en cours.",
            ),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App ouverte sur la cible : ${info.name}. Ouvre Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                )
            }
            if (target is SyncTarget.Usb && !LedgerAppCompatibility.supportsRealDevicePush(info.version)) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        "Push matériel bloqué: l'app Passwords ${info.version} est antérieure à " +
                            "${MIN_SAFE_REAL_DEVICE_VERSION_LABEL}. Mets à jour l'app sur le Ledger " +
                            "avant toute écriture réelle.",
                    appName = info.name,
                    appVersion = info.version,
                    clearDeviceEntries = true,
                    clearDiffLines = true,
                )
            }
            val config = client.getAppConfig()
            val deviceAssessment = assessLocalPushRisk(localSnapshot, config.storageSize, target.pushSafetyMode())
            if (deviceAssessment.decision == PushRiskDecision.Block && target is SyncTarget.Usb && !hardwareDangerousOverrideEnabled) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        deviceAssessment.summaryLines().joinToString(separator = "\n") +
                            "\nOuvre Debug pour activer l'override dangereux si tu veux vraiment forcer ce push matériel.",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                )
            }
            if (!deviceAssessment.validation.isValid) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage = deviceAssessment.validation.toUserMessage(),
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
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
                        "Push envoyé vers le Ledger avec override dangereux. Aucun readback automatique n'a été exécuté après l'écriture."
                    } else {
                        "Push envoyé vers le Ledger. Aucun readback automatique n'a été exécuté après l'écriture."
                    },
                    "Push envoyé vers Speculos.",
                ),
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                clearDeviceEntries = true,
                clearDiffLines = true,
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
            preMessage = target.userActionMessage("Vérification du Ledger en cours...", "Vérification de Speculos en cours..."),
        ) { client ->
            val info = client.getAppInfo()
            if (info.name != EXPECTED_APP_NAME) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.WrongAppOpened,
                    statusMessage = "App ouverte sur la cible : ${info.name}. Ouvre Passwords.",
                    appName = info.name,
                    appVersion = info.version,
                )
            }
            val config = client.getAppConfig()
            val deviceVault = metadataCodec.decode(client.dumpMetadatas(config.storageSize)).vault
            val diff = differ.diff(before = deviceVault, after = localSnapshot)
            if (diff.hasChanges) {
                return@performLedgerAction SyncUpdate(
                    status = SyncStatus.ValidationError,
                    statusMessage =
                        "Le Ledger diffère de l'état local " +
                            "(local=${localSnapshot.entries.size}, ledger=${deviceVault.entries.size}).",
                    appName = info.name,
                    appVersion = info.version,
                    storageSize = config.storageSize,
                    deviceEntries = deviceVault.entries.size,
                    diffLines = renderLedgerDiffLines(diff),
                )
            }
            SyncUpdate(
                status = SyncStatus.Success,
                statusMessage = "Le Ledger correspond à l'état local.",
                appName = info.name,
                appVersion = info.version,
                storageSize = config.storageSize,
                deviceEntries = deviceVault.entries.size,
                diffLines = renderLedgerDiffLines(diff),
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
                    statusMessage = "Aucun Ledger détecté.",
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
                    statusMessage = "Renseigne une adresse Speculos.",
                    deviceName = null,
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
                            statusMessage = "Le port Speculos doit être un entier entre 1 et 65535.",
                            deviceName = null,
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
            update.replaceLocalVault?.let { replacement ->
                val persistence =
                    applyLocalVault(
                        replacement,
                        "Vault local remplacé depuis le Ledger.",
                        backupJsonText = update.replaceLocalBackupJsonText,
                    )
                if (!persistence.persisted) {
                    statusMessage = "${update.statusMessage} Le remplacement local n'a pas pu être persisté."
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
    val confirmLabel: String = "Continuer",
)

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
    const val SyncBack = "sync_back"
    const val SyncScroll = "sync_scroll"
    const val SyncStatusMessage = "sync_status_message"
    const val SyncTransportUsb = "sync_transport_usb"
    const val SyncTransportSpeculos = "sync_transport_speculos"
    const val SyncSpeculosHost = "sync_speculos_host"
    const val SyncSpeculosPort = "sync_speculos_port"
    const val SyncRefresh = "sync_refresh"
    const val SyncPull = "sync_pull"
    const val SyncPush = "sync_push"
    const val SyncVerify = "sync_verify"
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
