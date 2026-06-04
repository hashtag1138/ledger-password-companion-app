package com.ledgerpasswords.companion.android

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupFileNamesTest {
    @Test
    fun `default backup file name includes a human readable timestamp`() {
        val clock = Clock.fixed(Instant.parse("2026-06-04T12:34:56Z"), ZoneId.of("Europe/Paris"))

        assertEquals(
            "ledger-passwords-backup-2026-06-04_14-34-56.json",
            BackupFileNames.defaultBackupFileName(clock),
        )
    }
}
