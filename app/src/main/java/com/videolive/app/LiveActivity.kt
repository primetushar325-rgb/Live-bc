package com.videolive.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.videolive.app.stream.MicMixer
import com.videolive.app.stream.Phase
import com.videolive.app.stream.StreamService
import com.videolive.app.stream.StreamUiState
import com.videolive.app.util.Texts
import kotlinx.coroutines.launch

class LiveActivity : AppCompatActivity() {

    private lateinit var txtElapsed: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtVideoName: TextView
    private lateinit var txtResolution: TextView
    private lateinit var txtFps: TextView
    private lateinit var txtBitrate: TextView
    private lateinit var txtConnection: TextView
    private lateinit var txtAudio: TextView
    private lateinit var btnMuteMic: TextView
    private lateinit var txtYouTubeHint: TextView
    private lateinit var txtLoop: TextView
    private lateinit var txtNetwork: TextView
    private lateinit var btnRetryNow: TextView
    private lateinit var rowNowPlaying: View
    private lateinit var txtNowPlaying: TextView
    private lateinit var txtReconnects: TextView
    private lateinit var txtDeviceTemp: TextView
    private lateinit var rowLastError: View
    private lateinit var txtLastError: TextView
    private lateinit var rowMicLevel: View
    private lateinit var txtMicLevel: TextView

