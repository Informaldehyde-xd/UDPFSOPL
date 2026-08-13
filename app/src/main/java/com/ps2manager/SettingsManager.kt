package com.ps2manager.udpfsserver

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

object SettingsManager {
    private const val PREFS_NAME = "udpfs_server_settings"
    private const val KEY_SHARE_PATH = "share_path"
    private const val KEY_BIND_IP = "bind_ip"
    private const val KEY_VERBOSE = "verbose_logging"

    fun defaultSharePath(): String =
        File(Environment.getExternalStorageDirectory(), "UDPFS").absolutePath

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSharePath(context: Context): String =
        prefs(context).getString(KEY_SHARE_PATH, defaultSharePath()) ?: defaultSharePath()

    fun setSharePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_SHARE_PATH, path).apply()
    }

    fun getBindIp(context: Context): String =
        prefs(context).getString(KEY_BIND_IP, "") ?: ""

    fun setBindIp(context: Context, ip: String) {
        prefs(context).edit().putString(KEY_BIND_IP, ip).apply()
    }

    fun getVerboseLogging(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VERBOSE, false)

    fun setVerboseLogging(context: Context, verbose: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERBOSE, verbose).apply()
    }

    fun isValidIp(ip: String): Boolean {
        if (ip.isBlank()) return true
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull()
            n != null && n in 0..255 && part == n.toString()
        }
    }
}