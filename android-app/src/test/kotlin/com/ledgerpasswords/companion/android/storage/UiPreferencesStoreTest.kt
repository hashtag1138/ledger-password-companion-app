package com.ledgerpasswords.companion.android.storage

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UiPreferencesStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load missing preferences returns defaults`() {
        val store = UiPreferencesStore(tempDir.resolve("ui-preferences.properties").toFile())

        assertEquals(UiPreferences(), store.load())
    }

    @Test
    fun `save persists preferences and load restores them`() {
        val file = tempDir.resolve("ui-preferences.properties").toFile()
        val store = UiPreferencesStore(file)
        val preferences =
            UiPreferences(
                showExperimentalWarningOnLaunch = false,
                confirmHardwarePush = false,
            )

        store.save(preferences)

        assertEquals(preferences, store.load())
        assertEquals(
            """
            show_experimental_warning_on_launch=false
            confirm_hardware_push=false
            """.trimIndent() + "\n",
            file.readText(),
        )
    }

    @Test
    fun `load falls back to defaults for invalid values`() {
        val file = tempDir.resolve("ui-preferences.properties").toFile()
        file.writeText(
            """
            show_experimental_warning_on_launch=maybe
            confirm_hardware_push=false
            """.trimIndent(),
        )
        val store = UiPreferencesStore(file)

        assertEquals(
            UiPreferences(
                showExperimentalWarningOnLaunch = true,
                confirmHardwarePush = false,
            ),
            store.load(),
        )
    }
}
