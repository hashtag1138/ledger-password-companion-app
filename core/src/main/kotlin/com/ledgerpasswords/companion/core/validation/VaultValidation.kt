package com.ledgerpasswords.companion.core.validation

data class ValidationIssue(
    val severity: ValidationSeverity = ValidationSeverity.Error,
    val code: String,
    val message: String,
    val nickname: String? = null,
)

enum class ValidationSeverity {
    Warning,
    Error,
}

data class ValidationResult(
    val issues: List<ValidationIssue> = emptyList(),
) {
    val isValid: Boolean get() = issues.none { it.severity == ValidationSeverity.Error }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.Warning }
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.Error }
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    fun throwIfInvalid() {
        if (!isValid) {
            throw VaultValidationException(errors)
        }
    }
}

class VaultValidationException(
    val issues: List<ValidationIssue>,
) : IllegalArgumentException(issues.joinToString(separator = "; ") { it.message })
