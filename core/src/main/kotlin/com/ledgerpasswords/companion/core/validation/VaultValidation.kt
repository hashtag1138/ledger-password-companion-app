package com.ledgerpasswords.companion.core.validation

data class ValidationIssue(
    val code: String,
    val message: String,
    val nickname: String? = null,
)

data class ValidationResult(
    val issues: List<ValidationIssue> = emptyList(),
) {
    val isValid: Boolean get() = issues.isEmpty()

    fun throwIfInvalid() {
        if (!isValid) {
            throw VaultValidationException(issues)
        }
    }
}

class VaultValidationException(
    val issues: List<ValidationIssue>,
) : IllegalArgumentException(issues.joinToString(separator = "; ") { it.message })
