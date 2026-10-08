package com.videolive.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.videolive.app.util.DeviceCaps

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val batteryStatus = findViewById<TextView>(R.id.txtBatteryStatus)
        val btnBattery = findViewById<TextView>(R.id.btnBattery)
        btnBattery.setOnClickListener { requestBatteryExemption() }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            batteryStatus.text = "Battery optimization: EXEMPT ✓ (good for long streams)"
            batteryStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        } else {
            batteryStatus.text = "Battery optimization: ACTIVE ⚠ — allow unrestricted battery usage for reliable background streaming."
            batteryStatus.setTextColor(ContextCompat.getColor(this, R.color.amber))
        }

        val engineInfo = findViewById<TextView>(R.id.txtEngineInfo)
        val ffmpegVersion = try {
            FFmpegKitConfig.getFFmpegVersion() ?: "unknown"
        } catch (t: Throwable) {
            "unavailable"
        }
        engineInfo.text = "FFmpeg $ffmpegVersion\n${DeviceCaps.describe()}"

        findViewById<TextView>(R.id.btnOpenLogs).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                Toast.makeText(
                    this,
                    "Please allow unrestricted battery usage for this app in system settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
