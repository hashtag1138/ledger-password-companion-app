package com.ledgerpasswords.companion.android.storage

import java.io.File
import java.io.IOException

data class UiPreferences(
    val showExperimentalWarningOnLaunch: Boolean = true,
    val confirmHardwarePush: Boolean = true,
)

class UiPreferencesStore(
    private val file: File,
) {
    fun load(): UiPreferences {
        if (!file.exists()) return UiPreferences()

        val content =
            try {
                file.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                return UiPreferences()
            }

        val values =
            content
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                    }
                }.toMap()

        return UiPreferences(
            showExperimentalWarningOnLaunch = values.parseBooleanOrDefault(KEY_SHOW_EXPERIMENTAL_WARNING, true),
            confirmHardwarePush = values.parseBooleanOrDefault(KEY_CONFIRM_HARDWARE_PUSH, true),
        )
    }

    fun save(preferences: UiPreferences): UiPreferences {
        val text =
            buildString {
                append(KEY_SHOW_EXPERIMENTAL_WARNING)
                append('=')
                append(preferences.showExperimentalWarningOnLaunch)
                append('\n')
                append(KEY_CONFIRM_HARDWARE_PUSH)
                append('=')
                append(preferences.confirmHardwarePush)
                append('\n')
            }
        writeTextAtomically(text)
        return preferences
    }

    private fun writeTextAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.tmp")
        tempFile.writeText(text, Charsets.UTF_8)
        if (!tempFile.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    companion object {
        private const val KEY_SHOW_EXPERIMENTAL_WARNING = "show_experimental_warning_on_launch"
        private const val KEY_CONFIRM_HARDWARE_PUSH = "confirm_hardware_push"
    }
}

private fun Map<String, String>.parseBooleanOrDefault(key: String, default: Boolean): Boolean =
    when (get(key)?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }
