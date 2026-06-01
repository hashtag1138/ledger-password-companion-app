package com.ledgerpasswords.companion.core.model

enum class CharsetFlag(val bit: Int, val ledgerName: String) {
    UPPERCASE(0x01, "UPPERCASE"),
    LOWERCASE(0x02, "LOWERCASE"),
    NUMBERS(0x04, "NUMBERS"),
    MINUS(0x08, "MINUS"),
    UNDERLINE(0x10, "UNDERLINE"),
    SPACE(0x20, "SPACE"),
    SPECIAL(0x40, "SPECIAL"),
    BRACKETS(0x80, "BRACKETS");

    companion object {
        fun fromLedgerName(name: String): CharsetFlag? =
            entries.firstOrNull { it.ledgerName.equals(name, ignoreCase = true) }

        fun fromCliName(name: String): CharsetFlag? = when (name.trim().lowercase()) {
            "upper", "uppercase", "maj", "majuscules" -> UPPERCASE
            "lower", "lowercase", "min", "minuscules" -> LOWERCASE
            "num", "number", "numbers", "chiffres" -> NUMBERS
            "minus", "dash", "tiret" -> MINUS
            "underline", "underscore" -> UNDERLINE
            "space", "espace" -> SPACE
            "special", "specials", "speciaux", "spéciaux" -> SPECIAL
            "bracket", "brackets" -> BRACKETS
            else -> fromLedgerName(name)
        }
    }
}
