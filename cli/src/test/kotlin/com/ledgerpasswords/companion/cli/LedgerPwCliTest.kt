package com.ledgerpasswords.companion.cli

import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.ledger.backup.BackupJsonCodec
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import com.ledgerpasswords.companion.ledger.transport.FakeLedgerTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LedgerPwCliTest {
    private val codec = BackupJsonCodec()
    private val metadataCodec = MetadataCodec()

    @Test
    fun `validate prints success for a valid backup`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier("github")))))

        val output =
            captureStdout {
                LedgerPwCli().run(listOf("file", "validate", input.toString()))
            }

        assertTrue(output.contains("OK: 1 identifiers"))
    }

    @Test
    fun `validate prints warnings for risky but still encodable backup`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier(" leading")))))

        val output =
            captureStdout {
                LedgerPwCli().run(listOf("file", "validate", input.toString()))
            }

        assertTrue(output.contains("OK: 1 identifiers"))
        assertTrue(output.contains("warning: Nickname ' leading' starts or ends with whitespace."))
    }

    @Test
    fun `add command writes updated backup file`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        val outputFile = workdir.resolve("updated.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier("github")))))

        val output =
            captureStdout {
                LedgerPwCli().run(
                    listOf(
                        "file",
                        "add",
                        input.toString(),
                        "gitlab",
                        "--charset",
                        "upper,lower",
                        "--out",
                        outputFile.toString(),
                    ),
                )
            }

        val updated = codec.fromJson(outputFile.readText())

        assertEquals(listOf("github", "gitlab"), updated.entries.map { it.nickname })
        assertTrue(output.contains("Added 'gitlab'"))
    }

    @Test
    fun `export-raw writes a full metadata buffer`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        val outputFile = workdir.resolve("metadata.bin")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier("github")))))

        LedgerPwCli().run(
            listOf(
                "file",
                "export-raw",
                input.toString(),
                "--out",
                outputFile.toString(),
            ),
        )

        assertEquals(4096, outputFile.readBytes().size)
    }

    @Test
    fun `device info prints app and config from transport`() {
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(appVersion = "1.3.1"))

        val output =
            captureStdout {
                cli.run(listOf("device", "info"))
            }

        assertTrue(output.contains("App: Passwords 1.3.1"))
        assertTrue(output.contains("Storage size: 4096"))
    }

    @Test
    fun `device pull exports current device metadata to backup json`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val outputFile = workdir.resolve("pulled.json")
        val raw = metadataCodec.encode(Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("gmail"))))
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(appVersion = "1.3.1", initialMetadatas = raw))

        val output =
            captureStdout {
                cli.run(listOf("device", "pull", "--out", outputFile.toString()))
            }

        val pulled = codec.fromJson(outputFile.readText())
        assertEquals(listOf("github", "gmail"), pulled.entries.map { it.nickname })
        assertTrue(output.contains("Pulled 2 identifiers"))
    }

    @Test
    fun `device push loads metadata into transport and verify confirms it`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("gitlab")))))
        val transport = FakeLedgerTransport()
        val cli = LedgerPwCli().withTransport(transport)

        val pushOutput =
            captureStdout {
                cli.run(listOf("device", "push", input.toString()))
            }
        val verifyOutput =
            captureStdout {
                cli.run(listOf("device", "verify", input.toString()))
            }

        assertTrue(pushOutput.contains("Pushed 2 identifiers"))
        assertTrue(verifyOutput.contains("OK: device metadata matches"))
    }

    @Test
    fun `device push over hid is blocked for passwords 1 3 1 and explains official compatibility`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier("github")))))
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(appVersion = "1.3.1"))

        val error =
            assertThrows(IllegalStateException::class.java) {
                cli.run(listOf("device", "push", input.toString(), "--hid"))
            }

        assertTrue(error.message!!.contains("Real-device write refused"))
        assertTrue(error.message!!.contains("1.3.1"))
        assertTrue(error.message!!.contains("1.3.2"))
        assertTrue(error.message!!.contains("available in Ledger Live"))
        assertTrue(error.message!!.contains("pull/dump remain allowed"))
        assertTrue(error.message!!.contains("https://github.com/LedgerHQ/app-passwords"))
    }

    @Test
    fun `device push over hid is blocked by hardware safe policy for leading whitespace`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier(" leading")))))
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(appVersion = "1.3.2"))

        val error =
            assertThrows(IllegalStateException::class.java) {
                cli.run(listOf("device", "push", input.toString(), "--hid"))
            }

        assertTrue(error.message!!.contains("Push blocked by companion safety policy"))
        assertTrue(error.message!!.contains("starts or ends with whitespace"))
    }

    @Test
    fun `device push over hid is allowed again on passwords 1 3 2`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier("github")))))
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(appVersion = "1.3.2"))

        val output =
            captureStdout {
                cli.run(listOf("device", "push", input.toString(), "--hid"))
            }

        assertTrue(output.contains("Pushed 1 identifiers"))
    }

    @Test
    fun `device push over hid can continue with dangerous override`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        input.writeText(codec.toJson(Vault(entries = listOf(PasswordIdentifier(" leading")))))
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(appVersion = "1.3.2"))

        val output =
            captureStdout {
                cli.run(listOf("device", "push", input.toString(), "--hid", "--dangerous-override"))
            }

        assertTrue(output.contains("warning: dangerous override enabled"))
        assertTrue(output.contains("Pushed 1 identifiers"))
    }

    @Test
    fun `device diff reports no identifier changes when device matches file`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        val vault = Vault(entries = listOf(PasswordIdentifier("github"), PasswordIdentifier("gitlab")))
        input.writeText(codec.toJson(vault))
        val raw = metadataCodec.encode(vault)
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(initialMetadatas = raw))

        val output =
            captureStdout {
                cli.run(listOf("device", "diff", input.toString()))
            }

        assertTrue(output.contains("No identifier changes"))
    }

    @Test
    fun `device diff reports added removed and changed charsets`() {
        val workdir = Files.createTempDirectory("ledger-pw-cli-test")
        val input = workdir.resolve("backup.json")
        val deviceVault =
            Vault(
                entries = listOf(
                    PasswordIdentifier("github"),
                    PasswordIdentifier("gmail"),
                ),
            )
        val fileVault =
            Vault(
                entries = listOf(
                    PasswordIdentifier("github", charsets = com.ledgerpasswords.companion.core.model.CharsetPolicy.fromCli("upper,lower")),
                    PasswordIdentifier("proton"),
                ),
            )
        input.writeText(codec.toJson(fileVault))
        val raw = metadataCodec.encode(deviceVault)
        val cli = LedgerPwCli().withTransport(FakeLedgerTransport(initialMetadatas = raw))

        val output =
            captureStdout {
                cli.run(listOf("device", "diff", input.toString()))
            }

        assertTrue(output.contains("Diff device ->"))
        assertTrue(output.contains("+ proton [ALL_SETS]"))
        assertTrue(output.contains("- gmail [ALL_SETS]"))
        assertTrue(output.contains("~ github [ALL_SETS] -> [UPPERCASE,LOWERCASE]"))
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        return try {
            block()
            buffer.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }

    private fun LedgerPwCli.withTransport(transport: FakeLedgerTransport): LedgerPwCli {
        transportFactory = { transport }
        return this
    }
}
