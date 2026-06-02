@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.ledgerpasswords.companion.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.VaultCapacitySnapshot
import com.ledgerpasswords.companion.core.capacitySnapshot
import com.ledgerpasswords.companion.core.model.CharsetFlag
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.validation.VaultValidator
import kotlinx.coroutines.launch

private enum class AppPanel {
    Home,
    Editor,
    Sync,
    Settings,
    About,
    Debug,
}

@Composable
internal fun LedgerPasswordsCompanionShell(
    localVault: Vault,
    localVaultMessage: String,
    showSyncScreen: Boolean,
    showSettingsScreen: Boolean,
    showAboutScreen: Boolean,
    showDebugScreen: Boolean,
    entryEditorState: EntryEditorState?,
    syncUiState: SyncUiState,
    transportMode: SyncTransportMode,
    speculosHost: String,
    speculosPortText: String,
    hardwarePushConfirmationEnabled: Boolean,
    hardwareDangerousOverrideEnabled: Boolean,
    onAddEntry: () -> Unit,
    onEditEntry: (PasswordIdentifier) -> Unit,
    onDismissEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDebug: () -> Unit,
    onBackToHome: () -> Unit,
    onEditorNicknameChanged: (String) -> Unit,
    onEditorCharsetToggled: (CharsetFlag, Boolean) -> Unit,
    onHardwarePushConfirmationChanged: (Boolean) -> Unit,
    onHardwareDangerousOverrideChanged: (Boolean) -> Unit,
    onSaveEntry: () -> Unit,
    onDeleteEntry: () -> Unit,
    onImportBackup: () -> Unit,
    onExportBackup: () -> Unit,
    onOpenSync: () -> Unit,
    onTransportModeChanged: (SyncTransportMode) -> Unit,
    onSpeculosHostChanged: (String) -> Unit,
    onSpeculosPortChanged: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshDevice: () -> Unit,
    onPullFromLedger: () -> Unit,
    onCompareWithLedger: () -> Unit,
    onPushToLedger: () -> Unit,
    onVerifyLedger: () -> Unit,
    pushConfirmationDialogState: PushConfirmationDialogState?,
    onDismissPushConfirmation: () -> Unit,
    onConfirmPushToLedger: () -> Unit,
) {
    val panel =
        when {
            entryEditorState != null -> AppPanel.Editor
            showSyncScreen -> AppPanel.Sync
            showSettingsScreen -> AppPanel.Settings
            showAboutScreen -> AppPanel.About
            showDebugScreen -> AppPanel.Debug
            else -> AppPanel.Home
        }
    val storageSize = syncUiState.storageSize ?: LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    fun copyNicknameToClipboard(nickname: String) {
        clipboardManager.setText(AnnotatedString(nickname))
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Identifiant copié")
        }
    }

    LedgerWarmTheme {
        pushConfirmationDialogState?.let { dialogState ->
            AlertDialog(
                onDismissRequest = onDismissPushConfirmation,
                title = { Text(dialogState.title) },
                text = {
                    Text(dialogState.body)
                },
                confirmButton =
                    if (dialogState.canConfirm) {
                        {
                            TextButton(onClick = onConfirmPushToLedger) {
                                Text(dialogState.confirmLabel)
                            }
                        }
                    } else {
                        {}
                    },
                dismissButton = {
                    TextButton(onClick = onDismissPushConfirmation) {
                        Text(if (dialogState.canConfirm) "Annuler" else "Fermer")
                    }
                },
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF110C09), Color(0xFF1A110C), Color(0xFF120D0A)),
                        ),
                    ),
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState)
                },
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = panel.title(),
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        navigationIcon = {
                            if (panel == AppPanel.Home) {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "Menu")
                                    }
                                    OverflowMenu(
                                        expanded = menuExpanded,
                                        onDismiss = { menuExpanded = false },
                                        onOpenSettings = {
                                            menuExpanded = false
                                            onOpenSettings()
                                        },
                                        onOpenAbout = {
                                            menuExpanded = false
                                            onOpenAbout()
                                        },
                                        onOpenDebug = {
                                            menuExpanded = false
                                            onOpenDebug()
                                        },
                                    )
                                }
                            } else {
                                IconButton(onClick = onBackToHome) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour")
                                }
                            }
                        },
                        actions = {
                            if (panel != AppPanel.Home) {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "Menu")
                                    }
                                    OverflowMenu(
                                        expanded = menuExpanded,
                                        onDismiss = { menuExpanded = false },
                                        onOpenSettings = {
                                            menuExpanded = false
                                            onOpenSettings()
                                        },
                                        onOpenAbout = {
                                            menuExpanded = false
                                            onOpenAbout()
                                        },
                                        onOpenDebug = {
                                            menuExpanded = false
                                            onOpenDebug()
                                        },
                                    )
                                }
                            }
                        },
                    )
                },
                floatingActionButton = {
                    if (panel == AppPanel.Home) {
                        FloatingActionButton(
                            onClick = onAddEntry,
                            modifier = Modifier.testTag(UiTags.HomeAddEntry),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                },
            ) { padding ->
                when (panel) {
                    AppPanel.Home ->
                        HomeDashboardScreen(
                            modifier = Modifier.padding(padding),
                            vault = localVault,
                            localVaultMessage = localVaultMessage,
                            onEditEntry = onEditEntry,
                            onCopyEntry = ::copyNicknameToClipboard,
                            onImportBackup = onImportBackup,
                            onExportBackup = onExportBackup,
                            onOpenSync = onOpenSync,
                        )

                    AppPanel.Editor ->
                        EntryWorkbenchScreen(
                            modifier = Modifier.padding(padding),
                            state = requireNotNull(entryEditorState),
                            localVault = localVault,
                            storageSize = storageSize,
                            onNicknameChange = onEditorNicknameChanged,
                            onCharsetToggled = onEditorCharsetToggled,
                            onSave = onSaveEntry,
                            onDelete = onDeleteEntry,
                            onCancel = onDismissEditor,
                        )

                    AppPanel.Sync ->
                        SyncOperationsScreen(
                            modifier = Modifier.padding(padding),
                            localVault = localVault,
                            syncUiState = syncUiState,
                            transportMode = transportMode,
                            dangerousOverrideEnabled = hardwareDangerousOverrideEnabled,
                            onRequestPermission = onRequestPermission,
                            onRefreshDevice = onRefreshDevice,
                            onPullFromLedger = onPullFromLedger,
                            onCompareWithLedger = onCompareWithLedger,
                            onPushToLedger = onPushToLedger,
                            onVerifyLedger = onVerifyLedger,
                            onOpenDebug = onOpenDebug,
                        )

                    AppPanel.Settings ->
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                            confirmationEnabled = hardwarePushConfirmationEnabled,
                            onConfirmationChanged = onHardwarePushConfirmationChanged,
                            dangerousOverrideEnabled = hardwareDangerousOverrideEnabled,
                            onOpenDebug = onOpenDebug,
                        )

                    AppPanel.About ->
                        AboutScreen(
                            modifier = Modifier.padding(padding),
                            storageSize = storageSize,
                            localVault = localVault,
                        )

                    AppPanel.Debug ->
                        DebugLabScreen(
                            modifier = Modifier.padding(padding),
                            transportMode = transportMode,
                            speculosHost = speculosHost,
                            speculosPortText = speculosPortText,
                            syncUiState = syncUiState,
                            onTransportModeChanged = onTransportModeChanged,
                            onSpeculosHostChanged = onSpeculosHostChanged,
                            onSpeculosPortChanged = onSpeculosPortChanged,
                            dangerousOverrideEnabled = hardwareDangerousOverrideEnabled,
                            onDangerousOverrideChanged = onHardwareDangerousOverrideChanged,
                            onRefreshDevice = onRefreshDevice,
                            onOpenSync = onOpenSync,
                        )
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Réglages") },
            onClick = onOpenSettings,
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("À propos") },
            onClick = onOpenAbout,
            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("Debug") },
            onClick = onOpenDebug,
            leadingIcon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
        )
    }
}

