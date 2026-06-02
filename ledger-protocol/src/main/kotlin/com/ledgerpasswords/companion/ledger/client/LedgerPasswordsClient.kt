package com.ledgerpasswords.companion.ledger.client

import com.ledgerpasswords.companion.ledger.apdu.ApduConstants
import com.ledgerpasswords.companion.ledger.apdu.LedgerStatusException
import com.ledgerpasswords.companion.ledger.apdu.StatusWords
import com.ledgerpasswords.companion.ledger.transport.LedgerTransport
import java.io.ByteArrayOutputStream

data class AppInfo(
    val name: String,
    val version: String,
)

data class AppConfig(
    val storageSize: Int,
    val keyboardType: Int,
    val pressEnterAfterTyping: Boolean,
)

class LedgerPasswordsClient(
    private val transport: LedgerTransport,
) {
    suspend fun getAppInfo(): AppInfo {
        val response = transport.exchange(
            cla = ApduConstants.CLA_SDK,
            ins = ApduConstants.INS_GET_APP_INFO,
        ).ensureSuccess()

        val data = response.data
        require(data.size >= 3) { "Unexpected app info response length: ${data.size}" }
        var offset = 1 // Ledger SDK app-info response starts with a format byte.
        val appNameLength = data[offset++].toInt() and 0xFF
        require(offset + appNameLength <= data.size) { "Malformed app name in response" }
        val appName = data.copyOfRange(offset, offset + appNameLength).toString(Charsets.US_ASCII)
        offset += appNameLength
        require(offset < data.size) { "Missing app version length" }
        val appVersionLength = data[offset++].toInt() and 0xFF
        require(offset + appVersionLength <= data.size) { "Malformed app version in response" }
        val appVersion = data.copyOfRange(offset, offset + appVersionLength).toString(Charsets.US_ASCII)
        return AppInfo(name = appName, version = appVersion)
    }

    suspend fun getAppConfig(): AppConfig {
        val response = transport.exchange(
            cla = ApduConstants.CLA_PASSWORDS,
            ins = ApduConstants.INS_GET_APP_CONFIG,
        ).ensureSuccess()
        val data = response.data
        require(data.size == 6) { "Unexpected app config response length: ${data.size}" }
        val storageSize =
            ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
        return AppConfig(
            storageSize = storageSize,
            keyboardType = data[4].toInt() and 0xFF,
            pressEnterAfterTyping = data[5].toInt() != 0,
        )
    }

    suspend fun dumpMetadatas(storageSize: Int? = null): ByteArray {
        val expectedSize = storageSize ?: getAppConfig().storageSize
        val output = ByteArrayOutputStream(expectedSize)
        while (output.size() < expectedSize) {
            val response = transport.exchange(
                cla = ApduConstants.CLA_PASSWORDS,
                ins = ApduConstants.INS_DUMP_METADATAS,
            ).ensureSuccess()
            require(response.data.size >= 2) { "Dump response must contain a flag and payload bytes" }
            val flag = response.data[0].toInt() and 0xFF
            require(flag == ApduConstants.MORE_DATA_INCOMING || flag == ApduConstants.LAST_CHUNK) {
                "Unexpected dump flag: 0x${flag.toString(16)}"
            }

            val chunkSize = response.data.size - 1
            val nextSize = output.size() + chunkSize
            require(nextSize <= expectedSize) {
                "Dump response exceeds expected size: $nextSize bytes for target $expectedSize"
            }

            output.write(response.data, 1, chunkSize)
            if (flag == ApduConstants.LAST_CHUNK) {
                require(output.size() == expectedSize) {
                    "$expectedSize bytes requested but only ${output.size()} bytes available"
                }
                break
            }
            require(output.size() < expectedSize) {
                "Dump response reached $expectedSize bytes without LAST_CHUNK flag"
            }
        }
        return output.toByteArray().copyOf(expectedSize)
    }

    suspend fun loadMetadatas(raw: ByteArray) {
        require(raw.isNotEmpty()) { "No metadata to load" }
        var offset = 0
        while (offset < raw.size) {
            val end = minOf(offset + ApduConstants.MAX_LOAD_CHUNK_SIZE, raw.size)
            val chunk = raw.copyOfRange(offset, end)
            val isLast = end == raw.size
            transport.exchange(
                cla = ApduConstants.CLA_PASSWORDS,
                ins = ApduConstants.INS_LOAD_METADATAS,
                p1 = if (isLast) ApduConstants.LAST_CHUNK else ApduConstants.MORE_DATA_INCOMING,
                data = chunk,
            ).ensureSuccess()
            offset = end
        }
    }

    private fun com.ledgerpasswords.companion.ledger.transport.ApduResponse.ensureSuccess(): com.ledgerpasswords.companion.ledger.transport.ApduResponse {
        if (statusWord != StatusWords.OK) throw LedgerStatusException(statusWord)
        return this
    }
}
