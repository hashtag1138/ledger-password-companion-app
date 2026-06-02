package com.ledgerpasswords.companion.ledger.transport

import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SpeculosTransportTest {
    @Test
    fun `exchange connects to the configured TCP port and decodes the response`() {
        ServerSocket(0).use { server ->
            val apduSeen = CompletableFuture<ByteArray>()
            val worker =
                Thread {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val apduLength = input.readInt()
                        val apdu = ByteArray(apduLength)
                        input.readFully(apdu)
                        apduSeen.complete(apdu)

                        val responsePayload = byteArrayOf(0x12, 0x34, 0x56)
                        socket.getOutputStream().writeInt32(responsePayload.size)
                        socket.getOutputStream().write(responsePayload)
                        socket.getOutputStream().write(byteArrayOf(0x90.toByte(), 0x00))
                        socket.getOutputStream().flush()
                    }
                }
            worker.start()

            val transport = SpeculosTransport(server = "127.0.0.1", port = server.localPort)
            val response =
                runBlocking {
                    transport.exchange(
                        cla = 0xB0,
                        ins = 0x01,
                        p1 = 0x00,
                        p2 = 0x00,
                        data = byteArrayOf(),
                    )
                }

            assertEquals("b001000000", apduSeen.get(2, TimeUnit.SECONDS).toHex())
            assertEquals("123456", response.data.toHex())
            assertEquals(0x9000, response.statusWord)

            transport.close()
            worker.join(2_000)
        }
    }

    @Test
    fun `exchange rejects APDU payloads larger than one byte length field`() {
        val transport = SpeculosTransport(port = 1)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    transport.exchange(
                        cla = 0xE0,
                        ins = 0x05,
                        p1 = 0x00,
                        p2 = 0x00,
                        data = ByteArray(256),
                    )
                }
            }

        assertEquals("APDU payload must fit in one byte length field", error.message)
    }
}

private fun OutputStream.writeInt32(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