@Composable
private fun HomeDashboardScreen(
    modifier: Modifier,
    vault: Vault,
    localVaultMessage: String,
    onEditEntry: (PasswordIdentifier) -> Unit,
    onCopyEntry: (String) -> Unit,
    onImportBackup: () -> Unit,
    onExportBackup: () -> Unit,
    onOpenSync: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var actionsExpanded by remember { mutableStateOf(false) }
    val filteredEntries =
        remember(vault.entries, search) {
            val needle = search.trim().lowercase()
            vault.entries
                .sortedBy { it.nickname.lowercase() }
                .filter { needle.isBlank() || it.nickname.lowercase().contains(needle) }
        }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(
                title = "Vault local",
                subtitle = "${vault.entries.size} identifiants disponibles",
            ) {
                Text(
                    localVaultMessage,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onOpenSync,
                        modifier = Modifier.weight(1f).testTag(UiTags.HomeOpenSync),
                    ) {
                        Icon(Icons.Rounded.CloudSync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Synchroniser")
                    }
                    Box {
                        OutlinedButton(onClick = { actionsExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Actions")
                        }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Importer backup.json") },
                                onClick = {
                                    actionsExpanded = false
                                    onImportBackup()
                                },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter backup.json") },
                                onClick = {
                                    actionsExpanded = false
                                    onExportBackup()
                                },
                                leadingIcon = { Icon(Icons.Rounded.Upload, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Identifiants", subtitle = "Appui sur une ligne pour éditer, appui long ou icône pour copier") {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filtrer les nicknames") },
                    singleLine = true,
                )
            }
        }

        if (filteredEntries.isEmpty()) {
            item {
                SectionCard(title = "Aucun résultat") {
                    Text(
                        if (vault.entries.isEmpty()) {
                            "Ajoute un identifiant ou importe un backup.json pour commencer."
                        } else {
                            "Aucun identifiant ne correspond à ce filtre."
                        },
                    )
                }
            }
        } else {
            items(filteredEntries, key = { it.nickname }) { entry ->
                CompactEntryRow(
                    entry = entry,
                    onEdit = { onEditEntry(entry) },
                    onCopy = { onCopyEntry(entry.nickname) },
                )
            }
        }
    }
}

