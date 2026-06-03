package com.ledgerpasswords.companion.android

import com.ledgerpasswords.companion.core.diff.VaultDiffer
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.sync.VaultMergePlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncUiLogicTest {
    private val differ = VaultDiffer()
    private val mergePlanner = VaultMergePlanner()

    @Test
    fun `applySyncUpdate clears stale device counters and diff lines`() {
        val previous =
            SyncUiState(
                status = SyncStatus.Success,
                statusMessage = "Old state",
                deviceName = "/dev/bus/usb/001/003",
                appName = "Passwords",
                appVersion = "1.3.1",
                storageSize = 4096,
                deviceEntries = 4,
                diffLines = listOf("Stale diff"),
            )

        val next =
            applySyncUpdate(
                previous,
                SyncUpdate(
                    status = SyncStatus.DeviceConnected,
                    statusMessage = "Ledger connected",
                    clearDeviceEntries = true,
                    clearDiffLines = true,
                ),
            )

        assertEquals(SyncStatus.DeviceConnected, next.status)
        assertEquals("Ledger connected", next.statusMessage)
        assertEquals("/dev/bus/usb/001/003", next.deviceName)
        assertNull(next.deviceEntries)
        assertEquals(emptyList<String>(), next.diffLines)
        assertEquals("Passwords", next.appName)
    }

    @Test
    fun `applySyncUpdate clears diff summary and verify CTA when requested`() {
        val previous =
            SyncUiState(
                diffSummary = "1 addition",
                diffLines = listOf("old diff"),
                showVerifyCallToAction = true,
            )

        val next =
            applySyncUpdate(
                previous,
                SyncUpdate(
                    status = SyncStatus.Idle,
                    statusMessage = "Back to idle",
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
                statusMessage = "Old state",
                appName = "Passwords",
                appVersion = "1.3.0",
                storageSize = 4096,
                deviceEntries = 1,
                diffLines = listOf("Old diff"),
            )

        val next =
            applySyncUpdate(
                previous,
                SyncUpdate(
                    status = SyncStatus.Success,
                    statusMessage = "Comparison completed",
                    appVersion = "1.3.1",
                    deviceEntries = 2,
                    diffSummary = "1 addition",
                    diffLines = listOf("New diff"),
                ),
            )

        assertEquals(SyncStatus.Success, next.status)
        assertEquals("Comparison completed", next.statusMessage)
        assertEquals("Passwords", next.appName)
        assertEquals("1.3.1", next.appVersion)
        assertEquals(4096, next.storageSize)
        assertEquals(2, next.deviceEntries)
        assertEquals("1 addition", next.diffSummary)
        assertEquals(listOf("New diff"), next.diffLines)
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

        assertEquals("1 addition • 1 removal • 1 charset change", renderLedgerDiffSummary(diff))
    }

    @Test
    fun `sync status labels are user friendly`() {
        assertEquals("Safety block", SyncStatus.ValidationError.displayLabel())
        assertEquals("Waiting for Ledger approval", SyncStatus.WaitingForLedgerApproval.displayLabel())
    }

    @Test
    fun `renderLedgerDiffLines returns explicit no change message`() {
        val diff =
            differ.diff(
                before = Vault(entries = listOf(PasswordIdentifier("github"))),
                after = Vault(entries = listOf(PasswordIdentifier("github"))),
            )

        assertEquals(
            listOf("No difference between local and Ledger."),
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
                "Local only: proton [ALL_SETS]",
                "Ledger only: gmail [ALL_SETS]",
                "Different charsets: github [Ledger=LOWERCASE] -> [Local=UPPERCASE,LOWERCASE,NUMBERS]",
            ),
            renderLedgerDiffLines(diff),
        )
    }

    @Test
    fun `renderSynchronizationSummary returns concise merge counts`() {
        val plan =
            mergePlanner.plan(
                local = Vault(entries = listOf(PasswordIdentifier("github"))),
                remote = Vault(entries = listOf(PasswordIdentifier("gmail"))),
            )

        assertEquals("1 local-only • 1 target-only", renderSynchronizationSummary(plan))
    }

    @Test
    fun `renderSynchronizationLines formats keep import and conflict actions`() {
        val plan =
            mergePlanner.plan(
                local = Vault(
                    entries = listOf(
                        PasswordIdentifier("github", CharsetPolicy.fromCli("lower")),
                        PasswordIdentifier("proton"),
                    ),
                ),
                remote = Vault(
                    entries = listOf(
                        PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")),
                        PasswordIdentifier("gmail"),
                    ),
                ),
            )

        assertEquals(
            listOf(
                "Keep local only: proton [ALL_SETS]",
                "Import from target: gmail [ALL_SETS]",
                "Conflict: github [Local=LOWERCASE] -> [Target=UPPERCASE,LOWERCASE,NUMBERS]",
            ),
            renderSynchronizationLines(plan),
        )
    }
}
