package com.ledgerpasswords.companion.ledger.backup

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.core.risk.PushRiskFinding
import com.ledgerpasswords.companion.core.risk.PushRiskSeverity
import com.ledgerpasswords.companion.ledger.metadata.DecodedMetadata
import com.ledgerpasswords.companion.ledger.metadata.Hex
import com.ledgerpasswords.companion.ledger.metadata.MetadataCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class BackupJsonCodec(
    private val metadataCodec: MetadataCodec = MetadataCodec(),
) {
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

    fun inspect(text: String): BackupInspection {
        val file = json.decodeFromString(BackupFile.serializer(), text)
        val parsedVault = Vault(entries = file.parsed.map { it.toDomain() }, source = VaultSource.BackupFile).sortedByNickname()
        val findings = mutableListOf<PushRiskFinding>()
        val raw =
            if (file.rawMetadatas.isNullOrBlank()) {
                null
            } else {
                runCatching { Hex.decode(file.rawMetadatas) }
                    .getOrElse { error ->
                        findings += PushRiskFinding(
                            severity = PushRiskSeverity.Block,
                            code = "raw_metadata_decode_failed",
                            message = "The raw_metadatas field cannot be decoded: ${error.message ?: error::class.java.simpleName}.",
                        )
                        null
                    }
            }

        if (file.corruptions.isNotEmpty()) {
            findings += PushRiskFinding(
                severity = PushRiskSeverity.Block,
                code = "backup_corruptions_reported",
                message = "The backup reports ${file.corruptions.size} encountered corruption(s).",
            )
        }

        val decodedRaw =
            raw?.let { rawBytes ->
                if (rawBytes.size != file.storageSize) {
                    findings += PushRiskFinding(
                        severity = PushRiskSeverity.Block,
                        code = "raw_storage_size_mismatch",
                        message = "Raw metadata is ${rawBytes.size} bytes, but the backup declares ${file.storageSize}.",
                    )
                }
                runCatching { metadataCodec.decode(rawBytes) }
                    .getOrElse { error ->
                        findings += PushRiskFinding(
                            severity = PushRiskSeverity.Block,
                            code = "raw_metadata_invalid",
                            message = "Raw metadata could not be decoded: ${error.message ?: error::class.java.simpleName}.",
                        )
                        null
                    }
            }

        val rawVault =
            decodedRaw?.let { decoded ->
                if (decoded.corruptions.isNotEmpty()) {
                    findings += PushRiskFinding(
                        severity = PushRiskSeverity.Block,
                        code = "decoded_raw_corruptions",
                        message = "The decoded raw metadata contains ${decoded.corruptions.size} corruption(s).",
                    )
                }
                decoded.vault.copy(source = VaultSource.BackupFile).sortedByNickname()
            }

        if (rawVault != null && rawVault.entries != parsedVault.entries) {
            findings += PushRiskFinding(
                severity = PushRiskSeverity.Block,
                code = "parsed_raw_mismatch",
                message = "The parsed entries and raw_metadatas do not describe the same vault.",
            )
        }

        if (raw != null && rawVault != null) {
            val reencodedMatches =
                runCatching { metadataCodec.encode(rawVault).contentEquals(raw) }
                    .getOrElse { false }
            if (!reencodedMatches) {
                findings += PushRiskFinding(
                    severity = PushRiskSeverity.Block,
                    code = "raw_not_roundtrip_stable",
                    message = "Raw metadata is not stable after local decode/encode.",
                )
            }
        }

        return BackupInspection(
            file = file,
            parsedVault = parsedVault,
            rawVault = rawVault,
            findings = findings.distinctBy { Triple(it.severity, it.code, it.message) },
        )
    }

    companion object {
        const val FORMAT = "ledger-passwords-companion.v1"
    }
}

data class BackupInspection(
    val file: BackupFile,
    val parsedVault: Vault,
    val rawVault: Vault?,
    val findings: List<PushRiskFinding>,
) {
    val hasRawMetadatas: Boolean get() = !file.rawMetadatas.isNullOrBlank()
    val hasBlockingFindings: Boolean get() = findings.any { it.severity == PushRiskSeverity.Block }
    val preferredVault: Vault get() = rawVault ?: parsedVault
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
