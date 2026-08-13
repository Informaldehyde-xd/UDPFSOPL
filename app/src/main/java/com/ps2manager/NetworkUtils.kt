package com.ps2manager.udpfsserver

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidates = interfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> Collections.list(iface.inetAddresses).map { iface.name to it } }
                .filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress }

            val preferred = candidates.firstOrNull { (name, _) ->
                name.contains("wlan", ignoreCase = true) || name.contains("ap", ignoreCase = true)
            } ?: candidates.firstOrNull()

            preferred?.second?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}