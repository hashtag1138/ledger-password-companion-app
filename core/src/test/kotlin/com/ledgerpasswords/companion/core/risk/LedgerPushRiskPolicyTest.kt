package com.ledgerpasswords.companion.core.risk

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerPushRiskPolicyTest {
    private val policy = LedgerPushRiskPolicy(LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE)

    @Test
    fun `internal spaces are allowed in hardware safe mode`() {
        val assessment =
            policy.assess(
                vault = Vault(entries = listOf(PasswordIdentifier("sofian terki"))),
                mode = PushSafetyMode.HardwareSafe,
            )

        assertEquals(PushRiskDecision.Allow, assessment.decision)
    }

    @Test
    fun `multiple entries are allowed in hardware safe mode`() {
        val assessment =
            policy.assess(
                vault = Vault(entries = listOf(PasswordIdentifier("sofian terki"), PasswordIdentifier("abc"))),
                mode = PushSafetyMode.HardwareSafe,
            )

        assertEquals(PushRiskDecision.Allow, assessment.decision)
        assertTrue(assessment.summaryLines().none { it.contains("multiple entries") })
    }

    @Test
    fun `leading whitespace is blocked in hardware safe mode`() {
        val assessment =
            policy.assess(
                vault = Vault(entries = listOf(PasswordIdentifier(" leading"))),
                mode = PushSafetyMode.HardwareSafe,
            )

        assertEquals(PushRiskDecision.Block, assessment.decision)
        assertTrue(assessment.summaryLines().any { it.contains("starts or ends with whitespace") })
    }

    @Test
    fun `normalized duplicate is blocked in hardware safe mode`() {
        val assessment =
            policy.assess(
                vault = Vault(entries = listOf(PasswordIdentifier("é"), PasswordIdentifier("e\u0301"))),
                mode = PushSafetyMode.HardwareSafe,
            )

        assertEquals(PushRiskDecision.Block, assessment.decision)
        assertTrue(assessment.summaryLines().any { it.contains("equivalent after normalization") })
    }

    @Test
    fun `dense list is warned`() {
        val assessment =
            policy.assess(
                vault = Vault(entries = (1..12).map { index -> PasswordIdentifier("slot-${index.toString().padStart(2, '0')}") }),
                mode = PushSafetyMode.HardwareSafe,
            )

        assertEquals(PushRiskDecision.Warn, assessment.decision)
        assertTrue(assessment.summaryLines().any { it.contains("Dense lists") })
    }

    @Test
    fun `speculos mode downgrades extra blocking findings to warnings`() {
        val assessment =
            policy.assess(
                vault = Vault(entries = listOf(PasswordIdentifier("github"))),
                mode = PushSafetyMode.Standard,
                extraFindings =
                    listOf(
                        PushRiskFinding(
                            severity = PushRiskSeverity.Block,
                            code = "raw_metadata_invalid",
                            message = "Raw suspect.",
                        ),
                    ),
            )

        assertEquals(PushRiskDecision.Warn, assessment.decision)
        assertTrue(assessment.summaryLines().single().contains("Raw suspect"))
    }
}