@Composable
private fun EntryWorkbenchScreen(
    modifier: Modifier,
    state: EntryEditorState,
    localVault: Vault,
    storageSize: Int,
    onNicknameChange: (String) -> Unit,
    onCharsetToggled: (CharsetFlag, Boolean) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val draftVault = buildDraftVault(localVault, state)
    val previewCapacity = draftVault?.capacitySnapshot(storageSize)
    val previewValidation = draftVault?.let { VaultValidator(storageSize).validate(it) }
    val nicknameTooLong = state.nicknameByteCount > LedgerPasswordsLimits.MAX_NICKNAME_BYTES
    val canSave =
        state.nickname.trim().isNotBlank() &&
            state.selectedFlags.isNotEmpty() &&
            !nicknameTooLong &&
            (previewValidation?.isValid ?: false)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = if (state.isCreation) "Nouvel identifiant" else "Modifier l’identifiant",
            subtitle = "Le nickname est limité par Ledger. Toute modification peut changer le mot de passe généré.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("${state.nicknameByteCount}/${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} octets UTF-8")
                previewCapacity?.let {
                    StatPill("${it.usedBytes}/${it.storageSize} octets après sauvegarde")
                }
            }
        }

        if (!state.isCreation && state.originalNickname != state.nickname.trim()) {
            SectionCard(title = "Impact") {
                Text("Renommer un identifiant modifie le mot de passe que Ledger Passwords générera ensuite.")
            }
        }

        SectionCard(title = "Nickname", subtitle = "Maximum 19 octets UTF-8") {
            OutlinedTextField(
                value = state.nickname,
                onValueChange = onNicknameChange,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.EntryNicknameField),
                label = { Text("Nickname") },
                singleLine = true,
                isError = nicknameTooLong,
                supportingText = {
                    Text(
                        when {
                            nicknameTooLong -> "Trop long pour Ledger. Raccourcis le nickname."
                            state.nickname.trim().isBlank() -> "Le nickname ne doit pas être vide."
                            else -> "Compte tenu en octets UTF-8, pas seulement en caractères."
                        },
                    )
                },
            )
        }

        SectionCard(title = "Charsets", subtitle = "Choisis au moins un groupe autorisé") {
            CharsetFlag.entries.forEach { flag ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onCharsetToggled(flag, flag !in state.selectedFlags) }
                            .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(if (flag in state.selectedFlags) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(flag.frenchLabel(), fontWeight = FontWeight.Medium)
                        Text(flag.ledgerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = flag in state.selectedFlags,
                        onCheckedChange = { checked -> onCharsetToggled(flag, checked) },
                    )
                }
            }
        }

        previewCapacity?.let {
            SectionCard(
                title = "Capacité Ledger",
                subtitle = previewValidation?.takeIf { !it.isValid }?.issues?.joinToString(" • ") { issue -> issue.message },
            ) {
                LinearProgressIndicator(
                    progress = { it.usageRatio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("${it.usedBytes}/${it.storageSize} octets utilisés, ${it.remainingBytes.coerceAtLeast(0)} restants")
                Text("${it.entryCount}/${it.maxEntryCount} slots utilisés")
            }
        }

        state.errorMessage?.let {
            SectionCard(title = "Blocage") {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f).testTag(UiTags.EntrySave)) {
                Text(if (state.isCreation) "Ajouter" else "Sauvegarder")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Annuler")
            }
        }
        if (!state.isCreation) {
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Supprimer cet identifiant")
            }
        }
    }
}

