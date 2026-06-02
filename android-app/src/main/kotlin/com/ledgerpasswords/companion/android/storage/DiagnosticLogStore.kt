package com.ledgerpasswords.companion.android.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogStore {
    const val FILE_NAME: String = "ledger-debug.log"
    private const val MAX_BYTES: Long = 512 * 1024

    private val lock = Any()
    private val formatter =
        ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }

    @Volatile
    private var file: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (file == null) {
                file = File(context.filesDir, FILE_NAME).also { target ->
                    target.parentFile?.mkdirs()
                    appendUnlocked(target, "MARK/LedgerPwDiag session start pid=${android.os.Process.myPid()}")
                }
            }
        }
    }

    fun mark(message: String) {
        append("MARK", "LedgerPwDiag", message)
    }

    fun info(tag: String, message: String) {
        Log.i(tag, message)
        append("INFO", tag, message)
    }

    fun debug(tag: String, message: String) {
        Log.d(tag, message)
        append("DEBUG", tag, message)
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, message)
        append("WARN", tag, message)
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        append(
            "ERROR",
            tag,
            buildString {
                append(message)
                if (error != null) {
                    append('\n')
                    append(error.stackTraceAsString())
                }
            },
        )
    }

    private fun append(level: String, tag: String, message: String) {
        synchronized(lock) {
            val target = file ?: return
            ensureCapacity(target, message.length)
            appendUnlocked(target, "$level/$tag $message")
        }
    }

    private fun ensureCapacity(target: File, incomingChars: Int) {
        if (!target.exists()) return
        if (target.length() + incomingChars < MAX_BYTES) return

        val retained = target.readText(Charsets.UTF_8).takeLast((MAX_BYTES / 2).toInt())
        target.writeText(retained, Charsets.UTF_8)
        appendUnlocked(target, "INFO/LedgerPwDiag log truncated to keep latest entries")
    }

    private fun appendUnlocked(target: File, line: String) {
        val timestamp = formatter.get()?.format(Date()) ?: Date().toString()
        target.appendText("$timestamp $line\n", Charsets.UTF_8)
    }

    private fun Throwable.stackTraceAsString(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }
}
