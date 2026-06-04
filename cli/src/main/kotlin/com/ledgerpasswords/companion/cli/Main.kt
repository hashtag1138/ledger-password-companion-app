package com.ledgerpasswords.companion.cli

import com.ledgerpasswords.companion.core.diff.VaultDiffer
import com.ledgerpasswords.companion.core.edit.VaultEditor
import com.ledgerpasswords.companion.core.LedgerAppCompatibility
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.risk.LedgerPushRiskPolicy
import com.ledgerpasswords.companion.core.risk.PushRiskDecision
import com.ledgerpasswords.companion.core.risk.PushRiskSeverity
import com.ledgerpasswords.companion.core.risk.PushSafetyMode
import com.ledgerpasswords.companion.core.validation.VaultValidator
import com.ledgerpasswords.companion.ledger.backup.BackupJsonCodec
import com.ledgerpasswords.companion.ledger.client.LedgerPasswordsClient
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import com.ledgerpasswords.companion.ledger.transport.LedgerTransport
import com.ledgerpasswords.companion.ledger.transport.PcHidLedgerTransport
import com.ledgerpasswords.companion.ledger.transport.SpeculosTransport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    try {
        LedgerPwCli().run(args.toList())
    } catch (error: Throwable) {
        System.err.println("error: ${error.message}")
        kotlin.system.exitProcess(1)
    }
}

class LedgerPwCli {
    private val backupCodec = BackupJsonCodec()
    private val metadataCodec = MetadataCodec()
    private val editor = VaultEditor()
    private val validator = VaultValidator()
    private val differ = VaultDiffer()
    internal var transportFactory: (List<String>) -> LedgerTransport = { deviceArgs ->
        if (deviceArgs.hasFlag("--hid")) {
            PcHidLedgerTransport()
        } else {
            SpeculosTransport(
                server = deviceArgs.option("--server") ?: SpeculosTransport.DEFAULT_SERVER,
                port = (deviceArgs.option("--port") ?: SpeculosTransport.DEFAULT_PORT.toString()).toInt(),
            )
        }
    }

    fun run(args: List<String>) {
        if (args.isEmpty() || args.first() == "help" || args.first() == "--help") {
            printHelp()
            return
        }
        when (args.first()) {
            "file" -> runFile(args.drop(1))
            "device" -> runDevice(args.drop(1))
            else -> error("Unknown command: ${args.first()}")
        }
    }

    private fun runFile(args: List<String>) {
        require(args.isNotEmpty()) { "Missing file subcommand" }
        when (args.first()) {
            "list" -> list(args.drop(1))
            "validate" -> validate(args.drop(1))
            "add" -> add(args.drop(1))
            "delete" -> delete(args.drop(1))
            "rename" -> rename(args.drop(1))
            "edit" -> edit(args.drop(1))
            "export-raw" -> exportRaw(args.drop(1))
            else -> error("Unknown file subcommand: ${args.first()}")
        }
    }

    private fun list(args: List<String>) {
        val file = args.getPath(0, "backup.json")
        val vault = backupCodec.fromJson(file.readText())
        if (vault.entries.isEmpty()) {
            println("No identifiers")
            return
        }
        println("#  nickname             charsets")
        vault.entries.forEachIndexed { index, entry ->
            println("${index + 1}. ${entry.nickname.padEnd(20)} ${entry.charsets.toLedgerNames().joinToString(",")}")
        }
    }

    private fun validate(args: List<String>) {
        val file = args.getPath(0, "backup.json")
        val inspection = backupCodec.inspect(file.readText())
        val vault = inspection.preferredVault
        val result = validator.validate(vault)
        if (result.isValid) {
            println("OK: ${vault.entries.size} identifiers")
            result.warnings.forEach { println("warning: ${it.message}") }
            inspection.findings.forEach { finding ->
                val prefix = if (finding.severity == PushRiskSeverity.Block) "warning" else "warning"
                println("$prefix: ${finding.message}")
            }
        } else {
            result.issues.forEach { println("${it.code}: ${it.message}") }
            error("Backup is invalid")
        }
    }

    private fun add(args: List<String>) {
        val file = args.getPath(0, "backup.json")
        val nickname = args.getOrNull(1) ?: error("Missing nickname")
        val out = args.requiredOption("--out")
        val charset = CharsetPolicy.fromCli(args.option("--charset"))
        val vault = backupCodec.fromJson(file.readText())
        val updated = editor.add(vault, PasswordIdentifier(nickname = nickname, charsets = charset))
        Path.of(out).writeText(backupCodec.toJson(updated))
        println("Added '$nickname' -> $out")
    }

