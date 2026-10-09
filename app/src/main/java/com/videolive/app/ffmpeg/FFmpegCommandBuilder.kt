package com.videolive.app.ffmpeg

import com.videolive.app.model.FrameMode
import com.videolive.app.model.LoopMode
import com.videolive.app.model.StreamConfig
import com.videolive.app.model.VideoInfo
import java.io.File
import java.util.Locale

/**
 * Builds the FFmpeg argument list dynamically from the user's selections.
 *
 * Looping strategy (CRITICAL):
 * The input is looped inside ONE FFmpeg session with `-stream_loop -1` paced
 * by `-re`. The RTMP output is opened exactly once and is never closed at a
 * loop boundary, so YouTube sees one continuous stream and output PTS/DTS
 * stay monotonic.
 *
 * Playlists (Phase 3): multiple prepared cache files are played through the
 * concat demuxer as ONE input, still ONE session / ONE RTMP output. Loop All
 * adds `-stream_loop -1` around the whole list. Sequential/Shuffle play one
 * pass and finish. This requires codec-compatible items (same video codec,
 * resolution and audio layout) — enforced by the caller.
 */
object FFmpegCommandBuilder {

    fun build(
        config: StreamConfig,
        info: VideoInfo,
        ffmpegInput: String,
        destinationUrl: String,
        micPipePath: String?
    ): List<String> {
        val (w, h) = config.outputSize()
        val fps = config.fps
        val bitrateK = config.targetBitrateKbps()

        val args = mutableListOf<String>()

        // Progress reporting (used for health monitoring + live stats).
        args += listOf("-hide_banner", "-stats", "-stats_period", "1")

        // Input 0: the local video, looped forever, paced at real-time speed.
        args += listOf("-re", "-stream_loop", "-1", "-i", ffmpegInput)

        appendInputsAndOutput(args, config, info.hasAudio, w, h, fps, bitrateK, micPipePath)
        args += destinationUrl
        return args
    }

    /**
     * Playlist variant: ONE concat-demuxer input over the prepared cache files.
     * [concatListFile] must contain `file '<path>'` lines in play order.
     */
    fun buildPlaylist(
        config: StreamConfig,
        hasAudio: Boolean,
        concatListFile: File,
        destinationUrl: String,
        micPipePath: String?
    ): List<String> {
        val (w, h) = config.outputSize()
        val fps = config.fps
        val bitrateK = config.targetBitrateKbps()

        val args = mutableListOf<String>()
        args += listOf("-hide_banner", "-stats", "-stats_period", "1")

        // Loop All wraps the whole playlist; Sequential/Shuffle play one pass.
        args += listOf("-re")
        if (config.loopMode == LoopMode.ALL) args += listOf("-stream_loop", "-1")
        args += listOf("-f", "concat", "-safe", "0", "-i", concatListFile.absolutePath)

        appendInputsAndOutput(args, config, hasAudio, w, h, fps, bitrateK, micPipePath)
        args += destinationUrl
        return args
    }

    // Shared tail: extra inputs (silence/mic), video filter chain, encoders, muxer.
    private fun appendInputsAndOutput(
        args: MutableList<String>,
        config: StreamConfig,
        hasAudio: Boolean,
        w: Int,
        h: Int,
        fps: Int,
        bitrateK: Int,
        micPipePath: String?
    ) {
        var nextIndex = 1
        var silenceIndex: Int? = null
        var micIndex: Int? = null

        if (!hasAudio && micPipePath == null) {
            // YouTube requires an audio track — generate silence.
            args += listOf(
                "-f", "lavfi", "-i",
                "anullsrc=channel_layout=stereo:sample_rate=44100"
            )
            silenceIndex = nextIndex++
        }

        if (micPipePath != null) {
            // Live microphone PCM written by MicMixer into an FFmpegKit named pipe.
            args += listOf("-f", "s16le", "-ar", "44100", "-ac", "1", "-i", micPipePath)
            micIndex = nextIndex++
        }

        // Video mapping + framing (fit/fill + zoom/pan, applied at start only).
        args += listOf("-map", "0:v:0")
        args += listOf("-vf", videoFilter(config, w, h))

        // H.264 encode tuned for low-latency live streaming (CBR).
        args += listOf(
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-tune", "zerolatency",
            "-pix_fmt", "yuv420p",
            "-r", fps.toString(),
            "-g", (fps * 2).toString(),
            "-keyint_min", fps.toString(),
            "-sc_threshold", "0",
            "-b:v", "${bitrateK}k",
            "-maxrate", "${bitrateK}k",
            "-bufsize", "${bitrateK * 2}k"
        )

        // Audio routing.
        val volume = config.videoVolumePct.coerceIn(0, 100) / 100f
        val micVolume = config.micVolumePct.coerceIn(0, 100) / 100f
        when {
            micPipePath != null && hasAudio -> {
                // Real mixer: video audio (user volume) + microphone (own gain),
                // via amix. normalize=0 keeps the video audio at its set level.
                val vol = String.format(Locale.US, "%.2f", volume)
                val mvol = String.format(Locale.US, "%.2f", micVolume)
                args += listOf(
                    "-filter_complex",
                    "[0:a:0]volume=$vol[a0];" +
                        "[$micIndex:a]aformat=sample_fmts=s16:sample_rates=44100:" +
                        "channel_layouts=mono,volume=$mvol[m];" +
                        "[a0][m]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]",
                    "-map", "[aout]"
                )
            }
            micPipePath != null -> {
                // Video has no audio: mic becomes the only audio source.
                val mvol = String.format(Locale.US, "%.2f", micVolume)
                args += listOf(
                    "-filter_complex",
                    "[$micIndex:a]volume=$mvol[m]",
                    "-map", "[m]"
                )
            }
            hasAudio -> {
                args += listOf("-map", "0:a:0?")
                if (config.videoVolumePct < 100) {
                    args += listOf("-af", String.format(Locale.US, "volume=%.2f", volume))
                }
            }
            silenceIndex != null -> {
                args += listOf("-map", "$silenceIndex:a:0")
            }
        }

        // AAC audio, 44.1 kHz stereo — required by FLV/YouTube.
        args += listOf("-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2")

        // FLV output over RTMP/RTMPS. This URL is the ONLY output; it stays open
        // across every loop of the video.
        args += listOf("-f", "flv", "-flvflags", "no_duration_filesize")
    }

