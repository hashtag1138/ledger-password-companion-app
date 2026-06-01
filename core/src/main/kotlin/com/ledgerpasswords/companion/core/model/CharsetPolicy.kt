package com.ledgerpasswords.companion.core.model

data class CharsetPolicy(val bitmask: Int) {
    init {
        require(bitmask in 0x00..0xFF) { "Charset bitmask must be between 0x00 and 0xFF" }
    }

    val isAll: Boolean get() = bitmask == 0x00 || bitmask == 0xFF

    fun toLedgerBitmask(): Int = if (isAll) ALL_SETS else bitmask

    fun toLedgerNames(): List<String> =
        if (isAll) {
            listOf("ALL_SETS")
        } else {
            CharsetFlag.entries.filter { bitmask and it.bit != 0 }.map { it.ledgerName }
        }

    companion object {
        const val ALL_SETS: Int = 0xFF

        val All: CharsetPolicy = CharsetPolicy(ALL_SETS)

        fun fromFlags(flags: Iterable<CharsetFlag>): CharsetPolicy {
            val mask = flags.fold(0) { acc, flag -> acc or flag.bit }
            return if (mask == 0) All else CharsetPolicy(mask)
        }

        fun fromLedgerNames(names: Iterable<String>): CharsetPolicy {
            val normalized = names.map { it.trim() }.filter { it.isNotEmpty() }
            if (normalized.isEmpty() || normalized.any { it.equals("ALL_SETS", ignoreCase = true) || it.equals("ALL", ignoreCase = true) }) {
                return All
            }
            val flags = normalized.map { name ->
                CharsetFlag.fromLedgerName(name)
                    ?: throw IllegalArgumentException("Unknown charset name: $name")
            }
            return fromFlags(flags)
        }

        fun fromCli(value: String?): CharsetPolicy {
            if (value.isNullOrBlank()) return All
            val tokens = value.split(',', ';', '+').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty() || tokens.any { it.equals("all", true) || it.equals("all_sets", true) }) return All
            val flags = tokens.map { token ->
                CharsetFlag.fromCliName(token)
                    ?: throw IllegalArgumentException("Unknown charset token: $token")
            }
            return fromFlags(flags)
        }
    }
}