@Composable
private fun SyncOperationsScreen(
    modifier: Modifier,
    localVault: Vault,
    syncUiState: SyncUiState,
    transportMode: SyncTransportMode,
    dangerousOverrideEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onRefreshDevice: () -> Unit,
    onPullFromLedger: () -> Unit,
    onCompareWithLedger: () -> Unit,
    onPushToLedger: () -> Unit,
    onVerifyLedger: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val busy =
        syncUiState.status in setOf(
            SyncStatus.WaitingForLedgerApproval,
            SyncStatus.Dumping,
            SyncStatus.Loading,
            SyncStatus.Verifying,
        )
    val localCapacity = localVault.capacitySnapshot(syncUiState.storageSize ?: LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE)

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(UiTags.SyncScroll),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroCard(
                title = "Synchronisation ${if (transportMode == SyncTransportMode.Usb) "matérielle" else "émulée"}",
                subtitle = "Les opérations normales restent ici. Les transports et réglages Speculos sont isolés dans Debug.",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill(syncUiState.deviceName ?: "Aucune cible")
                    StatPill("${localVault.entries.size} locaux")
                    StatPill("${syncUiState.deviceEntries?.toString() ?: "-"} sur la cible")
                }
            }
        }

        item {
            SectionCard(title = "État courant", subtitle = syncUiState.status.name) {
                Text(syncUiState.statusMessage, modifier = Modifier.testTag(UiTags.SyncStatusMessage))
                Spacer(Modifier.height(10.dp))
                Text("App : ${syncUiState.appName ?: "-"} ${syncUiState.appVersion ?: ""}".trim())
                Text("Storage : ${syncUiState.storageSize?.toString() ?: "-"}")
                Text("Capacité locale : ${localCapacity.usedBytes}/${localCapacity.storageSize} octets")
                if (transportMode == SyncTransportMode.Usb && dangerousOverrideEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Override dangereux actif : la policy hardware-safe peut être contournée depuis Debug.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (transportMode == SyncTransportMode.Speculos) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenDebug) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Outils debug")
                    }
                }
            }
        }

        item {
            SectionCard(title = "Actions") {
                ActionButton("Rafraîchir la cible", Icons.Rounded.CloudSync, onRefreshDevice, enabled = !busy, tag = UiTags.SyncRefresh)
                if (transportMode == SyncTransportMode.Usb) {
                    Spacer(Modifier.height(10.dp))
                    ActionButton("Demander la permission USB", Icons.Rounded.Verified, onRequestPermission, enabled = !busy)
                }
                Spacer(Modifier.height(10.dp))
                ActionButton("Importer depuis Ledger", Icons.Rounded.Download, onPullFromLedger, enabled = !busy, tag = UiTags.SyncPull)
                Spacer(Modifier.height(10.dp))
                ActionButton("Comparer le local avec la cible", Icons.Rounded.Edit, onCompareWithLedger, enabled = !busy)
                Spacer(Modifier.height(10.dp))
                ActionButton("Exporter le local vers Ledger", Icons.Rounded.Upload, onPushToLedger, enabled = !busy, tag = UiTags.SyncPush)
                Spacer(Modifier.height(10.dp))
                ActionButton("Vérifier la cohérence finale", Icons.Rounded.Verified, onVerifyLedger, enabled = !busy, tag = UiTags.SyncVerify)
            }
        }

        if (syncUiState.diffLines.isNotEmpty()) {
            item {
                SectionCard(title = "Diff local vs Ledger") {
                    syncUiState.diffLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    confirmationEnabled: Boolean,
    onConfirmationChanged: (Boolean) -> Unit,
    dangerousOverrideEnabled: Boolean,
    onOpenDebug: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = "Réglages",
            subtitle = "Les fonctions normales restent centrées sur le vault local et la sync utilisateur.",
        ) {
            Text("Le thème suit une palette sombre chaude pensée pour réduire l’éblouissement pendant la manipulation du Ledger.")
        }

        SectionCard(title = "Sécurité des écritures") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Confirmer avant chaque push matériel", fontWeight = FontWeight.Medium)
                    Text(
                        "Conserve un popup de confirmation avant tout export vers un vrai Ledger.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = confirmationEnabled, onCheckedChange = onConfirmationChanged)
            }
        }

        SectionCard(title = "Debug séparé") {
            Text("Les réglages de transport Speculos et l’émulation restent dans un écran Debug dédié.")
            if (dangerousOverrideEnabled) {
                Text(
                    "Un override dangereux est actuellement actif dans Debug.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenDebug) {
                Icon(Icons.Rounded.BugReport, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Ouvrir Debug")
            }
        }
    }
}

@Composable
private fun AboutScreen(
    modifier: Modifier,
    storageSize: Int,
    localVault: Vault,
) {
    val capacity = localVault.capacitySnapshot(storageSize)
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = "À propos",
            subtitle = "Companion Android pour préparer, sauvegarder et synchroniser les metadata Ledger Passwords.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("Companion Android")
                StatPill("${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} octets max / nickname")
                StatPill("$storageSize octets de storage")
            }
        }

        SectionCard(title = "Contraintes Ledger") {
            Text("Un nickname est limité à 19 octets UTF-8.")
            Text("Le bloc metadata est limité par le storage de l’app Ledger Passwords.")
            Text("Toute modification locale est validée avant export pour éviter un metadata invalide.")
        }

        SectionCard(title = "Capacité locale actuelle") {
            LinearProgressIndicator(
                progress = { capacity.usageRatio.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text("${capacity.entryCount}/${capacity.maxEntryCount} identifiants théoriques")
            Text("${capacity.usedBytes}/${capacity.storageSize} octets utilisés")
            Text("${capacity.remainingBytes.coerceAtLeast(0)} octets restants")
        }

        SectionCard(title = "Diagnostics") {
            Text("Le log de diagnostic Android est stocké dans ${MainActivity.DIAGNOSTIC_LOG_FILE_NAME}.")
            Text("Le vrai push matériel reste manuel, sans readback automatique après l’écriture.")
        }
    }
}

