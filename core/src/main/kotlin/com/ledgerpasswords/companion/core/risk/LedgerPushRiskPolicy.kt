package com.ledgerpasswords.companion.core.risk

import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.validation.NicknameSafety
import com.ledgerpasswords.companion.core.validation.ValidationResult
import com.ledgerpasswords.companion.core.validation.ValidationSeverity
import com.ledgerpasswords.companion.core.validation.VaultValidator
import kotlin.math.abs

enum class PushRiskSeverity {
    Warning,
    Block,
}

enum class PushRiskDecision {
    Allow,
    Warn,
    Block,
}

data class PushRiskFinding(
    val severity: PushRiskSeverity,
    val code: String,
    val message: String,
    val nickname: String? = null,
)

data class LedgerPushRiskAssessment(
    val validation: ValidationResult,
    val findings: List<PushRiskFinding>,
) {
    val decision: PushRiskDecision
        get() =
            when {
                !validation.isValid || findings.any { it.severity == PushRiskSeverity.Block } -> PushRiskDecision.Block
                validation.hasWarnings || findings.any { it.severity == PushRiskSeverity.Warning } -> PushRiskDecision.Warn
                else -> PushRiskDecision.Allow
            }

    fun summaryLines(): List<String> {
        val validationLines =
            validation.issues.map { issue ->
                val prefix = if (issue.severity == ValidationSeverity.Error) "Blocked" else "Warning"
                "$prefix: ${issue.message}"
            }
        val findingLines =
            findings.map { finding ->
                val prefix = if (finding.severity == PushRiskSeverity.Block) "Blocked" else "Warning"
                "$prefix: ${finding.message}"
            }
        return validationLines + findingLines
    }
}

enum class PushSafetyMode {
    Standard,
    HardwareSafe,
}

class LedgerPushRiskPolicy(
    private val storageSize: Int,
) {
    private val validator = VaultValidator(storageSize)

    fun assess(
        vault: Vault,
        mode: PushSafetyMode,
        extraFindings: List<PushRiskFinding> = emptyList(),
    ): LedgerPushRiskAssessment {
        val validation = validator.validate(vault)
        val findings = mutableListOf<PushRiskFinding>()
        findings +=
            extraFindings.map { finding ->
                if (mode == PushSafetyMode.Standard && finding.severity == PushRiskSeverity.Block) {
                    finding.copy(severity = PushRiskSeverity.Warning)
                } else {
                    finding
                }
            }

        val byNormalizedKey = linkedMapOf<String, MutableList<String>>()
        val byVisualSkeleton = linkedMapOf<String, MutableList<String>>()

        vault.entries.forEach { entry ->
            val nickname = entry.nickname
            if (NicknameSafety.hasLeadingOrTrailingWhitespace(nickname)) {
                findings += PushRiskFinding(
                    severity = if (mode == PushSafetyMode.HardwareSafe) PushRiskSeverity.Block else PushRiskSeverity.Warning,
                    code = "leading_or_trailing_whitespace",
                    message = "Nickname '$nickname' starts or ends with whitespace.",
                    nickname = nickname,
                )
            }

            val normalized = NicknameSafety.normalizedKey(nickname)
            byNormalizedKey.getOrPut(normalized) { mutableListOf() } += nickname

            val skeleton = NicknameSafety.visualSkeleton(nickname)
            byVisualSkeleton.getOrPut(skeleton) { mutableListOf() } += nickname
        }

        byNormalizedKey.values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                findings += PushRiskFinding(
                    severity = PushRiskSeverity.Block,
                    code = "normalized_duplicate_nickname",
                    message = "These nicknames become equivalent after normalization: ${duplicates.joinToString(", ")}.",
                )
            }

        byVisualSkeleton.values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                findings += PushRiskFinding(
                    severity = PushRiskSeverity.Block,
                    code = "confusable_visual_duplicate",
                    message = "These nicknames are visually confusable: ${duplicates.joinToString(", ")}.",
                )
            }

        val normalizedEntries = vault.entries.map { NicknameSafety.visualSkeleton(it.nickname) }
        for (leftIndex in normalizedEntries.indices) {
            for (rightIndex in leftIndex + 1 until normalizedEntries.size) {
                val left = normalizedEntries[leftIndex]
                val right = normalizedEntries[rightIndex]
                val commonPrefix = NicknameSafety.commonPrefixLength(left, right)
                val minLength = minOf(left.length, right.length)
                if (minLength >= 10 &&
                    commonPrefix >= 8 &&
                    abs(left.length - right.length) <= 2
                ) {
                    val leftRaw = vault.entries[leftIndex].nickname
                    val rightRaw = vault.entries[rightIndex].nickname
                    findings += PushRiskFinding(
                        severity = PushRiskSeverity.Warning,
                        code = "close_prefix_nicknames",
                        message = "Nicknames '$leftRaw' and '$rightRaw' are very close and may cause a wrong selection on the Ledger.",
                    )
                }
            }
        }

        if (vault.entries.size >= 2) {
            findings += PushRiskFinding(
                severity = if (mode == PushSafetyMode.HardwareSafe) PushRiskSeverity.Block else PushRiskSeverity.Warning,
                code = "multi_entry_show_second_known_crash",
                message =
                    "Vaults with multiple entries are currently dangerous: " +
                        "our regressions show a reproducible app-passwords crash on \"show password\" for the second item.",
            )
        }

        if (vault.entries.size >= 12) {
            findings += PushRiskFinding(
                severity = PushRiskSeverity.Warning,
                code = "dense_vault",
                message = "The vault contains ${vault.entries.size} entries. Dense lists are known to be fragile in app-passwords.",
            )
        }

        return LedgerPushRiskAssessment(
            validation = validation,
            findings = findings.distinctBy { Triple(it.severity, it.code, it.message) },
        )
    }
}