    /**
     * Framing filter. FIT letterboxes, FILL crops; zoom (100-300%) and pan
     * (-100..100 on both axes) are applied AFTER framing, on the composed
     * canvas, so what the preview describes is exactly what the encoder sends.
     * Defaults reproduce the proven fit+pad chain exactly.
     */
    fun videoFilter(config: StreamConfig, w: Int, h: Int): String {
        val base = when (config.frameMode) {
            FrameMode.FIT ->
                "scale=$w:$h:force_original_aspect_ratio=decrease," +
                    "pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black"
            FrameMode.FILL ->
                "scale=$w:$h:force_original_aspect_ratio=increase," +
                    "crop=$w:$h"
        }
        // CFR normalization: phone recordings are frequently variable-frame-
        // rate. `-re` paces by timestamps, so VFR input produces periodic
        // pacing jitter at the muxer (visible as ~1 s freezes on the server
        // while the local file plays fine). A constant-rate `fps` filter is
        // the standard fix and makes output cadence exact.
        val cfr = ",fps=${config.fps}"

        val zoom = config.zoomPct.coerceIn(100, 300) / 100f
        val panX = config.panXPct.coerceIn(-100, 100) / 100f
        val panY = config.panYPct.coerceIn(-100, 100) / 100f
        if (zoom <= 1.001f && panX == 0f && panY == 0f) return "$base$cfr,setsar=1"

        val z = String.format(Locale.US, "%.2f", zoom)
        // Zoom in around the (optionally panned) centre of the framed canvas.
        val px = String.format(Locale.US, "%.3f", panX)
        val py = String.format(Locale.US, "%.3f", panY)
        return base + "," +
            "scale=iw*$z:ih*$z," +
            "crop=$w:$h:(iw-$w)/2+$px*(iw-$w)/2:(ih-$h)/2+$py*(ih-$h)/2" +
            cfr + ",setsar=1"
    }

    /** Combines server + key (or the advanced full URL) into the final
     * destination. Returns null when anything is malformed. */
    fun buildDestinationUrl(
        fullUrlMode: Boolean,
        serverUrl: String,
        fullUrl: String,
        streamKey: String
    ): String? {
        return if (fullUrlMode) {
            val url = fullUrl.trim()
            if (isValidRtmpUrl(url) && url.none { it.isWhitespace() } && hasHostAndPath(url)) url
            else null
        } else {
            val server = serverUrl.trim().trimEnd('/')
            val key = streamKey.trim()
            when {
                !isValidRtmpUrl(server) -> null
                server.any { it.isWhitespace() } -> null
                !hasHost(server) -> null
                key.isEmpty() -> null
                key.any { it.isWhitespace() } -> null
                else -> "$server/$key"
            }
        }
    }

    fun isValidRtmpUrl(url: String): Boolean =
        url.startsWith("rtmp://") || url.startsWith("rtmps://")

    /** rtmp(s)://<host>[:port] — host part must be present. */
    private fun hasHost(url: String): Boolean {
        val rest = url.substringAfter("://")
        val hostPart = rest.substringBefore('/').substringBefore('?')
        return hostPart.isNotEmpty()
    }

    /** Full-URL mode additionally requires a path after the host. */
    private fun hasHostAndPath(url: String): Boolean {
        val rest = url.substringAfter("://")
        val slash = rest.indexOf('/')
        return slash > 0 && slash < rest.length - 1
    }
}
