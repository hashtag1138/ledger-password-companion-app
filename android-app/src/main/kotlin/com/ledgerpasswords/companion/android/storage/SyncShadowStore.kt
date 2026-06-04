package com.ledgerpasswords.companion.android.storage

import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.core.model.toLedgerComparable
import com.ledgerpasswords.companion.ledger.backup.BackupApp
import com.ledgerpasswords.companion.ledger.backup.BackupJsonCodec
import java.io.File
import java.io.IOException
import java.util.Base64

enum class SyncTargetKind {
    Usb,
    Speculos,
}

data class SyncShadowState(
    val lastSyncedVault: Vault,
    val targetKind: SyncTargetKind,
    val targetDescriptor: String?,
    val storageSize: Int,
    val updatedAtEpochMillis: Long,
)

class SyncShadowStore(
    private val file: File,
    private val codec: BackupJsonCodec = BackupJsonCodec(),
) {
    fun load(): SyncShadowState? {
        if (!file.exists()) return null

        val content =
            try {
                file.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                return null
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

        val targetKind =
            when (values[KEY_TARGET_KIND]?.lowercase()) {
                "usb" -> SyncTargetKind.Usb
                "speculos" -> SyncTargetKind.Speculos
                else -> return null
            }
        val storageSize = values[KEY_STORAGE_SIZE]?.toIntOrNull() ?: return null
        val updatedAtEpochMillis = values[KEY_UPDATED_AT_EPOCH_MS]?.toLongOrNull() ?: return null
        val vaultJson =
            runCatching {
                val base64 = values[KEY_VAULT_JSON_BASE64] ?: return null
                String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
            }.getOrNull() ?: return null
        val vault =
            runCatching {
                codec.fromJson(vaultJson).copy(source = VaultSource.Local).sortedByNickname()
                    .toLedgerComparable()
            }.getOrNull() ?: return null

        return SyncShadowState(
            lastSyncedVault = vault,
            targetKind = targetKind,
            targetDescriptor = values[KEY_TARGET_DESCRIPTOR]?.ifBlank { null },
            storageSize = storageSize,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    fun save(state: SyncShadowState): SyncShadowState {
        val normalizedVault = state.lastSyncedVault.copy(source = VaultSource.Local).sortedByNickname().toLedgerComparable()
        val vaultJson = codec.toJson(normalizedVault, app = SHADOW_APP)
        val text =
            buildString {
                append(KEY_FORMAT)
                append('=')
                append(FORMAT)
                append('\n')
                append(KEY_TARGET_KIND)
                append('=')
                append(state.targetKind.name.lowercase())
                append('\n')
                append(KEY_TARGET_DESCRIPTOR)
                append('=')
                append(state.targetDescriptor.orEmpty())
                append('\n')
                append(KEY_STORAGE_SIZE)
                append('=')
                append(state.storageSize)
                append('\n')
                append(KEY_UPDATED_AT_EPOCH_MS)
                append('=')
                append(state.updatedAtEpochMillis)
                append('\n')
                append(KEY_VAULT_JSON_BASE64)
                append('=')
                append(Base64.getEncoder().encodeToString(vaultJson.toByteArray(Charsets.UTF_8)))
                append('\n')
            }
        writeTextAtomically(text)
        return state.copy(lastSyncedVault = normalizedVault)
    }

    fun clear() {
        if (file.exists()) {
            file.delete()
        }
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
        private const val FORMAT = "ledger-passwords-companion.sync-shadow.v1"
        private const val KEY_FORMAT = "format"
        private const val KEY_TARGET_KIND = "target_kind"
        private const val KEY_TARGET_DESCRIPTOR = "target_descriptor"
        private const val KEY_STORAGE_SIZE = "storage_size"
        private const val KEY_UPDATED_AT_EPOCH_MS = "updated_at_epoch_ms"
        private const val KEY_VAULT_JSON_BASE64 = "vault_json_base64"

        private val SHADOW_APP = BackupApp(name = "Passwords Companion Sync Shadow", version = "1")
    }
}
