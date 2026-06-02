package com.ledgerpasswords.companion.android.storage

import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.ledger.backup.BackupApp
import com.ledgerpasswords.companion.ledger.backup.BackupJsonCodec
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import java.io.File
import java.io.IOException

class LocalVaultStore(
    private val file: File,
    private val codec: BackupJsonCodec = BackupJsonCodec(),
    private val metadataCodec: MetadataCodec = MetadataCodec(),
) {
    fun load(): LocalVaultLoadResult {
        if (!file.exists()) {
            return LocalVaultLoadResult(
                vault = Vault(source = VaultSource.Local),
                message = "Vault local vide. Ajoute un identifiant ou importe depuis Ledger.",
                backupJsonText = null,
            )
        }

        val text =
            try {
                file.readText(Charsets.UTF_8)
            } catch (error: IOException) {
                return LocalVaultLoadResult(
                    vault = null,
                    message = "Impossible de lire ${file.name}: ${error.message ?: error::class.java.simpleName}.",
                    backupJsonText = null,
                )
            }

        return try {
            LocalVaultLoadResult(
                vault = decodeVault(text),
                message = "Vault local chargé depuis ${file.name}.",
                backupJsonText = text,
            )
        } catch (_: Throwable) {
            val backupName = "${file.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}.json"
            runCatching { file.copyTo(File(file.parentFile, backupName), overwrite = true) }
            LocalVaultLoadResult(
                vault = Vault(source = VaultSource.Local),
                message = "Le stockage local était invalide. Une copie a été conservée dans $backupName.",
                backupJsonText = null,
            )
        }
    }

    fun saveVault(vault: Vault): String {
        val normalized = vault.copy(source = VaultSource.Local).sortedByNickname()
        val json = codec.toJson(normalized, app = LOCAL_APP)
        writeTextAtomically(json)
        return json
    }

    fun saveBackupJson(text: String): String {
        decodeVault(text)
        writeTextAtomically(text)
        return text
    }

    private fun decodeVault(text: String): Vault {
        val raw = codec.rawFromJson(text)
        return metadataCodec.decode(raw).vault.copy(source = VaultSource.Local).sortedByNickname()
    }

    private fun writeTextAtomically(json: String) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.tmp")
        tempFile.writeText(json, Charsets.UTF_8)
        if (!tempFile.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    companion object {
        private val LOCAL_APP = BackupApp(name = "Passwords Companion Local Vault", version = "1")
    }
}

data class LocalVaultLoadResult(
    val vault: Vault?,
    val message: String,
    val backupJsonText: String?,
)