    private fun delete(args: List<String>) {
        val file = args.getPath(0, "backup.json")
        val nickname = args.getOrNull(1) ?: error("Missing nickname")
        val out = args.requiredOption("--out")
        val vault = backupCodec.fromJson(file.readText())
        val updated = editor.delete(vault, nickname)
        Path.of(out).writeText(backupCodec.toJson(updated))
        println("Deleted '$nickname' -> $out")
    }

    private fun rename(args: List<String>) {
        val file = args.getPath(0, "backup.json")
        val oldName = args.getOrNull(1) ?: error("Missing old nickname")
        val newName = args.getOrNull(2) ?: error("Missing new nickname")
        val out = args.requiredOption("--out")
        val vault = backupCodec.fromJson(file.readText())
        val updated = editor.rename(vault, oldName, newName)
        Path.of(out).writeText(backupCodec.toJson(updated))
        println("Renamed '$oldName' -> '$newName' -> $out")
        println("warning: renaming changes the generated password on Ledger")
    }

    private fun edit(args: List<String>) {
        val file = args.getPath(0, "backup.json")
        val nickname = args.getOrNull(1) ?: error("Missing nickname")
        val out = args.requiredOption("--out")
        val charset = CharsetPolicy.fromCli(args.requiredOption("--charset"))
        val vault = backupCodec.fromJson(file.readText())
        val updated = editor.updateCharsets(vault, nickname, charset)
        Path.of(out).writeText(backupCodec.toJson(updated))
        println("Edited '$nickname' -> $out")
    }

    private fun exportRaw(args: List<String>) {
        val file = args.getPath(0, "backup.json")
        val out = args.requiredOption("--out")
        val vault = backupCodec.fromJson(file.readText())
        Path.of(out).writeBytes(metadataCodec.encode(vault))
        println("Exported raw metadata -> $out")
    }

    private fun runDevice(args: List<String>) {
        require(args.isNotEmpty()) { "Missing device subcommand" }
        when (args.first()) {
            "info" -> deviceInfo(args.drop(1))
            "pull" -> devicePull(args.drop(1))
            "diff" -> deviceDiff(args.drop(1))
            "push" -> devicePush(args.drop(1))
            "verify" -> deviceVerify(args.drop(1))
            else -> error("Unknown device subcommand: ${args.first()}")
        }
    }

    private fun deviceInfo(args: List<String>) = withClient(args) { client ->
        val info = client.getAppInfo()
        val config = client.getAppConfig()
        println("App: ${info.name} ${info.version}")
        println("Storage size: ${config.storageSize}")
        println("Keyboard type: ${config.keyboardType}")
        println("Press enter after typing: ${config.pressEnterAfterTyping}")
    }

    private fun devicePull(args: List<String>) = withClient(args) { client ->
        val out = Path.of(args.requiredOption("--out"))
        val info = client.getAppInfo()
        val raw = client.dumpMetadatas()
        val decoded = metadataCodec.decode(raw)
        out.writeText(backupCodec.toJson(decoded, app = com.ledgerpasswords.companion.ledger.backup.BackupApp(info.name, info.version)))
        println("Pulled ${decoded.vault.entries.size} identifiers -> $out")
    }

