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
                issues += ValidationIssue(code = "blank_nickname", message = "Nickname must not be blank", nickname = nickname)
            }
            if (bytes.size > LedgerPasswordsLimits.MAX_NICKNAME_BYTES) {
                issues += ValidationIssue(
                    code = "nickname_too_long",
                    message = "Nickname '$nickname' is ${bytes.size} UTF-8 bytes, max ${LedgerPasswordsLimits.MAX_NICKNAME_BYTES}",
                    nickname = nickname,
                )
            }
            if (!seen.add(nickname)) {
                issues += ValidationIssue(code = "duplicate_nickname", message = "Duplicate nickname: $nickname", nickname = nickname)
            }
            if (entry.charsets.bitmask !in 0x00..0xFF) {
                issues += ValidationIssue(code = "invalid_charset", message = "Invalid charset bitmask for $nickname", nickname = nickname)
            }
            if (NicknameSafety.containsDisallowedControlCharacters(nickname)) {
                issues += ValidationIssue(
                    code = "control_character",
                    message = "Nickname '$nickname' contains control characters that are unsafe for Ledger Passwords.",
                    nickname = nickname,
                )
            }
            if (NicknameSafety.containsDangerousFormatCharacters(nickname)) {
                issues += ValidationIssue(
                    code = "dangerous_format_character",
                    message = "Nickname '$nickname' contains invisible formatting characters that are unsafe for Ledger Passwords.",
                    nickname = nickname,
                )
            }
            if (NicknameSafety.hasLeadingOrTrailingWhitespace(nickname)) {
                issues += ValidationIssue(
                    severity = ValidationSeverity.Warning,
                    code = "leading_or_trailing_whitespace",
                    message = "Nickname '$nickname' starts or ends with whitespace.",
                    nickname = nickname,
                )
            }

            serializedBytes += 3 + bytes.size
        }

        if (serializedBytes > storageSize) {
            issues += ValidationIssue(
                code = "storage_overflow",
                message = "Serialized vault uses $serializedBytes bytes, storage size is $storageSize",
            )
        }

        return ValidationResult(issues)
    }
}
