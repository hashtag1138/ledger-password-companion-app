package com.ledgerpasswords.companion.core.sync

import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreeWayVaultMergePlannerTest {
    private val planner = ThreeWayVaultMergePlanner()

    @Test
    fun `plan keeps one-sided additions and removals`() {
        val plan =
            planner.plan(
                base = Vault(entries = listOf(PasswordIdentifier("github"))),
                local = Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("proton"))),
                remote = Vault(entries = emptyList()),
            )

        assertTrue(plan.canMerge)
        assertEquals(listOf("proton"), plan.localAdditions.map { it.nickname })
        assertEquals(listOf("github"), plan.remoteRemovals.map { it.nickname })
        assertEquals(listOf("proton"), plan.mergedVault!!.entries.map { it.nickname })
    }

    @Test
    fun `plan keeps one-sided updates`() {
        val plan =
            planner.plan(
                base = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("lower")))),
                local = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")))),
                remote = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("lower")))),
            )

        assertTrue(plan.canMerge)
        assertEquals(1, plan.localUpdates.size)
        assertEquals("github", plan.mergedVault!!.entries.single().nickname)
        assertEquals(CharsetPolicy.fromCli("upper,lower,numbers"), plan.mergedVault!!.entries.single().charsets)
    }

    @Test
    fun `plan reports divergent concurrent update conflict`() {
        val plan =
            planner.plan(
                base = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("lower")))),
                local = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")))),
                remote = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("all")))),
            )

        assertFalse(plan.canMerge)
        assertEquals(ThreeWayConflictReason.ChangedDifferently, plan.conflicts.single().reason)
    }

    @Test
    fun `plan reports remove versus change conflict`() {
        val plan =
            planner.plan(
                base = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("lower")))),
                local = Vault(entries = emptyList()),
                remote = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")))),
            )

        assertFalse(plan.canMerge)
        assertEquals(ThreeWayConflictReason.LocalRemovedRemoteChanged, plan.conflicts.single().reason)
    }

    @Test
    fun `plan accepts converged updates when both sides changed to the same result`() {
        val plan =
            planner.plan(
                base = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("lower")))),
                local = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")))),
                remote = Vault(entries = listOf(PasswordIdentifier("github", CharsetPolicy.fromCli("upper,lower,numbers")))),
            )

        assertTrue(plan.canMerge)
        assertEquals(1, plan.convergedUpdates.size)
        assertEquals(CharsetPolicy.fromCli("upper,lower,numbers"), plan.mergedVault!!.entries.single().charsets)
    }
}
