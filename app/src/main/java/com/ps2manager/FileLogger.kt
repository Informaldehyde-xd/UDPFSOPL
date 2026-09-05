/* Writes a plain-text debug log to /storage/emulated/0/Download/udpfs_debug.log
 * so it can be pulled off the device without adb (relies on the "All Files
 * Access" permission the app already requests for the share folder). */
package com.ps2manager.udpfsserver

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

object FileLogger {
    private const val FILE_NAME = "udpfs_debug.log"
    private val lock = ReentrantLock()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun logFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)

    /** Wipes the log and starts a fresh session. Call once each time a server starts. */
    fun startSession(title: String) {
        lock.lock()
        try {
            val f = logFile()
            f.parentFile?.mkdirs()
            f.writeText("=== $title ===\nsession started ${timeFmt.format(Date())}\n\n")
        } catch (e: Exception) {
            Log.e("FileLogger", "failed to start log session (is All Files Access granted?)", e)
        } finally { lock.unlock() }
    }

    fun header(line: String) = append("HEADER", "I", line)

    fun i(tag: String, msg: String) { Log.i(tag, msg); append(tag, "I", msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); append(tag, "D", msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(tag, msg, t); append(tag, "W", msg + (t?.let { " -- $it" } ?: ""))
    }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t); append(tag, "E", msg + (t?.let { " -- $it" } ?: ""))
    }

    /** Hex dump helper for tracing raw packets, e.g. "F5 F5 00 00 01 02". */
    fun hex(data: ByteArray, len: Int = data.size, max: Int = 64): String {
        val n = minOf(len, max)
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", data[i]))
        }
        if (len > max) sb.append(" ...(${len} bytes total)")
        return sb.toString()
    }

    private fun append(tag: String, level: String, msg: String) {
        lock.lock()
        try {
            logFile().appendText("${timeFmt.format(Date())} $level/$tag: $msg\n")
        } catch (e: Exception) {
            // Don't let a logging failure take down the server.
        } finally { lock.unlock() }
    }
}
