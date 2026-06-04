package com.ledgerpasswords.companion.android

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object BackupFileNames {
    private const val BACKUP_FILE_PREFIX = "ledger-passwords-backup"
    private val exportTimestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

    fun defaultBackupFileName(clock: Clock = Clock.systemDefaultZone()): String {
        val timestamp = LocalDateTime.now(clock).format(exportTimestampFormatter)
        return "$BACKUP_FILE_PREFIX-$timestamp.json"
    }
}