    private fun deviceDiff(args: List<String>) = withClient(args) { client ->
        val file = args.firstPathOrNull() ?: error("Missing backup.json")
        val expected = backupCodec.fromJson(file.readText())
        val actual = metadataCodec.decode(client.dumpMetadatas()).vault
        val diff = differ.diff(before = actual, after = expected)

        if (!diff.hasChanges) {
            println("No identifier changes between device and $file")
            return@withClient
        }

        println("Diff device -> $file")
        diff.added.forEach { entry ->
            println("+ ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
        }
        diff.removed.forEach { entry ->
            println("- ${entry.nickname} [${entry.charsets.toLedgerNames().joinToString(",")}]")
        }
        diff.changedCharsets.forEach { change ->
            println(
                "~ ${change.after.nickname} " +
                    "[${change.before.charsets.toLedgerNames().joinToString(",")}] -> " +
                    "[${change.after.charsets.toLedgerNames().joinToString(",")}]",
            )
        }
    }

    private fun devicePush(args: List<String>) = withClient(args) { client ->
        val file = args.firstPathOrNull() ?: error("Missing backup.json")
        val text = file.readText()
        val inspection = backupCodec.inspect(text)
        val vault = inspection.preferredVault
        val dangerousOverride = args.hasFlag("--dangerous-override")
        validator.validate(vault).throwIfInvalid()
        val info = client.getAppInfo()
        if (args.hasFlag("--hid") && !LedgerAppCompatibility.supportsRealDevicePush(info.version)) {
            error(LedgerAppCompatibility.realDeviceWriteBlockedMessage(info.version))
        }
        val config = client.getAppConfig()
        val mode = if (args.hasFlag("--hid")) PushSafetyMode.HardwareSafe else PushSafetyMode.Standard
        val assessment = LedgerPushRiskPolicy(config.storageSize).assess(vault, mode, inspection.findings)
        when (assessment.decision) {
            PushRiskDecision.Allow -> Unit
            PushRiskDecision.Warn -> assessment.summaryLines().forEach { println("warning: $it") }
            PushRiskDecision.Block -> {
                if (!args.hasFlag("--hid") || !dangerousOverride) {
                    val advice =
                        if (args.hasFlag("--hid")) {
                            "\nRetry with --dangerous-override only for explicit debug testing."
                        } else {
                            ""
                        }
                    error("Push blocked by companion safety policy:\n${assessment.summaryLines().joinToString("\n")}$advice")
                }
                assessment.summaryLines().forEach { println("warning: $it") }
                println("warning: dangerous override enabled, continuing with a real-device push.")
            }
        }
        val raw = backupCodec.rawFromJson(text)
        client.loadMetadatas(raw)
        println("Pushed ${vault.entries.size} identifiers from $file")
    }

    private fun deviceVerify(args: List<String>) = withClient(args) { client ->
        val file = args.firstPathOrNull() ?: error("Missing backup.json")
        val expected = backupCodec.rawFromJson(file.readText())
        val actual = client.dumpMetadatas(storageSize = expected.size)
        if (expected.contentEquals(actual)) {
            println("OK: device metadata matches $file")
        } else {
            val mismatches = expected.indices.count { expected[it] != actual[it] }
            error("Device metadata differs from $file ($mismatches differing bytes)")
        }
    }

    private fun <T> withClient(args: List<String>, block: suspend (LedgerPasswordsClient) -> T): T {
        transportFactory(args).use { transport ->
            return runBlocking {
                block(LedgerPasswordsClient(transport))
            }
        }
    }

    private fun printHelp() {
        println(
            """
            ledger-pw - Ledger Passwords Companion CLI

            Offline commands:
              ledger-pw file list <backup.json>
              ledger-pw file validate <backup.json>
              ledger-pw file add <backup.json> <nickname> --charset all|upper,lower,numbers --out <out.json>
              ledger-pw file delete <backup.json> <nickname> --out <out.json>
              ledger-pw file rename <backup.json> <old> <new> --out <out.json>
              ledger-pw file edit <backup.json> <nickname> --charset all|upper,lower,numbers --out <out.json>
              ledger-pw file export-raw <backup.json> --out <metadata.bin>

            Device commands (Speculos TCP by default, add --hid for a real Ledger over USB HID):
              ledger-pw device info [--hid] [--server 127.0.0.1] [--port 9999]
              ledger-pw device pull --out <backup.json> [--hid] [--server 127.0.0.1] [--port 9999]
              ledger-pw device diff <backup.json> [--hid] [--server 127.0.0.1] [--port 9999]
              ledger-pw device push <backup.json> [--hid] [--dangerous-override] [--server 127.0.0.1] [--port 9999]
              ledger-pw device verify <backup.json> [--hid] [--server 127.0.0.1] [--port 9999]
            """.trimIndent(),
        )
    }
}

private fun List<String>.getPath(index: Int, label: String): Path {
    val value = getOrNull(index) ?: error("Missing $label")
    val path = Path.of(value)
    require(Files.exists(path)) { "File not found: $path" }
    return path
}

private fun List<String>.option(name: String): String? {
    val index = indexOf(name)
    return if (index >= 0) getOrNull(index + 1) else null
}

private fun List<String>.requiredOption(name: String): String = option(name) ?: error("Missing required option $name")
private fun List<String>.hasFlag(name: String): Boolean = contains(name)

private fun List<String>.firstPathOrNull(): Path? {
    val value = firstOrNull { !it.startsWith("--") && Path.of(it).exists() }
    return value?.let(Path::of)
}
