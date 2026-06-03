package com.ledgerpasswords.companion.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ledgerpasswords.companion.android.storage.SyncShadowState
import com.ledgerpasswords.companion.android.storage.SyncTargetKind
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.sync.ThreeWayConflictReason
import com.ledgerpasswords.companion.core.sync.ThreeWayMergeConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerPasswordsCompanionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startupWarningDialogCanDisableFutureDisplay() {
        var dialogState by mutableStateOf<AppDialogState?>(AppDialogState.StartupWarning())
        var showStartupWarning by mutableStateOf(true)

        composeRule.setShellContent(
            startupWarningEnabled = { showStartupWarning },
            appDialogState = { dialogState },
            onConfirmAppDialog = {
                val startupDialog = dialogState as AppDialogState.StartupWarning
                if (startupDialog.dontShowAgain) {
                    showStartupWarning = false
                }
                dialogState = null
            },
            onStartupWarningDismissPreferenceChanged = { checked ->
                dialogState = (dialogState as AppDialogState.StartupWarning).copy(dontShowAgain = checked)
            },
        )

        composeRule.onNodeWithText("Experimental warning").assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.DialogStartupDontShowAgain).performClick()
        composeRule.onNodeWithText("Continue").performClick()

        composeRule.waitUntilNodeCount("Experimental warning", expectedCount = 0)
        assertFalse(showStartupWarning)
    }

    @Test
    fun settingsScreenTogglesStartupWarningPreference() {
        var showStartupWarning by mutableStateOf(true)

        composeRule.setShellContent(
            showSettingsScreen = true,
            startupWarningEnabled = { showStartupWarning },
            onStartupWarningEnabledChanged = { enabled -> showStartupWarning = enabled },
        )

        composeRule.onNodeWithTag(UiTags.SettingsStartupWarningSwitch).assertIsOn()
        composeRule.onNodeWithTag(UiTags.SettingsStartupWarningSwitch).performClick()

        assertFalse(showStartupWarning)
    }

    @Test
    fun syncScreenShowsOnlySynchronizeAndCurrentStateForDailyUse() {
        composeRule.setShellContent(
            showSyncScreen = true,
            syncUiState =
                SyncUiState(
                    status = SyncStatus.Success,
                    statusMessage = "Push sent to the Ledger.",
                    deviceName = "Ledger Nano",
                    appName = "Passwords",
                    appVersion = "1.3.1",
                    storageSize = 4096,
                    deviceEntries = 1,
                    diffSummary = "1 addition",
                    diffLines = listOf("Local only: proton [ALL_SETS]"),
                    showVerifyCallToAction = true,
                ),
            startupWarningEnabled = { true },
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.SyncScroll)
            .performScrollToNode(hasTestTag(UiTags.SyncSynchronize))
        composeRule.onNodeWithTag(UiTags.SyncSynchronize).assertIsDisplayed()
        composeRule.onNodeWithText("Synchronize").assertIsDisplayed()
        composeRule.onNodeWithText("Current state").assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.SyncScroll)
            .performScrollToNode(hasText("Verify now"))
        composeRule.onNodeWithText("Verify now").assertIsDisplayed()
        composeRule.onNodeWithText("Latest sync result: 1 addition").assertIsDisplayed()
        composeRule.onNodeWithText("Export local to Ledger").assertDoesNotExist()
        composeRule.onNodeWithText("Advanced: read & compare").assertDoesNotExist()
        composeRule.onNodeWithText("Advanced: write & final check").assertDoesNotExist()
    }

    @Test
    fun homeScreenShowsLastSyncAndHighlightsNewLocalEntries() {
        composeRule.setShellContent(
            localVault =
                Vault(
                    entries = listOf(
                        PasswordIdentifier("github"),
                        PasswordIdentifier("proton", CharsetPolicy.fromCli("upper,lower,numbers")),
                    ),
                ),
            syncShadowState =
                SyncShadowState(
                    lastSyncedVault = Vault(entries = listOf(PasswordIdentifier("github"))),
                    targetKind = SyncTargetKind.Usb,
                    targetDescriptor = null,
                    storageSize = 4096,
                    updatedAtEpochMillis = 1_717_440_000_000L,
                ),
        )

        composeRule.onNodeWithText("Last sync:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 local change waiting to be synchronized.").assertIsDisplayed()
        composeRule.onNodeWithText("Synchronize now").assertIsDisplayed()
        composeRule.onNodeWithText("New locally").assertIsDisplayed()
    }

    @Test
    fun deleteConfirmationDialogConfirmsDestructiveAction() {
        var dialogState by mutableStateOf<AppDialogState?>(AppDialogState.DeleteEntry("github"))
        var confirmed = false

        composeRule.setShellContent(
            appDialogState = { dialogState },
            onConfirmAppDialog = {
                confirmed = true
                dialogState = null
            },
        )

        composeRule.onNodeWithText("Delete this identifier?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.waitUntilNodeCount("Delete this identifier?", expectedCount = 0)
        assertTrue(confirmed)
    }

    @Test
    fun guidedSynchronizationDialogShowsProgressStepAndFinalCloseAction() {
        var dialogState by mutableStateOf<AppDialogState?>(
            AppDialogState.SyncFlow(
                SyncFlowDialogState(
                    stepIndex = 3,
                    stepCount = 4,
                    stepLabel = "Write merged identifiers",
                    title = "Approve the write",
                    body = "Approve the write on your Ledger. The merged identifier list will be saved to the device.",
                ),
            ),
        )
        var closed = false

        composeRule.setShellContent(
            appDialogState = { dialogState },
            onConfirmAppDialog = {
                closed = true
                dialogState = null
            },
        )

        composeRule.onNodeWithText("Approve the write").assertIsDisplayed()
        composeRule.onNodeWithText("Step 3/4 • Write merged identifiers").assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertDoesNotExist()

        dialogState =
            AppDialogState.SyncFlow(
                SyncFlowDialogState(
                    stepIndex = 4,
                    stepCount = 4,
                    stepLabel = "Finished",
                    title = "Synchronization complete",
                    body = "Local vault and Ledger now share the merged vault.",
                    allowClose = true,
                ),
            )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Synchronization complete").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()

        assertTrue(closed)
    }

    @Test
    fun syncConflictDialogRoutesKeepTargetChoiceWithoutCancelling() {
        var dialogState by mutableStateOf<AppDialogState?>(
            AppDialogState.ResolveSynchronizationConflict(
                DeferredSynchronizationConflictPrompt(
                    pendingConflicts =
                        listOf(
                            ThreeWayMergeConflict(
                                nickname = "github",
                                baseEntry = PasswordIdentifier("github"),
                                localEntry = PasswordIdentifier("github"),
                                remoteEntry = null,
                                reason = ThreeWayConflictReason.LocalChangedRemoteRemoved,
                            ),
                        ),
                    autoMergedEntries = listOf(PasswordIdentifier("proton")),
                    summary = "1 conflict",
                    lines = listOf("Conflict: github [Local=ALL_SETS] vs [Target=removed]"),
                    storageSize = 4096,
                ),
            ),
        )
        var chosen: SyncConflictResolutionChoice? = null
        var cancelled = false

        composeRule.setShellContent(
            appDialogState = { dialogState },
            onDismissAppDialog = {
                cancelled = true
                dialogState = null
            },
            onResolveSynchronizationConflictChoice = { choice ->
                chosen = choice
                dialogState = null
            },
        )

        composeRule.onNodeWithText("Resolve synchronization conflict 1/1").assertIsDisplayed()
        composeRule.onNodeWithText("Keep target").performClick()

        assertEquals(SyncConflictResolutionChoice.KeepTarget, chosen)
        assertFalse(cancelled)
    }
}

