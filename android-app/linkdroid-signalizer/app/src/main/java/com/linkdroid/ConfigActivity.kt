package com.linkdroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ConfigActivity : AppCompatActivity() {
    private lateinit var portInput: EditText
    private lateinit var btnSave: Button
    private lateinit var btnBatteryOptimization: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        portInput = findViewById(R.id.port_input)
        btnSave = findViewById(R.id.btn_save)
        btnBatteryOptimization = findViewById(R.id.btn_battery_optimization)
        statusText = findViewById(R.id.status_text)

        val prefs = getSharedPreferences("linkdroid_config", Context.MODE_PRIVATE)
        val savedPort = prefs.getInt("adb_port_override", -1)
        if (savedPort > 0) {
            portInput.setText(savedPort.toString())
        }

        btnSave.setOnClickListener {
            val portStr = portInput.text.toString().trim()
            if (portStr.matches("\\d+".toRegex())) {
                val port = portStr.toInt()
                if (port in 1..65535) {
                    val prefs = getSharedPreferences("linkdroid_config", Context.MODE_PRIVATE)
                    prefs.edit().putInt("adb_port_override", port).apply()
                    Toast.makeText(this, "ADB port saved: $port", Toast.LENGTH_SHORT).show()
                    statusText.text = "Port configured. Restart the service to apply."
                } else {
                    Toast.makeText(this, "Port must be between 1 and 65535", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Enter a valid port number", Toast.LENGTH_SHORT).show()
            }
        }

        btnBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot open battery optimization settings", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Battery optimization not needed on this Android version", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
