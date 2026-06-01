package com.ledgerpasswords.companion.core.validation

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.model.Vault

class VaultValidator(
    private val storageSize: Int = LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE,
) {
    fun validate(vault: Vault): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val seen = linkedSetOf<String>()
        var serializedBytes = 2 // final 00 00 marker safety margin

        if (vault.entries.size > LedgerPasswordsLimits.MAX_METADATA_COUNT) {
            issues += ValidationIssue(
                code = "too_many_entries",
                message = "Too many entries: ${vault.entries.size}, max ${LedgerPasswordsLimits.MAX_METADATA_COUNT}",
            )
        }

        for (entry in vault.entries) {
            val nickname = entry.nickname
            val bytes = nickname.toByteArray(Charsets.UTF_8)

            if (nickname.isBlank()) {
                issues += ValidationIssue("blank_nickname", "Nickname must not be blank", nickname)
            }
            if (bytes.size > LedgerPasswordsLimits.MAX_NICKNAME_BYTES) {
                issues += ValidationIssue(
                    "nickname_too_long",
                    "Nickname '$nickname' is ${bytes.size} UTF-8 bytes, max ${LedgerPasswordsLimits.MAX_NICKNAME_BYTES}",
                    nickname,
                )
            }
            if (!seen.add(nickname)) {
                issues += ValidationIssue("duplicate_nickname", "Duplicate nickname: $nickname", nickname)
            }
            if (entry.charsets.bitmask !in 0x00..0xFF) {
                issues += ValidationIssue("invalid_charset", "Invalid charset bitmask for $nickname", nickname)
            }

            serializedBytes += 3 + bytes.size
        }

        if (serializedBytes > storageSize) {
            issues += ValidationIssue(
                "storage_overflow",
                "Serialized vault uses $serializedBytes bytes, storage size is $storageSize",
            )
        }

        return ValidationResult(issues)
    }
}
