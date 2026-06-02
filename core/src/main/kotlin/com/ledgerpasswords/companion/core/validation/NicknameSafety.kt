package com.ledgerpasswords.companion.core.validation

import java.text.Normalizer

object NicknameSafety {
    fun hasLeadingOrTrailingWhitespace(value: String): Boolean = value != value.trimUnicodeWhitespace()

    fun containsDisallowedControlCharacters(value: String): Boolean = value.codePoints().anyMatch { codePoint ->
        Character.isISOControl(codePoint)
    }

    fun containsDangerousFormatCharacters(value: String): Boolean = value.codePoints().anyMatch { codePoint ->
        Character.getType(codePoint) == Character.FORMAT.toInt()
    }

    fun normalizedKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .trimUnicodeWhitespace()
            .lowercase()

    fun visualSkeleton(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        val builder = StringBuilder()
        var previousWasWhitespace = false
        normalized.codePoints().forEach { codePoint ->
            val type = Character.getType(codePoint)
            if (type == Character.FORMAT.toInt()) {
                return@forEach
            }
            if (Character.isWhitespace(codePoint)) {
                if (!previousWasWhitespace) {
                    builder.append(' ')
                    previousWasWhitespace = true
                }
            } else {
                builder.appendCodePoint(codePoint)
                previousWasWhitespace = false
            }
        }
        return builder.toString().trimUnicodeWhitespace().lowercase()
    }

    fun commonPrefixLength(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        var index = 0
        while (index < limit && left[index] == right[index]) {
            index++
        }
        return index
    }
}

internal fun String.trimUnicodeWhitespace(): String = trim { it.isWhitespace() }