@Composable
private fun DebugLabScreen(
    modifier: Modifier,
    transportMode: SyncTransportMode,
    speculosHost: String,
    speculosPortText: String,
    syncUiState: SyncUiState,
    onTransportModeChanged: (SyncTransportMode) -> Unit,
    onSpeculosHostChanged: (String) -> Unit,
    onSpeculosPortChanged: (String) -> Unit,
    dangerousOverrideEnabled: Boolean,
    onDangerousOverrideChanged: (Boolean) -> Unit,
    onRefreshDevice: () -> Unit,
    onOpenSync: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = "Debug & Lab",
            subtitle = "Zone séparée pour transports de test, Speculos et diagnostic de cible.",
        ) {
            Text("L’interface normale n’expose plus ces réglages directement.")
        }

        SectionCard(title = "Transport actif", subtitle = syncUiState.deviceName ?: "Aucune cible") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).testTag(UiTags.SyncTransportUsb),
                    onClick = { onTransportModeChanged(SyncTransportMode.Usb) },
                    enabled = transportMode != SyncTransportMode.Usb,
                ) {
                    Text("USB réel")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f).testTag(UiTags.SyncTransportSpeculos),
                    onClick = { onTransportModeChanged(SyncTransportMode.Speculos) },
                    enabled = transportMode != SyncTransportMode.Speculos,
                ) {
                    Text("Speculos")
                }
            }
            if (transportMode == SyncTransportMode.Speculos) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = speculosHost,
                    onValueChange = onSpeculosHostChanged,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.SyncSpeculosHost),
                    label = { Text("Host Speculos") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = speculosPortText,
                    onValueChange = onSpeculosPortChanged,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.SyncSpeculosPort),
                    label = { Text("Port APDU Speculos") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Sur l’émulateur Android, 10.0.2.2 pointe vers le PC hôte.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard(title = "Actions debug") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Override dangereux hardware-safe", fontWeight = FontWeight.Medium)
                    Text(
                        "Permet de forcer un push matériel même si la policy de sécurité le bloquerait normalement.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = dangerousOverrideEnabled, onCheckedChange = onDangerousOverrideChanged)
            }
            Spacer(Modifier.height(10.dp))
            ActionButton("Rafraîchir la cible", Icons.Rounded.CloudSync, onRefreshDevice)
            Spacer(Modifier.height(10.dp))
            ActionButton("Ouvrir l’écran de sync", Icons.Rounded.Verified, onOpenSync)
        }
    }
}

