package com.videolive.app.stream

/**
 * Explicit pipeline states. The Connection indicator only advances on real
 * evidence: ENCODING when the engine reports its output is configured,
 * CONNECTED when the first encoded frame reaches the muxer (i.e. the RTMP
 * output was accepted), STREAMING once packets keep flowing.
 */
enum class Phase {
    IDLE,
    PREPARING,
    ENCODING,
    CONNECTING,
    CONNECTED,
    STREAMING,
    RECONNECTING,
    ERROR,
    STOPPING,
    STOPPED
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
