package com.ledgerpasswords.companion.android

import com.ledgerpasswords.companion.core.diff.VaultDiffer
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncUiLogicTest {
    private val differ = VaultDiffer()

    @Test
    fun `applySyncUpdate clears stale device counters and diff lines`() {
        val previous =
            SyncUiState(
                status = SyncStatus.Success,
                statusMessage = "Ancien état",
                deviceName = "/dev/bus/usb/001/003",
                appName = "Passwords",
                appVersion = "1.3.1",
                storageSize = 4096,
                deviceEntries = 4,
                diffLines = listOf("Diff obsolète"),
            )

        val next =
            applySyncUpdate(
                previous,
                SyncUpdate(
                    status = SyncStatus.DeviceConnected,
                    statusMessage = "Ledger connecté",
                    clearDeviceEntries = true,
                    clearDiffLines = true,
                ),
            )

        assertEquals(SyncStatus.DeviceConnected, next.status)
        assertEquals("Ledger connecté", next.statusMessage)
        assertEquals("/dev/bus/usb/001/003", next.deviceName)
        assertNull(next.deviceEntries)
        assertEquals(emptyList<String>(), next.diffLines)
        assertEquals("Passwords", next.appName)
    }

    @Test
    fun `applySyncUpdate clears diff summary and verify CTA when requested`() {
        val previous =
            SyncUiState(
                diffSummary = "1 ajout",
                diffLines = listOf("ancien diff"),
                showVerifyCallToAction = true,
            )

        val next =
            applySyncUpdate(
                previous,
                SyncUpdate(
                    status = SyncStatus.Idle,
                    statusMessage = "Retour au repos",
                    clearDiffSummary = true,
                    clearDiffLines = true,
                    showVerifyCallToAction = false,
                ),
            )

        assertNull(next.diffSummary)
        assertEquals(emptyList<String>(), next.diffLines)
        assertEquals(false, next.showVerifyCallToAction)
    }

    @Test
    fun `applySyncUpdate replaces provided fields and preserves omitted metadata`() {
        val previous =
            SyncUiState(
                status = SyncStatus.DeviceConnected,
                statusMessage = "Ancien état",
                appName = "Passwords",
                appVersion = "1.3.0",
                storageSize = 4096,
                deviceEntries = 1,
                diffLines = listOf("Ancien diff"),
            )

        val next =
            applySyncUpdate(
                previous,
                SyncUpdate(
                    status = SyncStatus.Success,
                    statusMessage = "Comparaison terminée",
                    appVersion = "1.3.1",
                    deviceEntries = 2,
                    diffSummary = "1 ajout",
                    diffLines = listOf("Nouveau diff"),
                ),
            )

        assertEquals(SyncStatus.Success, next.status)
        assertEquals("Comparaison terminée", next.statusMessage)
        assertEquals("Passwords", next.appName)
        assertEquals("1.3.1", next.appVersion)
        assertEquals(4096, next.storageSize)
        assertEquals(2, next.deviceEntries)
        assertEquals("1 ajout", next.diffSummary)
        assertEquals(listOf("Nouveau diff"), next.diffLines)
    }

    @Test
    fun `renderLedgerDiffSummary returns concise counts`() {
        val diff =
            differ.diff(
                before = Vault(
                    entries = listOf(
                        PasswordIdentifier("gmail"),
                        PasswordIdentifier("github", charsets = CharsetPolicy.fromCli("lower")),
                    ),
                ),
                after = Vault(
                    entries = listOf(
                        PasswordIdentifier("github", charsets = CharsetPolicy.fromCli("upper,lower,numbers")),
                        PasswordIdentifier("proton"),
                    ),
                ),
            )

        assertEquals("1 ajout • 1 suppression • 1 modification", renderLedgerDiffSummary(diff))
    }

    @Test
    fun `sync status labels are user friendly`() {
        assertEquals("Blocage de sécurité", SyncStatus.ValidationError.frenchLabel())
        assertEquals("Validation sur Ledger", SyncStatus.WaitingForLedgerApproval.frenchLabel())
    }

    @Test
    fun `renderLedgerDiffLines returns explicit no change message`() {
        val diff =
            differ.diff(
                before = Vault(entries = listOf(PasswordIdentifier("github"))),
                after = Vault(entries = listOf(PasswordIdentifier("github"))),
            )

        assertEquals(
            listOf("Aucune différence entre le local et le Ledger."),
            renderLedgerDiffLines(diff),
        )
    }

    @Test
    fun `renderLedgerDiffLines formats added removed and changed entries`() {
        val diff =
            differ.diff(
                before = Vault(
                    entries = listOf(
                        PasswordIdentifier("gmail"),
                        PasswordIdentifier("github", charsets = CharsetPolicy.fromCli("lower")),
                    ),
                ),
                after = Vault(
                    entries = listOf(
                        PasswordIdentifier("github", charsets = CharsetPolicy.fromCli("upper,lower,numbers")),
                        PasswordIdentifier("proton"),
                    ),
                ),
            )

        assertEquals(
            listOf(
                "Local seulement: proton [ALL_SETS]",
                "Ledger seulement: gmail [ALL_SETS]",
                "Charsets différents: github [Ledger=LOWERCASE] -> [Local=UPPERCASE,LOWERCASE,NUMBERS]",
            ),
            renderLedgerDiffLines(diff),
        )
    }
}
