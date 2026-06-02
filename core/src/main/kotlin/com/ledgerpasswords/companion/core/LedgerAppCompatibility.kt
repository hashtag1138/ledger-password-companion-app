package com.ledgerpasswords.companion.core

private val VERSION_PATTERN = Regex("""^\s*(\d+)\.(\d+)\.(\d+)(?:\D.*)?$""")

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
}

object LedgerAppCompatibility {
    val MIN_SAFE_REAL_DEVICE_PASSWORDS_VERSION: SemanticVersion = SemanticVersion(1, 3, 1)

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
}
