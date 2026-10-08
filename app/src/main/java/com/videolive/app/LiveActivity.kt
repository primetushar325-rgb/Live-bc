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
                txtConnection.text = "Stable ✓"
                txtConnection.setTextColor(ContextCompat.getColor(this, R.color.green))
            }
            Phase.CONNECTING, Phase.STARTING -> {
                txtConnection.text = "Connecting…"
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

        val videoAudio = if (state.hasVideoAudio) "Video ON ${state.volumePct}%" else "Video silent"
        val micPart = when {
            !state.micActive -> "Mic OFF"
            state.micMuted -> "Mic MUTED"
            else -> "Mic ON"
        }
        txtAudio.text = "$videoAudio • $micPart"

        btnMuteMic.visibility = if (state.micActive) View.VISIBLE else View.GONE
        btnMuteMic.setText(if (state.micMuted) R.string.unmute_mic else R.string.mute_mic)

        when (state.phase) {
            Phase.ERROR -> {
                if (!errorHandled) {
                    errorHandled = true
                    mainHandler.postDelayed({ finish() }, 3500)
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
