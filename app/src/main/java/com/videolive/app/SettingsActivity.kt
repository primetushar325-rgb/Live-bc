package com.videolive.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.videolive.app.util.ThemeEngine
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.videolive.app.data.SettingsRepository
import com.videolive.app.ffmpeg.FFmpegRuntime
import com.videolive.app.util.DeviceCaps
import com.videolive.app.util.ThemeEngine

class SettingsActivity : AppCompatActivity() {

    /** Seekbar range 0..15 maps to 35..50 °C. */
    private fun tempToProgress(c: Int) = (c - 35).coerceIn(0, 15)
    private fun progressToTemp(p: Int) = p + 35

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeEngine.apply(this)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val settingsRepo = SettingsRepository(this)

        // ---- Phase 7: thermal thresholds -----------------------------------
        val txtWarn = findViewById<TextView>(R.id.txtThermalWarn)
        val txtCrit = findViewById<TextView>(R.id.txtThermalCrit)
        val seekWarn = findViewById<SeekBar>(R.id.seekThermalWarn)
        val seekCrit = findViewById<SeekBar>(R.id.seekThermalCrit)
        seekWarn.progress = tempToProgress(settingsRepo.thermalWarnC)
        seekCrit.progress = tempToProgress(settingsRepo.thermalCritC)
        txtWarn.text = "${settingsRepo.thermalWarnC}°C"
        txtCrit.text = "${settingsRepo.thermalCritC}°C"

        seekWarn.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val warn = progressToTemp(progress)
                // Warning must stay below the safe-stop point.
                val crit = progressToTemp(seekCrit.progress)
                val effective = warn.coerceAtMost(crit - 1)
                if (fromUser && effective != warn) {
                    sb?.progress = tempToProgress(effective)
                }
                settingsRepo.thermalWarnC = effective
                txtWarn.text = "${effective}°C"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekCrit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val crit = progressToTemp(progress)
                val warn = progressToTemp(seekWarn.progress)
                val effective = crit.coerceAtLeast(warn + 1)
                if (fromUser && effective != crit) {
                    sb?.progress = tempToProgress(effective)
                }
                settingsRepo.thermalCritC = effective
                txtCrit.text = "${effective}°C"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---- Phase 3: playlist experimental gate ---------------------------
        val switchPlaylist = findViewById<SwitchCompat>(R.id.switchPlaylist)
        switchPlaylist.isChecked = settingsRepo.playlistEnabled
        switchPlaylist.setOnCheckedChangeListener { _, checked ->
            settingsRepo.playlistEnabled = checked
        }

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
        val report = FFmpegRuntime.verify(this)
        val versionLine = "FFmpeg ${report.ffmpegVersion ?: "unavailable"}"
        val testLine = if (report.ready) {
            "Runtime test: PASSED ✓"
        } else {
            "Runtime test: FAILED — ${report.userMessage} (see Advanced Logs)"
        }
        engineInfo.text = versionLine +
            "\n" + testLine +
            "\nDevice ABI: " + report.deviceAbis.joinToString(", ") +
            "\nPackaged ABIs: " +
            (report.apkAbis.joinToString(", ").ifEmpty { "none found" }) +
            "\n" + DeviceCaps.describe()

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
