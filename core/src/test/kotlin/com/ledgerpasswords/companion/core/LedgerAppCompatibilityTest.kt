package com.ledgerpasswords.companion.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerAppCompatibilityTest {
    @Test
    fun `parseSemanticVersion reads stable versions`() {
        assertEquals(SemanticVersion(1, 3, 2), LedgerAppCompatibility.parseSemanticVersion("1.3.2"))
        assertEquals(SemanticVersion(1, 3, 2), LedgerAppCompatibility.parseSemanticVersion(" 1.3.2-dev "))
    }

    @Test
    fun `parseSemanticVersion rejects unknown formats`() {
        assertEquals(null, LedgerAppCompatibility.parseSemanticVersion(""))
        assertEquals(null, LedgerAppCompatibility.parseSemanticVersion("unknown"))
        assertEquals(null, LedgerAppCompatibility.parseSemanticVersion("1.3"))
    }

    @Test
    fun `supportsRealDevicePush requires passwords 1 3 2 or newer`() {
        assertFalse(LedgerAppCompatibility.supportsRealDevicePush("1.3.0"))
        assertFalse(LedgerAppCompatibility.supportsRealDevicePush("1.3.1"))
        assertTrue(LedgerAppCompatibility.supportsRealDevicePush("1.3.2"))
        assertTrue(LedgerAppCompatibility.supportsRealDevicePush("1.4.0"))
    }

    @Test
    fun `realDeviceWriteBlockedMessage explains bugs and links`() {
        val message = LedgerAppCompatibility.realDeviceWriteBlockedMessage("1.3.1")

        assertTrue(message.contains("1.3.1"))
        assertTrue(message.contains("1.3.2"))
        assertTrue(message.contains("wrong index handling"))
        assertTrue(message.contains("AZERTY AltGr"))
        assertTrue(message.contains("pull/dump remain allowed"))
        assertTrue(message.contains("https://github.com/hashtag1138/ledger-passwords-show-second-repro"))
        assertTrue(message.contains("https://github.com/hashtag1138/ledger-password-companion-app"))
    }
}
