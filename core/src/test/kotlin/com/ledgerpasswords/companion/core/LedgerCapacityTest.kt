package com.ledgerpasswords.companion.core

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerCapacityTest {
    @Test
    fun `capacity snapshot counts bytes and entries`() {
        val vault = Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("gmail")))

        val snapshot = vault.capacitySnapshot()

        assertEquals(2, snapshot.entryCount)
        assertEquals(2 + (3 + 6) + (3 + 5), snapshot.usedBytes)
        assertEquals(LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE - snapshot.usedBytes, snapshot.remainingBytes)
        assertEquals(LedgerPasswordsLimits.MAX_METADATA_COUNT, snapshot.maxEntryCount)
    }

    @Test
    fun `capacity snapshot reports near limit and overflow`() {
        val nickname = "abcdefghijklmnopqrs"
        val vault = Vault(entries = List(LedgerPasswordsLimits.MAX_METADATA_COUNT) { PasswordIdentifier("$nickname$it".take(19)) })

        val snapshot = vault.capacitySnapshot()

        assertTrue(snapshot.isNearLimit)
        assertFalse(snapshot.isOverflowing)
        assertEquals(0, snapshot.remainingEntrySlots)
    }
}
