package com.ledgerpasswords.companion.ledger.metadata

import com.ledgerpasswords.companion.core.LedgerPasswordsLimits
import com.ledgerpasswords.companion.core.model.CharsetPolicy
import com.ledgerpasswords.companion.core.model.PasswordIdentifier
import com.ledgerpasswords.companion.core.model.Vault
import com.ledgerpasswords.companion.core.model.VaultSource
import com.ledgerpasswords.companion.core.validation.VaultValidator

class MetadataCodec(
    private val storageSize: Int = LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE,
) {
    fun decode(raw: ByteArray): DecodedMetadata {
        require(raw.isNotEmpty()) { "Raw metadata buffer must not be empty" }
        val active = mutableListOf<PasswordIdentifier>()
        val erased = mutableListOf<PasswordIdentifier>()
        val corruptions = mutableListOf<MetadataCorruption>()
        var offset = 0

        while (offset < raw.size) {
            val len = raw[offset].toInt() and 0xFF
            if (len == 0) break

            if (len < 1) {
                corruptions += MetadataCorruption(offset, "Invalid metadata length: $len")
                break
            }
            if (len > LedgerPasswordsLimits.MAX_METANAME_BYTES) {
                corruptions += MetadataCorruption(offset, "Nickname data too long: length=$len max=${LedgerPasswordsLimits.MAX_METANAME_BYTES}")
            }
            if (offset + 2 + len > raw.size) {
                corruptions += MetadataCorruption(offset, "Entry overflows raw metadata buffer")
                break
            }

            val kind = raw[offset + 1].toInt() and 0xFF
            val charset = raw[offset + 2].toInt() and 0xFF
            val nicknameLength = len - 1
            val nicknameBytes = raw.copyOfRange(offset + 3, offset + 3 + nicknameLength)
            val nickname = nicknameBytes.toString(Charsets.UTF_8)
            val entry = PasswordIdentifier(nickname = nickname, charsets = CharsetPolicy(charset))

            when (kind) {
                META_ACTIVE -> active += entry
                META_ERASED -> erased += entry
                else -> corruptions += MetadataCorruption(offset, "Unknown metadata kind: 0x${kind.toString(16)}")
            }

            offset += len + 2
        }

        return DecodedMetadata(
            vault = Vault(entries = active, source = VaultSource.LedgerDevice),
            erasedEntries = erased,
            corruptions = corruptions,
            rawHex = Hex.encode(raw),
        )
    }

    fun encode(vault: Vault): ByteArray {
        VaultValidator(storageSize).validate(vault).throwIfInvalid()
        val raw = ByteArray(storageSize) { 0x00.toByte() }
        var offset = 0

        for (entry in vault.entries) {
            val nicknameBytes = entry.nickname.toByteArray(Charsets.UTF_8)
            require(nicknameBytes.size <= LedgerPasswordsLimits.MAX_NICKNAME_BYTES) {
                "Nickname '${entry.nickname}' is too long: ${nicknameBytes.size} bytes"
            }
            val len = 1 + nicknameBytes.size
            val entrySize = len + 2
            require(offset + entrySize + 2 <= storageSize) {
                "Not enough space to encode metadata entry '${entry.nickname}'"
            }

            raw[offset++] = len.toByte()
            raw[offset++] = META_ACTIVE.toByte()
            raw[offset++] = entry.charsets.toLedgerBitmask().toByte()
            nicknameBytes.copyInto(raw, destinationOffset = offset)
            offset += nicknameBytes.size
        }

        // End marker is already zero-filled. Write two bytes explicitly for readability.
        if (offset < raw.size) raw[offset] = 0x00.toByte()
        if (offset + 1 < raw.size) raw[offset + 1] = 0x00.toByte()
        return raw
    }

    companion object {
        const val META_ACTIVE = 0x00
        const val META_ERASED = 0xFF
    }
}
