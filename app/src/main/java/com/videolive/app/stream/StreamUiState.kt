package com.videolive.app.stream

enum class Phase {
    IDLE, STARTING, CONNECTING, STREAMING, RECONNECTING, STOPPING, ERROR, STOPPED
}

data class StreamUiState(
    val phase: Phase = Phase.IDLE,
    val statusText: String = "",
    val errorText: String? = null,
    val elapsedMs: Long = 0,
    val videoName: String = "",
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    val fps: Int = 0,
    val configuredBitrateKbps: Int = 0,
    val liveFps: Float = 0f,
    val liveBitrateKbps: Int = 0,
    val speed: Double = 0.0,
    val attempt: Int = 0,
    val micActive: Boolean = false,
    val micMuted: Boolean = false,
    val hasVideoAudio: Boolean = true,
    val volumePct: Int = 100
)
