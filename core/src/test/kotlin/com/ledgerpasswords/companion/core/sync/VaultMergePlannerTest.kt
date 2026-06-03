package com.ledgerpasswords.companion.core.sync

import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultMergePlannerTest {
    private val planner = VaultMergePlanner()

    @Test
    fun `plan merges local-only and remote-only identifiers`() {
        val plan =
            planner.plan(
                local = Vault(entries = listOf(PasswordIdentifier("github"))),
                remote = Vault(entries = listOf(PasswordIdentifier("gmail"))),
            )

        assertTrue(plan.canMerge)
        assertEquals(listOf(PasswordIdentifier("github")), plan.localOnly)
        assertEquals(listOf(PasswordIdentifier("gmail")), plan.remoteOnly)
        assertEquals(
            listOf(PasswordIdentifier("github"), PasswordIdentifier("gmail")).sortedBy { it.nickname.lowercase() },
            plan.mergedVault!!.entries,
        )
    }

    @Test
    fun `plan keeps identical entries without conflict`() {
        val plan =
            planner.plan(
                local = Vault(entries = listOf(PasswordIdentifier("github"))),
                remote = Vault(entries = listOf(PasswordIdentifier("github"))),
            )

        assertTrue(plan.canMerge)
        assertFalse(plan.hasChanges)
        assertEquals(listOf(PasswordIdentifier("github")), plan.identical)
        assertEquals(listOf(PasswordIdentifier("github")), plan.mergedVault!!.entries)
    }

    @Test
    fun `plan reports conflict when same nickname has different charsets`() {
        val plan =
            planner.plan(
                local = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("lower")))),
                remote = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")))),
            )

        assertFalse(plan.canMerge)
        assertEquals(1, plan.conflicts.size)
        assertEquals("github", plan.conflicts.single().nickname)
        assertNull(plan.mergedVault)
    }
}
