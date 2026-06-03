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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
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
    onSynchronize: () -> Unit,
    onPullFromLedger: () -> Unit,
    onCompareWithLedger: () -> Unit,
    onPushToLedger: () -> Unit,
    onVerifyLedger: () -> Unit,
    startupWarningEnabled: Boolean,
    onStartupWarningEnabledChanged: (Boolean) -> Unit,
    appDialogState: AppDialogState?,
    onDismissAppDialog: () -> Unit,
    onConfirmAppDialog: () -> Unit,
    onStartupWarningDismissPreferenceChanged: (Boolean) -> Unit,
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
            snackbarHostState.showSnackbar("Identifier copied")
        }
    }

    LedgerWarmTheme {
        appDialogState?.let { dialogState ->
            AlertDialog(
                onDismissRequest = {
                    if (dialogState.dismissLabel != null) {
                        onDismissAppDialog()
                    }
                },
                title = { Text(dialogState.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(dialogState.body)
                        if (dialogState is AppDialogState.StartupWarning) {
                            Row(
                                modifier =
                                    Modifier
                                        .testTag(UiTags.DialogStartupDontShowAgain)
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            onStartupWarningDismissPreferenceChanged(!dialogState.dontShowAgain)
                                        }
                                        .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Checkbox(
                                    checked = dialogState.dontShowAgain,
                                    onCheckedChange = { checked ->
                                        onStartupWarningDismissPreferenceChanged(checked)
                                    },
                                )
                                Text("Don't show on startup")
                            }
                        }
                    }
                },
                confirmButton =
                    if (dialogState.canConfirm) {
                        {
                            TextButton(onClick = onConfirmAppDialog) {
                                Text(dialogState.confirmLabel)
                            }
                        }
                    } else {
                        {}
                    },
                dismissButton =
                    dialogState.dismissLabel?.let { dismissLabel ->
                        {
                            TextButton(onClick = onDismissAppDialog) {
                                Text(dismissLabel)
                            }
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
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                            onSynchronize = onSynchronize,
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
                            startupWarningEnabled = startupWarningEnabled,
                            onStartupWarningEnabledChanged = onStartupWarningEnabledChanged,
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
            text = { Text("Settings") },
            onClick = onOpenSettings,
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("About") },
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
                title = "Local vault",
                subtitle = "${vault.entries.size} identifiers available",
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
                        Text("Sync")
                    }
                    Box {
                        OutlinedButton(onClick = { actionsExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Actions")
                        }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Import backup.json") },
                                onClick = {
                                    actionsExpanded = false
                                    onImportBackup()
                                },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export backup.json") },
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
            SectionCard(title = "Identifiers", subtitle = "Tap a row to edit, long-press or use the icon to copy") {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filter nicknames") },
                    singleLine = true,
                )
            }
        }

        if (filteredEntries.isEmpty()) {
            item {
                SectionCard(title = "No results") {
                    Text(
                        if (vault.entries.isEmpty()) {
                            "Add an identifier or import a backup.json file to get started."
                        } else {
                            "No identifier matches this filter."
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
            title = if (state.isCreation) "New identifier" else "Edit identifier",
            subtitle = "Nickname length is limited by Ledger. Any change can alter the generated password.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("${state.nicknameByteCount}/${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} UTF-8 bytes")
                previewCapacity?.let {
                    StatPill("${it.usedBytes}/${it.storageSize} bytes after save")
                }
            }
        }

        if (!state.isCreation && state.originalNickname != state.nickname.trim()) {
            SectionCard(title = "Impact") {
                Text("Renaming an identifier changes the password Ledger Passwords will generate afterward.")
            }
        }

        SectionCard(title = "Nickname", subtitle = "Maximum 19 UTF-8 bytes") {
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
                            nicknameTooLong -> "Too long for Ledger. Shorten the nickname."
                            state.nickname.trim().isBlank() -> "Nickname must not be blank."
                            else -> "Counted in UTF-8 bytes, not just characters."
                        },
                    )
                },
            )
        }

        SectionCard(title = "Charsets", subtitle = "Choose at least one allowed set") {
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
                        Text(flag.displayLabel(), fontWeight = FontWeight.Medium)
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
                title = "Ledger capacity",
                subtitle = previewValidation?.takeIf { !it.isValid }?.issues?.joinToString(" • ") { issue -> issue.message },
            ) {
                LinearProgressIndicator(
                    progress = { it.usageRatio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("${it.usedBytes}/${it.storageSize} bytes used, ${it.remainingBytes.coerceAtLeast(0)} remaining")
                Text("${it.entryCount}/${it.maxEntryCount} slots used")
            }
        }

        state.errorMessage?.let {
            SectionCard(title = "Blocked") {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f).testTag(UiTags.EntrySave)) {
                Text(if (state.isCreation) "Add" else "Save")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
        }
        if (!state.isCreation) {
            Button(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Text("Delete this identifier")
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
    onSynchronize: () -> Unit,
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
    val listState = rememberLazyListState()

    LaunchedEffect(syncUiState.diffSummary, syncUiState.diffLines) {
        if (syncUiState.diffLines.isNotEmpty()) {
            listState.animateScrollToItem(2)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(UiTags.SyncScroll),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroCard(
                title = if (transportMode == SyncTransportMode.Usb) "Ledger sync" else "Test sync",
                subtitle = "Read, compare, then write to the target only if you understand the diff.",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill(syncUiState.deviceName ?: "No target")
                    StatPill("${localVault.entries.size} local entries")
                    StatPill("${syncUiState.deviceEntries?.toString() ?: "-"} on target")
                }
            }
        }

        item {
            SectionCard(title = "Current state", subtitle = syncUiState.status.displayLabel()) {
                Text(syncUiState.statusMessage, modifier = Modifier.testTag(UiTags.SyncStatusMessage))
                Spacer(Modifier.height(10.dp))
                Text("App: ${syncUiState.appName ?: "-"} ${syncUiState.appVersion ?: ""}".trim())
                Text("Storage: ${syncUiState.storageSize?.toString() ?: "-"}")
                Text("Local capacity: ${localCapacity.usedBytes}/${localCapacity.storageSize} bytes")
                if (syncUiState.showVerifyCallToAction) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onVerifyLedger, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Verified, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Verify now")
                    }
                }
                if (transportMode == SyncTransportMode.Usb && dangerousOverrideEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Dangerous override enabled: the hardware-safe policy can be bypassed from Debug.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (syncUiState.diffLines.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Local vs Ledger diff",
                    subtitle = syncUiState.diffSummary,
                    modifier = Modifier.testTag(UiTags.SyncDiffSection),
                ) {
                    syncUiState.diffLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Synchronize",
                subtitle = "Read the target, merge obvious additions, then verify before replacing the local vault.",
            ) {
                ActionButton(
                    "Synchronize local and target",
                    Icons.Rounded.CloudSync,
                    onSynchronize,
                    enabled = !busy,
                    tag = UiTags.SyncSynchronize,
                )
            }
        }

        item {
            SectionCard(title = "Advanced: read & compare") {
                ActionButton("Refresh target", Icons.Rounded.CloudSync, onRefreshDevice, enabled = !busy, tag = UiTags.SyncRefresh)
                if (transportMode == SyncTransportMode.Usb) {
                    Spacer(Modifier.height(10.dp))
                    ActionButton("Request USB permission", Icons.Rounded.Verified, onRequestPermission, enabled = !busy)
                }
                Spacer(Modifier.height(10.dp))
                ActionButton("Import from Ledger", Icons.Rounded.Download, onPullFromLedger, enabled = !busy, tag = UiTags.SyncPull)
                Spacer(Modifier.height(10.dp))
                ActionButton("Compare local with target", Icons.Rounded.Edit, onCompareWithLedger, enabled = !busy)
            }
        }

        item {
            SectionCard(
                title = "Advanced: write & final check",
                subtitle = "Export replaces the entire metadata block currently on the target.",
                modifier = Modifier.testTag(UiTags.SyncWriteSection),
            ) {
                ActionButton(
                    label = "Export local to Ledger",
                    icon = Icons.Rounded.Upload,
                    onClick = onPushToLedger,
                    enabled = !busy,
                    tone = ActionTone.Caution,
                    tag = UiTags.SyncPush,
                )
                Spacer(Modifier.height(10.dp))
                ActionButton(
                    label = "Verify final consistency",
                    icon = Icons.Rounded.Verified,
                    onClick = onVerifyLedger,
                    enabled = !busy,
                    tag = UiTags.SyncVerify,
                )
                if (transportMode == SyncTransportMode.Speculos) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Test tools")
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
    startupWarningEnabled: Boolean,
    onStartupWarningEnabledChanged: (Boolean) -> Unit,
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
            title = "Settings",
            subtitle = "Confirm sensitive actions and choose what appears on startup.",
        ) {
            Text("Test settings and risky overrides stay isolated in the Debug screen.")
        }

        SectionCard(title = "Write safety") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Confirm before every hardware push", fontWeight = FontWeight.Medium)
                    Text(
                        "Keep a confirmation prompt before any export to a real Ledger.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = confirmationEnabled,
                    onCheckedChange = onConfirmationChanged,
                    modifier = Modifier.testTag(UiTags.SettingsPushConfirmationSwitch),
                )
            }
        }

        SectionCard(title = "Startup warning") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show experimental warning", fontWeight = FontWeight.Medium)
                    Text(
                        "Reminds you on launch that the app remains experimental before any real write.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = startupWarningEnabled,
                    onCheckedChange = onStartupWarningEnabledChanged,
                    modifier = Modifier.testTag(UiTags.SettingsStartupWarningSwitch),
                )
            }
        }

        SectionCard(title = "Separate debug") {
            Text("Test transports and the dangerous override remain grouped in a separate screen.")
            if (dangerousOverrideEnabled) {
                Text(
                    "A dangerous override is currently enabled in Debug.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenDebug) {
                Icon(Icons.Rounded.BugReport, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Debug")
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
            title = "About",
            subtitle = "Android companion to prepare, back up, and sync Ledger Passwords metadata.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("Android companion")
                StatPill("${LedgerPasswordsLimits.MAX_NICKNAME_BYTES} bytes max / nickname")
                StatPill("$storageSize bytes of storage")
            }
        }

        SectionCard(title = "Ledger constraints") {
            Text("A nickname is limited to 19 UTF-8 bytes.")
            Text("The metadata block is limited by the Ledger Passwords app storage.")
            Text("Every local change is validated before export to avoid invalid metadata.")
        }

        SectionCard(title = "Current local capacity") {
            LinearProgressIndicator(
                progress = { capacity.usageRatio.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text("${capacity.entryCount}/${capacity.maxEntryCount} theoretical identifiers")
            Text("${capacity.usedBytes}/${capacity.storageSize} bytes used")
            Text("${capacity.remainingBytes.coerceAtLeast(0)} bytes remaining")
        }

        SectionCard(title = "Diagnostics") {
            Text("The Android diagnostic log is stored in ${MainActivity.DIAGNOSTIC_LOG_FILE_NAME}.")
            Text("Real hardware pushes remain manual, with no automatic readback after writing.")
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
            subtitle = "Separate area for test transports, Speculos, and target diagnostics.",
        ) {
            Text("The normal interface no longer exposes these settings directly.")
        }

        SectionCard(title = "Active transport", subtitle = syncUiState.deviceName ?: "No target") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).testTag(UiTags.SyncTransportUsb),
                    onClick = { onTransportModeChanged(SyncTransportMode.Usb) },
                    enabled = transportMode != SyncTransportMode.Usb,
                ) {
                    Text("Real USB")
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
                    label = { Text("Speculos host") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = speculosPortText,
                    onValueChange = onSpeculosPortChanged,
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.SyncSpeculosPort),
                    label = { Text("Speculos APDU port") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "On the Android emulator, 10.0.2.2 points to the host PC.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard(title = "Debug actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dangerous hardware-safe override", fontWeight = FontWeight.Medium)
                    Text(
                        "Allows forcing a hardware push even if the safety policy would normally block it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = dangerousOverrideEnabled, onCheckedChange = onDangerousOverrideChanged)
            }
            Spacer(Modifier.height(10.dp))
            ActionButton("Refresh target", Icons.Rounded.CloudSync, onRefreshDevice)
            Spacer(Modifier.height(10.dp))
            ActionButton("Open sync screen", Icons.Rounded.Verified, onOpenSync)
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
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
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
            supportingContent = {
                Text(
                    entry.charsets.toLedgerNames().joinToString(" • "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy ${entry.nickname}")
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
    tone: ActionTone = ActionTone.Default,
    tag: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        colors =
            when (tone) {
                ActionTone.Default -> ButtonDefaults.buttonColors()
                ActionTone.Caution ->
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                ActionTone.Danger ->
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
            },
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

private enum class ActionTone {
    Default,
    Caution,
    Danger,
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
        AppPanel.Editor -> "Local editing"
        AppPanel.Sync -> "Sync"
        AppPanel.Settings -> "Settings"
        AppPanel.About -> "About"
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

private fun CharsetFlag.displayLabel(): String =
    when (this) {
        CharsetFlag.UPPERCASE -> "Uppercase"
        CharsetFlag.LOWERCASE -> "Lowercase"
        CharsetFlag.NUMBERS -> "Numbers"
        CharsetFlag.MINUS -> "Hyphen"
        CharsetFlag.UNDERLINE -> "Underscore"
        CharsetFlag.SPACE -> "Space"
        CharsetFlag.SPECIAL -> "Specials"
        CharsetFlag.BRACKETS -> "Brackets"
    }

private fun Set<CharsetFlag>.toCharsetPolicy(): CharsetPolicy =
    if (size == CharsetFlag.entries.size) {
        CharsetPolicy.All
    } else {
        CharsetPolicy.fromFlags(sortedBy { it.ordinal })
    }
