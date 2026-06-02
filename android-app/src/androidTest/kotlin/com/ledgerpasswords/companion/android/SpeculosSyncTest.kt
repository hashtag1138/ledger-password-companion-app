package com.ledgerpasswords.companion.android

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeculosSyncTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun importEditPushAndVerifyAgainstSpeculos() {
        openSyncIfNeeded()
        connectToSpeculos()

        scrollToSyncAction(UiTags.SyncPull)
        composeRule.onNodeWithTag(UiTags.SyncPull).performClick()
        waitForStatus("Import terminé depuis Speculos.")

        scrollToSyncAction(UiTags.SyncPush)
        composeRule.onNodeWithTag(UiTags.SyncPush).performClick()
        waitForStatus("Push envoyé vers Speculos.")

        scrollToSyncAction(UiTags.SyncVerify)
        composeRule.onNodeWithTag(UiTags.SyncVerify).performClick()
        waitForStatus("Le Ledger correspond à l'état local.")
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

    private fun connectToSpeculos() {
        waitForStatus("Speculos connecté, app Passwords")
    }

    private fun scrollToSyncAction(tag: String) {
        composeRule.onNodeWithTag(UiTags.SyncScroll)
            .performScrollToNode(hasTestTag(tag))
    }

    private fun waitForStatus(expectedSubstring: String, timeoutMillis: Long = 60_000L) {
        composeRule.waitUntil(timeoutMillis) {
            try {
                composeRule.onNodeWithTag(UiTags.SyncStatusMessage)
                    .assertTextContains(expectedSubstring, substring = true)
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithTag(UiTags.SyncStatusMessage)
            .assertTextContains(expectedSubstring, substring = true)
    }
}
