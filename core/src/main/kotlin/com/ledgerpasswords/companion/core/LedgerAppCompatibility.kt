package com.ledgerpasswords.companion.core

private val VERSION_PATTERN = Regex("""^\s*(\d+)\.(\d+)\.(\d+)(?:\D.*)?$""")
private const val OFFICIAL_APP_REPO_URL = "https://github.com/LedgerHQ/app-passwords"

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    fun label(): String = "$major.$minor.$patch"
}

object LedgerAppCompatibility {
    val MIN_SAFE_REAL_DEVICE_PASSWORDS_VERSION: SemanticVersion = SemanticVersion(1, 3, 2)
    val minSafeRealDeviceVersionLabel: String
        get() = MIN_SAFE_REAL_DEVICE_PASSWORDS_VERSION.label()

    fun parseSemanticVersion(raw: String): SemanticVersion? {
        val match = VERSION_PATTERN.matchEntire(raw) ?: return null
        val (major, minor, patch) = match.destructured
        return SemanticVersion(
            major = major.toInt(),
            minor = minor.toInt(),
            patch = patch.toInt(),
        )
    }

    fun supportsRealDevicePush(appVersion: String): Boolean {
        val parsed = parseSemanticVersion(appVersion) ?: return false
        return parsed >= MIN_SAFE_REAL_DEVICE_PASSWORDS_VERSION
    }

    fun realDeviceReadOnlyNotice(): String =
        " Read-only only: real writes require the official Passwords app $minSafeRealDeviceVersionLabel+ from Ledger Live."

    fun realDeviceWriteBlockedMessage(appVersion: String): String {
        val versionClause =
            if (parseSemanticVersion(appVersion) == null) {
                "could not be validated against the required version $minSafeRealDeviceVersionLabel"
            } else {
                "is older than the required version $minSafeRealDeviceVersionLabel"
            }
        return """
            Real-device write refused: Passwords app $appVersion $versionClause.

            Real-device writes require the official Passwords app $minSafeRealDeviceVersionLabel or newer, available in Ledger Live.

            Read-only operations such as pull/dump remain allowed.

            Official app repository:
            - $OFFICIAL_APP_REPO_URL
        """.trimIndent()
    }
}
