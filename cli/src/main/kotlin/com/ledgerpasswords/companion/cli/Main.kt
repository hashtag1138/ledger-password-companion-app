package com.ledgerpasswords.companion.cli

import com.ledgerpasswords.companion.core.edit.VaultEditor
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.validation.VaultValidator
import com.ledgerpasswords.companion.ledger.backup.BackupJsonCodec
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

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
        val vault = backupCodec.fromJson(file.readText())
        val result = validator.validate(vault)
        if (result.isValid) {
            println("OK: ${vault.entries.size} identifiers")
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
        error("Device commands are TODO. Implement PcHidLedgerTransport or SpeculosTransport first. Requested: ${args.joinToString(" ")}")
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

            Device commands are placeholders until a real transport is implemented.
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
