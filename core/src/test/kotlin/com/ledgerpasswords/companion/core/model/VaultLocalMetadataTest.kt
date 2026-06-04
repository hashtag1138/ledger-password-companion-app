package com.ledgerpasswords.companion.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultLocalMetadataTest {
    @Test
    fun `ledger comparison ignores local note`() {
        val left = Vault(entries = listOf(PasswordIdentifier(nickname = "github", localNote = "Personal")))
        val right = Vault(entries = listOf(PasswordIdentifier(nickname = "github")))

        assertTrue(left.hasSameLedgerEntries(right))
    }

    @Test
    fun `overlay preserves local metadata by nickname`() {
        val local =
            Vault(
                entries = listOf(
                    PasswordIdentifier(nickname = "github", localNote = "Personal"),
                    PasswordIdentifier(nickname = "gmail", localNote = "Work"),
                ),
            )
        val merged =
            Vault(
                entries = listOf(
                    PasswordIdentifier(nickname = "github"),
                    PasswordIdentifier(nickname = "proton"),
                ),
            )

        val overlaid = merged.overlayLocalMetadataFrom(local)

        assertEquals("Personal", overlaid.entries.first { it.nickname == "github" }.localNote)
        assertNull(overlaid.entries.first { it.nickname == "proton" }.localNote)
    }
}
