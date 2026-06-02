package com.ledgerpasswords.companion.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerAppCompatibilityTest {
    @Test
    fun `parseSemanticVersion reads stable versions`() {
        assertEquals(SemanticVersion(1, 3, 1), LedgerAppCompatibility.parseSemanticVersion("1.3.1"))
        assertEquals(SemanticVersion(1, 3, 1), LedgerAppCompatibility.parseSemanticVersion(" 1.3.1-dev "))
    }

    @Test
    fun `parseSemanticVersion rejects unknown formats`() {
        assertEquals(null, LedgerAppCompatibility.parseSemanticVersion(""))
        assertEquals(null, LedgerAppCompatibility.parseSemanticVersion("unknown"))
        assertEquals(null, LedgerAppCompatibility.parseSemanticVersion("1.3"))
    }

    @Test
    fun `supportsRealDevicePush requires passwords 1 3 1 or newer`() {
        assertFalse(LedgerAppCompatibility.supportsRealDevicePush("1.3.0"))
        assertTrue(LedgerAppCompatibility.supportsRealDevicePush("1.3.1"))
        assertTrue(LedgerAppCompatibility.supportsRealDevicePush("1.4.0"))
    }
}
