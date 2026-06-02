package com.ledgerpasswords.companion.ledger.transport

import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket

class SpeculosTransport(
    private val server: String = DEFAULT_SERVER,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : LedgerTransport {
    private var socket: Socket? = null

    override suspend fun exchange(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ApduResponse {
        require(data.size <= 0xFF) { "APDU payload must fit in one byte length field" }

        val apdu = byteArrayOf(
            cla.toByte(),
            ins.toByte(),
            p1.toByte(),
            p2.toByte(),
            data.size.toByte(),
        ) + data

        val currentSocket = socket()
        currentSocket.getOutputStream().writeInt32(apdu.size)
        currentSocket.getOutputStream().write(apdu)
        currentSocket.getOutputStream().flush()

        val responseLength = currentSocket.getInputStream().readInt32()
        val responseData = currentSocket.getInputStream().readExactly(responseLength)
        val statusWord = currentSocket.getInputStream().readUint16()
        return ApduResponse(data = responseData, statusWord = statusWord)
    }

    override fun close() {
        socket?.close()
        socket = null
    }

    private fun socket(): Socket {
        socket?.let { return it }

        val created =
            Socket().apply {
                soTimeout = readTimeoutMs
                connect(InetSocketAddress(server, this@SpeculosTransport.port), connectTimeoutMs)
            }
        socket = created
        return created
    }

    companion object {
        const val DEFAULT_SERVER = "127.0.0.1"
        const val DEFAULT_PORT = 9999
        const val DEFAULT_CONNECT_TIMEOUT_MS = 2_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
    }
}

private fun java.io.OutputStream.writeInt32(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun java.io.InputStream.readInt32(): Int {
    val bytes = readExactly(4)
    return ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)
}

private fun java.io.InputStream.readUint16(): Int {
    val bytes = readExactly(2)
    return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
}

private fun java.io.InputStream.readExactly(length: Int): ByteArray {
    val output = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(output, offset, length - offset)
        if (read < 0) {
            throw EOFException("Unexpected end of stream after $offset bytes, expected $length")
        }
        offset += read
    }
    return output
}
