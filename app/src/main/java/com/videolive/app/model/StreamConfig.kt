package com.videolive.app.model

import java.io.Serializable

enum class Orientation { VERTICAL, HORIZONTAL }

enum class LoopMode { ONE, ALL }

enum class BitrateMode { AUTO, MANUAL }

/**
 * Output quality presets. The pair is the landscape (16:9) resolution;
 * vertical streams swap width and height.
 *
 * Auto bitrates are conservative CBR targets suitable for YouTube Live.
 * 4K is intentionally NOT offered: software H.264 encoding on phones
 * cannot reliably sustain a real 4K live encode.
 */
enum class Quality(val label: String, val width: Int, val height: Int, val autoBitrateKbps: Int) {
    Q360("360p", 640, 360, 1000),
    Q480("480p", 854, 480, 1800),
    Q720("720p", 1280, 720, 3500),
    Q1080("1080p", 1920, 1080, 5500);

    fun outputSize(orientation: Orientation): Pair<Int, Int> =
        if (orientation == Orientation.VERTICAL) height to width else width to height

    companion object {
        fun fromLabel(label: String): Quality = values().firstOrNull { it.label == label } ?: Q720
    }
}

/**
 * Everything needed to start a stream except the stream key.
 * The key is read from encrypted storage by the service and is never
 * passed through intents, logs or notifications.
 */
data class StreamConfig(
    val videoUri: String,
    val videoName: String,
    val hasAudio: Boolean,
    val orientation: Orientation,
    val quality: Quality,
    val fps: Int,
    val bitrateMode: BitrateMode,
    val manualBitrateKbps: Int,
    val videoVolumePct: Int,
    val micOn: Boolean,
    val loopMode: LoopMode,
    val fullUrlMode: Boolean,
    val serverUrl: String,
    val fullUrl: String
) : Serializable {

    fun targetBitrateKbps(): Int = when (bitrateMode) {
        BitrateMode.MANUAL -> manualBitrateKbps.coerceIn(200, 20_000)
        BitrateMode.AUTO -> quality.autoBitrateKbps
    }

    fun outputSize(): Pair<Int, Int> = quality.outputSize(orientation)
}

/** Probed information about the selected local video. */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val sizeBytes: Long,
    val videoCodec: String,
    val audioCodec: String
)
