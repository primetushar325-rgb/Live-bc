package com.videolive.app.ffmpeg

import com.videolive.app.model.StreamConfig
import com.videolive.app.model.VideoInfo
import java.util.Locale

/**
 * Builds the FFmpeg argument list dynamically from the user's selections.
 *
 * Looping strategy (CRITICAL):
 * The input is looped inside ONE FFmpeg session with `-stream_loop -1` paced by `-re`.
 * The RTMP output is opened exactly once and is never closed at a loop boundary,
 * so YouTube sees one continuous stream and output PTS/DTS stay monotonic.
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
        val volume = config.videoVolumePct.coerceIn(0, 100) / 100f

        val args = mutableListOf<String>()

        // Progress reporting (used for health monitoring + live stats).
        args += listOf("-hide_banner", "-stats", "-stats_period", "1")

        // Input 0: the local video, looped forever, paced at real-time speed.
        args += listOf("-re", "-stream_loop", "-1", "-i", ffmpegInput)

        var nextIndex = 1
        var silenceIndex: Int? = null
        var micIndex: Int? = null

        if (!info.hasAudio && micPipePath == null) {
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

        // Video mapping + exact output geometry (letterbox/pillarbox to the chosen aspect).
        args += listOf("-map", "0:v:0")
        args += listOf(
            "-vf",
            "scale=$w:$h:force_original_aspect_ratio=decrease," +
                "pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black," +
                "setsar=1"
        )

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
        when {
            micPipePath != null && info.hasAudio -> {
                // Real mixer: video audio (with user volume) + microphone, via amix.
                val vol = String.format(Locale.US, "%.2f", volume)
                args += listOf(
                    "-filter_complex",
                    "[0:a:0]volume=$vol[a0];" +
                        "[$micIndex:a]aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=mono[m];" +
                        "[a0][m]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]",
                    "-map", "[aout]"
                )
            }
            micPipePath != null -> {
                // Video has no audio: mic becomes the only audio source.
                args += listOf("-map", "$micIndex:a:0")
            }
            info.hasAudio -> {
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
        args += destinationUrl

        return args
    }

    /**
     * Combines server + key (or the advanced full URL) into the final
     * destination. Returns null when anything is malformed. Validates BEFORE
     * the encoder ever starts:
     *  - scheme must be rtmp:// or rtmps://
     *  - no whitespace anywhere (spaces break the connection silently)
     *  - server must have a host part; key must be non-empty
     *  - exactly ONE slash joins server and key (no duplicates)
     */
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
