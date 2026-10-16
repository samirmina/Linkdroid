package com.linkdroid

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket

class SignalizerService : Service() {
    private val binder = LocalBinder()
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverSocket: ServerSocket? = null
    private var beaconThread: Thread? = null
    private var isRunning = false
    private var callState: String = "IDLE"
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): SignalizerService = this@SignalizerService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground()
        } catch (e: Exception) {
            // ignore foreground start failure
        }
        requestBatteryOptimizationExemption()
        setupCallStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!isRunning) {
                isRunning = true
                startAdvertising()
            }
        } catch (e: Exception) {
            // ignore
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAdvertising()
        stopBeaconServer()
        cleanupCallStateListener()
        isRunning = false
    }

    private fun setupCallStateListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    val newState = when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                        else -> "IDLE"
                    }
                    callState = newState
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            telephonyManager = null
            phoneStateListener = null
        } catch (e: Exception) {
            telephonyManager = null
            phoneStateListener = null
        }
    }

    private fun cleanupCallStateListener() {
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        telephonyManager = null
        phoneStateListener = null
    }

    fun answerCall() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.acceptRingingCall()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun declineCall() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.acceptRingingCall()
                Thread.sleep(500)
                telecomManager.endCall()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun hangUpCall() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startForeground() {
        val channelId = "linkdroid_signalizer"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Linkdroid Signalizer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val adbPort = getCurrentAdbPort() ?: -1
        val contentText = if (adbPort > 0) {
            "Broadcasting on port $adbPort"
        } else {
            "ADB port not configured - tap to set"
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Linkdroid Signalizer")
            .setContentText(contentText)
            .setOngoing(true)
            .apply {
                if (adbPort <= 0) {
                    setContentIntent(
                        android.app.PendingIntent.getActivity(
                            this@SignalizerService,
                            0,
                            Intent(this@SignalizerService, ConfigActivity::class.java),
                            android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }
            }
            .build()

        startForeground(1, notification)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                // ignore - some OEMs don't support this
            }
        }
    }

    private fun startAdvertising() {
        val wifiIp = NetworkUtils.getWifiIp(this)
        val tailscaleIp = NetworkUtils.getTailscaleIp(this)
        val allIps = NetworkUtils.getAllInterfaceIps(this)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val androidVersion = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}"
        val deviceModel = Build.MODEL ?: "unknown"
        val serviceName = BeaconProtocol.resolveServiceName(this)

        val adbPort = getCurrentAdbPort() ?: 0

        if (adbPort <= 0) {
            updateNotificationForNoPort()
        }

        val nsd = getSystemService(NSD_SERVICE)
        if (nsd !is NsdManager) {
            return
        }
        nsdManager = nsd
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = BeaconProtocol.SERVICE_TYPE
            setPort(BeaconProtocol.TCP_PORT)
            setAttribute("adbport", adbPort.toString())
            setAttribute("wifiip", wifiIp)
            setAttribute("tailscaleip", tailscaleIp)
            setAttribute("allips", allIps.joinToString(","))
            setAttribute("device", deviceName)
            setAttribute("androidversion", androidVersion)
            setAttribute("devicemodel", deviceModel)
            setAttribute("beaconversion", BeaconProtocol.BEACON_VERSION.toString())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdInfo: NsdServiceInfo) {
                // service registered
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // registration failed
            }

            override fun onServiceUnregistered(nsdInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            // ignore
        }

        startBeaconServer(adbPort, wifiIp, tailscaleIp, deviceName, androidVersion, deviceModel, allIps)
    }

    private fun updateNotificationForNoPort() {
        val channelId = "linkdroid_signalizer"
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Linkdroid Signalizer")
            .setContentText("ADB port not configured - tap to set")
            .setOngoing(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, ConfigActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    private fun getCurrentAdbPort(): Int? {
        // Method 1: getprop
        try {
            val process = Runtime.getRuntime().exec("getprop service.adb.tcp.port")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val portStr = reader.readLine()?.trim() ?: return null
            reader.close()
            process.destroy()
            if (portStr.isEmpty() || portStr == "-1") return null
            val port = portStr.toIntOrNull()
            if (port != null && port > 0) return port
        } catch (e: Exception) {
            // ignore
        }

        // Method 2: SharedPreferences override
        val prefs = getSharedPreferences("linkdroid_config", Context.MODE_PRIVATE)
        val overridePort = prefs.getInt("adb_port_override", -1)
        if (overridePort > 0) return overridePort

        // Method 3: Try to read from Settings.Secure
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val adbEnabled = Settings.Secure.getInt(contentResolver, "adb_enabled")
                if (adbEnabled == 1) {
                    val port = Settings.Secure.getInt(contentResolver, "adb_port")
                    if (port > 0) return port
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return null
    }

    private fun startBeaconServer(
        adbPort: Int,
        wifiIp: String,
        tailscaleIp: String,
        deviceName: String,
        androidVersion: String,
        deviceModel: String,
        allIps: List<String> = emptyList()
    ) {
        beaconThread = Thread {
            try {
                serverSocket = ServerSocket(BeaconProtocol.TCP_PORT)
                serverSocket?.soTimeout = 0

                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        client.soTimeout = 60000
                        handleBeaconClient(client, adbPort, wifiIp, tailscaleIp, deviceName, androidVersion, deviceModel, allIps)
                    } catch (e: java.net.BindException) {
                        // Port 9876 in use, try alternate port
                        try {
                            serverSocket?.close()
                            val alternatePort = 9877
                            serverSocket = ServerSocket(alternatePort)
                            // Update mDNS service port
                            nsdManager?.unregisterService(registrationListener)
                            val serviceInfo = NsdServiceInfo().apply {
                                serviceName = BeaconProtocol.resolveServiceName(this@SignalizerService)
                                serviceType = BeaconProtocol.SERVICE_TYPE
                                setPort(alternatePort)
                                setAttribute("adbport", adbPort.toString())
                                setAttribute("wifiip", wifiIp)
                                setAttribute("tailscaleip", tailscaleIp)
                                setAttribute("allips", allIps.joinToString(","))
                                setAttribute("device", deviceName)
                                setAttribute("androidversion", androidVersion)
                                setAttribute("devicemodel", deviceModel)
                                setAttribute("beaconversion", BeaconProtocol.BEACON_VERSION.toString())
                            }
                            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
                        } catch (e2: Exception) {
                            // cannot bind any port
                            break
                        }
                    } catch (e: Exception) {
                        // continue accepting
                    }
                }
            } catch (e: Exception) {
                // server stopped
            }
        }
        beaconThread?.start()
    }

    private fun handleBeaconClient(
        client: Socket,
        adbPort: Int,
        wifiIp: String,
        tailscaleIp: String,
        deviceName: String,
        androidVersion: String,
        deviceModel: String,
        allIps: List<String>
    ) {
        try {
            val output = client.getOutputStream()
            val input = client.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val writer = OutputStreamWriter(output)

            fun sendBeacon() {
                val beacon = BeaconProtocol.buildBeacon(
                    adbPort, wifiIp, tailscaleIp, deviceName,
                    androidVersion, deviceModel, allIps, callState
                )
                writer.write(beacon)
                writer.flush()
            }

            sendBeacon()

            while (isRunning && !client.isClosed) {
                try {
                    val line = reader.readLine() ?: break
                    val command = line.trim()
                    when (command) {
                        "ANSWER" -> {
                            answerCall()
                            sendBeacon()
                        }
                        "DECLINE" -> {
                            declineCall()
                            sendBeacon()
                        }
                        "HANGUP" -> {
                            hangUpCall()
                            sendBeacon()
                        }
                        "PING" -> {
                            sendBeacon()
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            // client disconnected
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun stopBeaconServer() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        beaconThread = null
    }

    private fun stopAdvertising() {
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {
            // ignore
        }
        nsdManager = null
        registrationListener = null
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SignalizerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalizerService::class.java)
            context.stopService(intent)
        }
    }
}
