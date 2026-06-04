package com.ledgerpasswords.companion.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ledgerpasswords.companion.android.storage.LocalVaultStore
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.ledger.client.LedgerPasswordsClient
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import com.ledgerpasswords.companion.ledger.transport.SpeculosTransport
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeculosCharsetRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun synchronizeAllSetsEntryPreservesRawMaskAndReadableState() {
        val localVault = Vault(entries = listOf(PasswordIdentifier("gmail", CharsetPolicy.All)))
        prepareScenario(localVault = localVault, deviceVault = Vault(entries = emptyList()))
        openSyncIfNeeded()
        ensureSpeculosConnected()

        scrollToSyncAction(UiTags.SyncSynchronize)
        composeRule.onNodeWithTag(UiTags.SyncSynchronize).performClick()
        waitForStatus("Synchronization completed. Local and target now share the merged vault.")
        composeRule.onNodeWithText("Synchronization complete").assertIsDisplayed()

        assertEquals(localVault.entries, readLocalVault().entries)

        val raw = readDeviceRawMetadata()
        assertEquals(0x06, raw[0].toInt() and 0xFF)
        assertEquals(0x00, raw[1].toInt() and 0xFF)
        assertEquals(0xFF, raw[2].toInt() and 0xFF)
        assertEquals("gmail", raw.copyOfRange(3, 8).toString(Charsets.UTF_8))

        val decoded = MetadataCodec(raw.size).decode(raw).vault.copy(source = VaultSource.Local).sortedByNickname()
        assertEquals(localVault.entries, decoded.entries)
    }

    private fun openSyncIfNeeded() {
        when (waitForAnyTag(UiTags.HomeOpenSync, UiTags.SyncSynchronize, UiTags.SyncStatusMessage)) {
            UiTags.HomeOpenSync -> {
                composeRule.onNodeWithTag(UiTags.HomeOpenSync).performClick()
                waitForAnyTag(UiTags.SyncSynchronize, UiTags.SyncStatusMessage)
            }
            UiTags.SyncSynchronize,
            UiTags.SyncStatusMessage,
            -> return
        }
    }

    private fun waitForAnyTag(vararg tags: String, timeoutMillis: Long = 15_000L): String {
        composeRule.waitUntil(timeoutMillis) {
            tags.any { tag ->
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        }
        return tags.first { tag ->
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollToSyncAction(tag: String) {
        composeRule.onNodeWithTag(UiTags.SyncScroll)
            .performScrollToNode(hasTestTag(tag))
    }

    private fun scrollToSyncStatus() {
        composeRule.onNodeWithTag(UiTags.SyncScroll)
            .performScrollToNode(hasTestTag(UiTags.SyncStatusMessage))
    }

    private fun waitForStatus(expectedSubstring: String, timeoutMillis: Long = 60_000L) {
        composeRule.waitUntil(timeoutMillis) {
            statusContains(expectedSubstring)
        }
        scrollToSyncStatus()
        composeRule.onNodeWithTag(UiTags.SyncStatusMessage)
            .assertTextContains(expectedSubstring, substring = true)
    }

    private fun statusContains(expectedSubstring: String): Boolean =
        try {
            scrollToSyncStatus()
            composeRule.onNodeWithTag(UiTags.SyncStatusMessage)
                .assertTextContains(expectedSubstring, substring = true)
            true
        } catch (_: AssertionError) {
            false
        }

    private fun prepareScenario(
        localVault: Vault,
        deviceVault: Vault,
    ) {
        openSyncIfNeeded()
        ensureSpeculosConnected()
        writeLocalVault(localVault)
        writeDeviceVault(deviceVault)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    private fun ensureSpeculosConnected(timeoutMillis: Long = 60_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            openSyncIfNeeded()
            if (statusContains("Speculos connected, Passwords app")) {
                return
            }
            refreshTargetStateIfAvailable()
            Thread.sleep(500L)
        }
        waitForStatus("Speculos connected, Passwords app", timeoutMillis = 1_000L)
    }

    private fun refreshTargetStateIfAvailable() {
        if (composeRule.onAllNodesWithTag(UiTags.SyncRefresh).fetchSemanticsNodes().isEmpty()) {
            return
        }
        scrollToSyncAction(UiTags.SyncRefresh)
        composeRule.onNodeWithTag(UiTags.SyncRefresh).performClick()
    }

    private fun readLocalVault(): Vault {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        return requireNotNull(LocalVaultStore(File(targetContext.filesDir, LOCAL_VAULT_FILE_NAME)).load().vault)
    }

    private fun writeLocalVault(vault: Vault) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        LocalVaultStore(File(targetContext.filesDir, LOCAL_VAULT_FILE_NAME)).saveVault(vault)
    }

    private fun writeDeviceVault(vault: Vault) {
        withSpeculosRetry {
            runBlocking {
                val client = LedgerPasswordsClient(SpeculosTransport(server = speculosHost(), port = speculosPort()))
                val config = client.getAppConfig()
                val raw = MetadataCodec(config.storageSize).encode(vault.copy(source = VaultSource.Local).sortedByNickname())
                client.loadMetadatas(raw)
            }
        }
    }

    private fun readDeviceRawMetadata(): ByteArray =
        withSpeculosRetry {
            runBlocking {
                val client = LedgerPasswordsClient(SpeculosTransport(server = speculosHost(), port = speculosPort()))
                val config = client.getAppConfig()
                client.dumpMetadatas(config.storageSize)
            }
        }

    private fun <T> withSpeculosRetry(
        attempts: Int = 5,
        block: () -> T,
    ): T {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (error: SocketTimeoutException) {
                lastError = error
            } catch (error: IOException) {
                lastError = error
            }
            if (attempt < attempts - 1) {
                Thread.sleep(500L)
            }
        }
        throw requireNotNull(lastError)
    }

    private fun speculosHost(): String =
        InstrumentationRegistry.getArguments().getString("speculosHost") ?: "10.0.2.2"

    private fun speculosPort(): Int =
        InstrumentationRegistry.getArguments().getString("speculosPort")?.toIntOrNull() ?: 10100

    private companion object {
        const val LOCAL_VAULT_FILE_NAME = "local-vault.json"
    }
}