private fun ComposeContentTestRule.waitUntilNodeCount(
    text: String,
    expectedCount: Int,
    timeoutMillis: Long = 5_000L,
) {
    waitUntil(timeoutMillis) {
        onAllNodesWithText(text).fetchSemanticsNodes().size == expectedCount
    }
    onAllNodesWithText(text).assertCountEquals(expectedCount)
}

private fun ComposeContentTestRule.setShellContent(
    localVault: Vault = Vault(entries = listOf(PasswordIdentifier("github"))),
    localVaultMessage: String = "Local vault loaded.",
    showSyncScreen: Boolean = false,
    showSettingsScreen: Boolean = false,
    showAboutScreen: Boolean = false,
    showDebugScreen: Boolean = false,
    entryEditorState: EntryEditorState? = null,
    syncUiState: SyncUiState = SyncUiState(),
    syncShadowState: SyncShadowState? = null,
    transportMode: SyncTransportMode = SyncTransportMode.Usb,
    speculosHost: String = "10.0.2.2",
    speculosPortText: String = "10100",
    hardwarePushConfirmationEnabled: Boolean = true,
    hardwareDangerousOverrideEnabled: Boolean = false,
    startupWarningEnabled: () -> Boolean = { true },
    appDialogState: () -> AppDialogState? = { null },
    onAddEntry: () -> Unit = {},
    onEditEntry: (PasswordIdentifier) -> Unit = {},
    onDismissEditor: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDebug: () -> Unit = {},
    onBackToHome: () -> Unit = {},
    onEditorNicknameChanged: (String) -> Unit = {},
    onEditorCharsetToggled: (com.ledgerpasswords.companion.core.model.CharsetFlag, Boolean) -> Unit = { _, _ -> },
    onHardwarePushConfirmationChanged: (Boolean) -> Unit = {},
    onHardwareDangerousOverrideChanged: (Boolean) -> Unit = {},
    onSaveEntry: () -> Unit = {},
    onDeleteEntry: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onTransportModeChanged: (SyncTransportMode) -> Unit = {},
    onSpeculosHostChanged: (String) -> Unit = {},
    onSpeculosPortChanged: (String) -> Unit = {},
    onRequestPermission: () -> Unit = {},
    onRefreshDevice: () -> Unit = {},
    onSynchronize: () -> Unit = {},
    onPullFromLedger: () -> Unit = {},
    onCompareWithLedger: () -> Unit = {},
    onPushToLedger: () -> Unit = {},
    onVerifyLedger: () -> Unit = {},
    onStartupWarningEnabledChanged: (Boolean) -> Unit = {},
    onDismissAppDialog: () -> Unit = {},
    onConfirmAppDialog: () -> Unit = {},
    onResolveSynchronizationConflictChoice: (SyncConflictResolutionChoice) -> Unit = {},
    onStartupWarningDismissPreferenceChanged: (Boolean) -> Unit = {},
) {
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
            syncShadowState = syncShadowState,
            transportMode = transportMode,
            speculosHost = speculosHost,
            speculosPortText = speculosPortText,
            hardwarePushConfirmationEnabled = hardwarePushConfirmationEnabled,
            hardwareDangerousOverrideEnabled = hardwareDangerousOverrideEnabled,
            onAddEntry = onAddEntry,
            onEditEntry = onEditEntry,
            onDismissEditor = onDismissEditor,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onOpenDebug = onOpenDebug,
            onBackToHome = onBackToHome,
            onEditorNicknameChanged = onEditorNicknameChanged,
            onEditorCharsetToggled = onEditorCharsetToggled,
            onHardwarePushConfirmationChanged = onHardwarePushConfirmationChanged,
            onHardwareDangerousOverrideChanged = onHardwareDangerousOverrideChanged,
            onSaveEntry = onSaveEntry,
            onDeleteEntry = onDeleteEntry,
            onImportBackup = onImportBackup,
            onExportBackup = onExportBackup,
            onOpenSync = onOpenSync,
            onTransportModeChanged = onTransportModeChanged,
            onSpeculosHostChanged = onSpeculosHostChanged,
            onSpeculosPortChanged = onSpeculosPortChanged,
            onRequestPermission = onRequestPermission,
            onRefreshDevice = onRefreshDevice,
            onSynchronize = onSynchronize,
            onPullFromLedger = onPullFromLedger,
            onCompareWithLedger = onCompareWithLedger,
            onPushToLedger = onPushToLedger,
            onVerifyLedger = onVerifyLedger,
            startupWarningEnabled = startupWarningEnabled(),
            onStartupWarningEnabledChanged = onStartupWarningEnabledChanged,
            appDialogState = appDialogState(),
            onDismissAppDialog = onDismissAppDialog,
            onConfirmAppDialog = onConfirmAppDialog,
            onResolveSynchronizationConflictChoice = onResolveSynchronizationConflictChoice,
            onStartupWarningDismissPreferenceChanged = onStartupWarningDismissPreferenceChanged,
        )
    }
}
