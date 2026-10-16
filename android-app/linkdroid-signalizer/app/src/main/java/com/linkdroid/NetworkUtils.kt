package com.linkdroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import java.net.InetAddress

object NetworkUtils {
    fun getWifiIp(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) "" else String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            ""
        }
    }

    fun getTailscaleIp(context: Context): String {
        return try {
            val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                for (address in linkProperties.linkAddresses) {
                    val ip = address.address.hostAddress ?: continue
                    if (ip.startsWith("100.") || ip.startsWith("fd7a:") || ip.startsWith("fc00:")) {
                        return ip
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    fun getAllInterfaceIps(context: Context): List<String> {
        val ips = mutableListOf<String>()
        try {
            val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                for (address in linkProperties.linkAddresses) {
                    val ip = address.address.hostAddress ?: continue
                    if (ip.startsWith("127.")) continue
                    if (ip.startsWith("fe80:") || ip.startsWith("FE80:")) continue
                    if (ip == "::1") continue
                    if (ip == "::") continue
                    ips.add(ip)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return ips.distinct()
    }
}
