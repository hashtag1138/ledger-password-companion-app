package com.ledgerpasswords.companion.core

import com.ledgerpasswords.companion.core.model.Vault

data class VaultCapacitySnapshot(
    val storageSize: Int,
    val usedBytes: Int,
    val remainingBytes: Int,
    val entryCount: Int,
    val maxEntryCount: Int,
) {
    val usageRatio: Float = usedBytes.toFloat() / storageSize.toFloat()
    val remainingEntrySlots: Int = (maxEntryCount - entryCount).coerceAtLeast(0)
    val isNearLimit: Boolean = remainingBytes <= 128 || usageRatio >= 0.85f
    val isOverflowing: Boolean = remainingBytes < 0
}

fun Vault.capacitySnapshot(storageSize: Int = LedgerPasswordsLimits.DEFAULT_STORAGE_SIZE): VaultCapacitySnapshot {
    val usedBytes =
        2 + entries.sumOf { entry ->
            3 + entry.nickname.toByteArray(Charsets.UTF_8).size
        }
    return VaultCapacitySnapshot(
        storageSize = storageSize,
        usedBytes = usedBytes,
        remainingBytes = storageSize - usedBytes,
        entryCount = entries.size,
        maxEntryCount = storageSize / (1 + 1 + 1 + LedgerPasswordsLimits.MAX_METANAME_BYTES),
    )
}
