package com.linkdroid

import android.content.Context
import android.os.Build

object BeaconProtocol {
    const val SERVICE_TYPE = "_linkdroid._tcp."
    const val TCP_PORT = 9876
    const val BEACON_VERSION = 2

    fun resolveServiceName(context: Context): String {
        val device = Build.DEVICE ?: ""
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val model = Build.MODEL ?: "unknown"

        return when {
            device.isNotBlank() && device.length >= 4 -> "Linkdroid-${device.take(8)}"
            else -> {
                val sanitizedManufacturer = manufacturer.lowercase().replace(" ", "-")
                val sanitizedModel = model.lowercase().replace(" ", "-")
                "Linkdroid-$sanitizedManufacturer-$sanitizedModel"
            }
        }
    }

    fun buildBeacon(
        adbPort: Int,
        wifiIp: String,
        tailscaleIp: String,
        deviceName: String,
        androidVersion: String,
        deviceModel: String,
        allIps: List<String> = emptyList(),
        callState: String = "IDLE"
    ): String = buildString {
        appendLine("LINKDROID $BEACON_VERSION")
        appendLine("DEVICE_NAME=$deviceName")
        appendLine("ADB_PORT=$adbPort")
        appendLine("WIFI_IP=$wifiIp")
        appendLine("TAILSCALE_IP=$tailscaleIp")
        if (allIps.isNotEmpty()) {
            appendLine("ALL_IPS=${allIps.joinToString(",")}")
        }
        appendLine("ANDROID_VERSION=$androidVersion")
        appendLine("DEVICE_MODEL=$deviceModel")
        appendLine("BEACON_VERSION=$BEACON_VERSION")
        appendLine("CALL_STATE=$callState")
    }

    data class ParsedBeacon(
        val deviceName: String,
        val adbPort: Int,
        val wifiIp: String,
        val tailscaleIp: String,
        val allIps: List<String>,
        val androidVersion: String,
        val deviceModel: String,
        val beaconVersion: Int,
        val callState: String
    )

    fun parseBeacon(raw: String): ParsedBeacon {
        var deviceName = ""
        var adbPort = 0
        var wifiIp = ""
        var tailscaleIp = ""
        var allIps = emptyList<String>()
        var androidVersion = ""
        var deviceModel = ""
        var beaconVersion = 1
        var callState = "IDLE"

        raw.split("\n", "\r\n").forEach { line ->
            when {
                line.startsWith("DEVICE_NAME=") -> deviceName = line.substringAfter("DEVICE_NAME=").trim()
                line.startsWith("ADB_PORT=") -> adbPort = line.substringAfter("ADB_PORT=").trim().toIntOrNull() ?: 0
                line.startsWith("WIFI_IP=") -> wifiIp = line.substringAfter("WIFI_IP=").trim()
                line.startsWith("TAILSCALE_IP=") -> tailscaleIp = line.substringAfter("TAILSCALE_IP=").trim()
                line.startsWith("ALL_IPS=") -> allIps = line.substringAfter("ALL_IPS=").trim().split(",").filter { it.isNotEmpty() }
                line.startsWith("ANDROID_VERSION=") -> androidVersion = line.substringAfter("ANDROID_VERSION=").trim()
                line.startsWith("DEVICE_MODEL=") -> deviceModel = line.substringAfter("DEVICE_MODEL=").trim()
                line.startsWith("BEACON_VERSION=") -> beaconVersion = line.substringAfter("BEACON_VERSION=").trim().toIntOrNull() ?: 1
                line.startsWith("CALL_STATE=") -> callState = line.substringAfter("CALL_STATE=").trim()
            }
        }

        return ParsedBeacon(
            deviceName.ifEmpty { "Linkdroid Device" },
            adbPort,
            wifiIp,
            tailscaleIp,
            allIps,
            androidVersion,
            deviceModel,
            beaconVersion,
            callState
        )
    }
}
