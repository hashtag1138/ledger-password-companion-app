package com.ledgerpasswords.companion.ledger.backup

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.ledger.metadata.DecodedMetadata
import com.ledgerpasswords.companion.ledger.metadata.Hex
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class BackupJsonCodec(
    private val metadataCodec: MetadataCodec = MetadataCodec(),
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun fromJson(text: String): Vault {
        val file = json.decodeFromString(BackupFile.serializer(), text)
        val entries = file.parsed.map { it.toDomain() }
        return Vault(entries = entries, source = VaultSource.BackupFile)
    }

    fun toJson(vault: Vault, app: BackupApp = BackupApp(name = "Passwords", version = "unknown")): String {
        val raw = metadataCodec.encode(vault)
        val decoded = metadataCodec.decode(raw)
        return toJson(decoded, app)
    }

    fun toJson(decoded: DecodedMetadata, app: BackupApp = BackupApp(name = "Passwords", version = "unknown")): String {
        val file = BackupFile(
            format = FORMAT,
            storageSize = LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE,
            app = app,
            parsed = decoded.vault.entries.map { BackupEntry.fromDomain(it) },
            erased = decoded.erasedEntries.map { BackupEntry.fromDomain(it) },
            corruptions = decoded.corruptions.map { "offset=${it.offset}: ${it.message}" },
            rawMetadatas = decoded.rawHex,
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    fun rawFromJson(text: String): ByteArray {
        val file = json.decodeFromString(BackupFile.serializer(), text)
        return if (!file.rawMetadatas.isNullOrBlank()) {
            Hex.decode(file.rawMetadatas)
        } else {
            metadataCodec.encode(fromJson(text))
        }
    }

    companion object {
        const val FORMAT = "ledger-passwords-companion.v1"
    }
}

@Serializable
data class BackupFile(
    val format: String = BackupJsonCodec.FORMAT,
    @SerialName("storage_size") val storageSize: Int = LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE,
    val app: BackupApp? = null,
    val parsed: List<BackupEntry> = emptyList(),
    @SerialName("nicknames_erased_but_still_stored") val erased: List<BackupEntry> = emptyList(),
    @SerialName("corruptions_encountered") val corruptions: List<String> = emptyList(),
    @SerialName("raw_metadatas") val rawMetadatas: String? = null,
)

@Serializable
data class BackupApp(
    val name: String,
    val version: String,
)

@Serializable
data class BackupEntry(
    val nickname: String,
    val charsets: List<String> = listOf("ALL_SETS"),
) {
    fun toDomain(): PasswordIdentifier = PasswordIdentifier(
        nickname = nickname,
        charsets = CharsetPolicy.fromLedgerNames(charsets),
    )

    companion object {
        fun fromDomain(entry: PasswordIdentifier): BackupEntry = BackupEntry(
            nickname = entry.nickname,
            charsets = entry.charsets.toLedgerNames(),
        )
    }
}