@Composable
private fun HeroCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.82f),
                                LedgerWarmPalette.Panel.copy(alpha = 0.95f),
                            ),
                        ),
                    ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(subtitle, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f))
                    content()
                },
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            content()
        }
    }
}

@Composable
private fun CompactEntryRow(
    entry: PasswordIdentifier,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier =
                Modifier.combinedClickable(
                    onClick = onEdit,
                    onLongClick = onCopy,
                ),
            headlineContent = {
                Text(
                    entry.nickname,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copier ${entry.nickname}")
                }
            },
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun StatPill(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
        shape = CircleShape,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun AppPanel.title(): String =
    when (this) {
        AppPanel.Home -> "Ledger Companion"
        AppPanel.Editor -> "Édition locale"
        AppPanel.Sync -> "Synchronisation"
        AppPanel.Settings -> "Réglages"
        AppPanel.About -> "À propos"
        AppPanel.Debug -> "Debug"
    }

private fun buildDraftVault(localVault: Vault, state: EntryEditorState): Vault? {
    val nickname = state.nickname.trim()
    if (nickname.isBlank() || state.selectedFlags.isEmpty()) return null

    val updatedEntry = PasswordIdentifier(nickname = nickname, charsets = state.selectedFlags.toCharsetPolicy())
    val nextEntries =
        if (state.originalNickname == null) {
            localVault.entries + updatedEntry
        } else {
            localVault.entries.map { current ->
                if (current.nickname == state.originalNickname) updatedEntry else current
            }
        }
    return Vault(entries = nextEntries.sortedBy { it.nickname.lowercase() }, source = localVault.source)
}

private fun CharsetFlag.frenchLabel(): String =
    when (this) {
        CharsetFlag.UPPERCASE -> "Majuscules"
        CharsetFlag.LOWERCASE -> "Minuscules"
        CharsetFlag.NUMBERS -> "Chiffres"
        CharsetFlag.MINUS -> "Tiret"
        CharsetFlag.UNDERLINE -> "Underscore"
        CharsetFlag.SPACE -> "Espace"
        CharsetFlag.SPECIAL -> "Spéciaux"
        CharsetFlag.BRACKETS -> "Brackets"
    }

private fun Set<CharsetFlag>.toCharsetPolicy(): CharsetPolicy =
    if (size == CharsetFlag.entries.size) {
        CharsetPolicy.All
    } else {
        CharsetPolicy.fromFlags(sortedBy { it.ordinal })
    }