    private var errorHandled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)

        txtElapsed = findViewById(R.id.txtElapsed)
        txtStatus = findViewById(R.id.txtStatus)
        txtVideoName = findViewById(R.id.txtVideoName)
        txtResolution = findViewById(R.id.txtResolution)
        txtFps = findViewById(R.id.txtFps)
        txtBitrate = findViewById(R.id.txtBitrate)
        txtConnection = findViewById(R.id.txtConnection)
        txtAudio = findViewById(R.id.txtAudio)
        btnMuteMic = findViewById(R.id.btnMuteMic)
        txtYouTubeHint = findViewById(R.id.txtYouTubeHint)
        txtLoop = findViewById(R.id.txtLoop)
        txtNetwork = findViewById(R.id.txtNetwork)
        btnRetryNow = findViewById(R.id.btnRetryNow)
        btnRetryNow.setOnClickListener { StreamService.requestRetryNow() }
        rowNowPlaying = findViewById(R.id.rowNowPlaying)
        txtNowPlaying = findViewById(R.id.txtNowPlaying)
        txtReconnects = findViewById(R.id.txtReconnects)
        txtDeviceTemp = findViewById(R.id.txtDeviceTemp)
        rowLastError = findViewById(R.id.rowLastError)
        txtLastError = findViewById(R.id.txtLastError)
        rowMicLevel = findViewById(R.id.rowMicLevel)
        txtMicLevel = findViewById(R.id.txtMicLevel)

        findViewById<TextView>(R.id.btnStopLive).setOnClickListener { confirmStop() }
        findViewById<ImageButton>(R.id.btnLiveSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnMuteMic.setOnClickListener {
            val newMuted = !MicMixer.muted
            MicMixer.muted = newMuted
            StreamService.uiState.value = StreamService.uiState.value.copy(micMuted = newMuted)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamService.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: StreamUiState) {
        txtElapsed.text = Texts.formatClock(state.elapsedMs)
        txtStatus.text = state.statusText
        txtStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.phase == Phase.ERROR) R.color.red else R.color.cyan
            )
        )

        txtVideoName.text = state.videoName.ifEmpty { "—" }
        txtResolution.text =
            if (state.outWidth > 0) "${state.outWidth}x${state.outHeight}" else "—"
        txtFps.text =
            if (state.fps > 0) "${state.fps}" +
                (if (state.liveFps > 0f) String.format(" (%.1f live)", state.liveFps) else "")
            else "—"
        txtBitrate.text = when {
            state.liveBitrateKbps > 0 ->
                "${state.liveBitrateKbps} kbps (target ${state.configuredBitrateKbps})"
            state.configuredBitrateKbps > 0 -> "${state.configuredBitrateKbps} kbps"
            else -> "—"
        }

        when (state.phase) {
            Phase.STREAMING -> {
                txtConnection.text = "Sending video ✓"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.green))
            }
            Phase.PUBLISHING -> {
                txtConnection.text = "RTMP Connected ✓"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.green))
            }
            Phase.ENCODING -> {
                txtConnection.text = "Encoder started…"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.amber))
            }
            Phase.CONNECTING -> {
                txtConnection.text = "Connecting…"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.amber))
            }
            Phase.PREPARING -> {
                txtConnection.text = "Preparing…"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.amber))
            }
            Phase.RECONNECTING -> {
                txtConnection.text = "Reconnecting ${state.attempt}/5…"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.amber))
            }
            Phase.ERROR -> {
                txtConnection.text = "Failed"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.red))
            }
            else -> {
                txtConnection.text = "—"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        // Show the YouTube confirmation guidance once the encoder is actually
        // sending video — the app cannot see YouTube's broadcast state itself.
        txtYouTubeHint.visibility =
            if (state.phase == Phase.PUBLISHING || state.phase == Phase.STREAMING) View.VISIBLE
            else View.GONE

        btnRetryNow.visibility =
            if (state.phase == Phase.RECONNECTING) View.VISIBLE else View.GONE

        txtLoop.text = if (state.playlistPosition.isNotEmpty()) {
            "Loop ${state.loopCount + 1} (playlist)"
        } else {
            "Loop ${state.loopCount + 1} • item 1/1 (${state.videoName})"
        }

        // Phase 3: show which playlist item is on air.
        if (state.playlistPosition.isNotEmpty()) {
            rowNowPlaying.visibility = View.VISIBLE
            txtNowPlaying.text = state.playlistPosition
        } else {
            rowNowPlaying.visibility = View.GONE
        }

        // Phase 9: real reconnect counter + last error (if any).
        txtReconnects.text = if (state.attempt > 0) "${state.attempt}/5" else "0"
        if (state.lastError.isNotEmpty()) {
            rowLastError.visibility = View.VISIBLE
            txtLastError.text = state.lastError
        } else {
            rowLastError.visibility = View.GONE
        }

        // Phase 7: real battery-sensor temperature (0 = not read yet).
        txtDeviceTemp.text = if (state.deviceTempC > 0.0) {
            String.format(java.util.Locale.US, "%.1f°C", state.deviceTempC)
        } else "—"

        // Phase 5: live microphone input level.
        if (state.micActive) {
            rowMicLevel.visibility = View.VISIBLE
            val bar = "▮".repeat(state.micLevelPct / 10) +
                "▯".repeat((10 - state.micLevelPct / 10).coerceAtLeast(0))
            txtMicLevel.text = "$bar ${state.micLevelPct}%"
        } else {
            rowMicLevel.visibility = View.GONE
        }

        txtNetwork.text = if (state.networkOk) "OK" else "Poor / offline"
        txtNetwork.setTextColor(
            ContextCompat.getColor(this, if (state.networkOk) R.color.green else R.color.amber)
        )

        val videoAudio = if (state.hasVideoAudio) "Video ON ${state.volumePct}%" else "Video silent"
        val micPart = when {
            !state.micActive -> "Mic OFF"
            state.micMuted -> "Mic MUTED"
            else -> "Mic ON"
        }
        txtAudio.text = "$videoAudio • $micPart"

        btnMuteMic.visibility = if (state.micActive) View.VISIBLE else View.GONE
        btnMuteMic.setText(if (state.micMuted) R.string.unmute_mic else R.string.mute_mic)

        if (state.phase != Phase.ERROR) {
            errorHandled = false
        }

        when (state.phase) {
            Phase.ERROR -> {
                if (!errorHandled) {
                    errorHandled = true
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Streaming failed")
                        .setMessage(
                            (state.errorText ?: "Unknown error.") +
                                "\n\nOpen Advanced Logs (Settings) for the exact stage and reason."
                        )
                        .setPositiveButton("Retry") { _, _ ->
                            StreamService.retry(this)
                        }
                        .setNegativeButton("Close") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
            Phase.STOPPED -> {
                if (!errorHandled) {
                    errorHandled = true
                    mainHandler.postDelayed({ finish() }, 800)
                }
            }
            else -> {
                // Nothing to do — keep showing live status.
            }
        }

        if (!StreamService.isStreaming && state.phase == Phase.IDLE && !errorHandled) {
            finish()
        }
    }

    private fun confirmStop() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_confirm_title)
            .setMessage(R.string.stop_confirm_message)
            .setPositiveButton(R.string.stop) { _, _ ->
                StreamService.stop(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
