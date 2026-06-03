package com.ledgerpasswords.companion.android

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ledgerpasswords.companion.android.storage.LocalVaultStore
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
class SpeculosSyncPreparedStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun synchronizeMergesPreparedLocalOnlyAndTargetOnlyEntries() {
        openSyncIfNeeded()
        waitForStatus("Speculos connected, Passwords app")

        scrollToSyncAction(UiTags.SyncSynchronize)
        composeRule.onNodeWithTag(UiTags.SyncSynchronize).performClick()
        waitForStatus("Synchronization completed. Local and target now share the merged vault.")

        val expectedEntries = listOf(PasswordIdentifier("device-only"), PasswordIdentifier("local-only"))
        assertEquals(expectedEntries, readLocalVault().entries)
        assertEquals(expectedEntries, readDeviceVault().entries)
    }

    @Test
    fun synchronizeResolvesPreparedConflictAndWritesChosenSide() {
        openSyncIfNeeded()
        waitForStatus("Speculos connected, Passwords app")

        scrollToSyncAction(UiTags.SyncSynchronize)
        composeRule.onNodeWithTag(UiTags.SyncSynchronize).performClick()
        composeRule.waitUntil(15_000L) {
            composeRule.onAllNodesWithText("Resolve synchronization conflict 1/1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Keep local").performClick()
        waitForStatus("Synchronization completed. Local and target now share the merged vault.")

        val expectedEntries = listOf(PasswordIdentifier("github"))
        assertEquals(expectedEntries.map { it.nickname }, readLocalVault().entries.map { it.nickname })
        assertEquals(readLocalVault().entries, readDeviceVault().entries)
    }

    private fun openSyncIfNeeded() {
        when (waitForAnyTag(UiTags.HomeOpenSync, UiTags.SyncPull, UiTags.SyncStatusMessage)) {
            UiTags.HomeOpenSync -> {
                composeRule.onNodeWithTag(UiTags.HomeOpenSync).performClick()
                waitForAnyTag(UiTags.SyncPull, UiTags.SyncStatusMessage)
            }
            UiTags.SyncPull,
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
        scrollToSyncStatus()
        composeRule.waitUntil(timeoutMillis) {
            try {
                composeRule.onNodeWithTag(UiTags.SyncStatusMessage)
                    .assertTextContains(expectedSubstring, substring = true)
                true
            } catch (_: AssertionError) {
                false
            }
        }
        scrollToSyncStatus()
        composeRule.onNodeWithTag(UiTags.SyncStatusMessage)
            .assertTextContains(expectedSubstring, substring = true)
    }

    private fun readLocalVault(): Vault {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        return requireNotNull(LocalVaultStore(File(targetContext.filesDir, LOCAL_VAULT_FILE_NAME)).load().vault)
    }

    private fun readDeviceVault(): Vault =
        withSpeculosRetry {
            runBlocking {
                val client = LedgerPasswordsClient(SpeculosTransport(server = speculosHost(), port = speculosPort()))
                val config = client.getAppConfig()
                MetadataCodec(config.storageSize).decode(client.dumpMetadatas(config.storageSize)).vault
                    .copy(source = VaultSource.Local)
                    .sortedByNickname()
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
