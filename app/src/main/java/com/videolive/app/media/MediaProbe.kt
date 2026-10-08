package com.videolive.app.media

import com.arthenica.ffmpegkit.FFprobeKit
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.model.VideoInfo

/**
 * Real FFprobe based media inspection. Works with plain paths and with
 * FFmpegKit SAF protocol urls (saf:<id>.<ext>).
 */
object MediaProbe {

    fun probe(source: InputSource): VideoInfo? {
        return try {
        val session = FFprobeKit.getMediaInformation(source.ffmpegInput)
        val info = session.mediaInformation
        if (info == null) {
            LogStore.event("Probe failed: no media information for ${source.displayName}")
            null
        } else {
            var width = 0
            var height = 0
            var fps = 0
            var hasAudio = false
            var videoCodec = ""
            var audioCodec = ""

            for (stream in info.streams.orEmpty()) {
                when (stream.type) {
                    "video" -> {
                        if (width == 0) {
                            width = stream.width?.toInt() ?: 0
                            height = stream.height?.toInt() ?: 0
                            videoCodec = stream.codec ?: ""
                            fps = parseFps(stream.realFrameRate)
                            if (fps == 0) fps = parseFps(stream.averageFrameRate)
                        }
                    }
                    "audio" -> {
                        hasAudio = true
                        if (audioCodec.isEmpty()) audioCodec = stream.codec ?: ""
                    }
                }
            }

            if (width == 0 || height == 0) {
                LogStore.event("Probe: no decodable video stream in ${source.displayName}")
                return null
            }

            val durationMs = (info.duration?.toDoubleOrNull() ?: 0.0).let { (it * 1000).toLong() }
            val sizeBytes = info.size?.toLongOrNull() ?: source.sizeBytes

            VideoInfo(
                durationMs = durationMs,
                width = width,
                height = height,
                fps = fps,
                hasAudio = hasAudio,
                sizeBytes = sizeBytes,
                videoCodec = videoCodec,
                audioCodec = audioCodec
            )
        }
        } catch (t: Throwable) {
            LogStore.event("Probe error: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun parseFps(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val parts = raw.split("/")
        return try {
            if (parts.size == 2) {
                val num = parts[0].toDouble()
                val den = parts[1].toDouble()
                if (den > 0 && num > 0) Math.round(num / den).toInt() else 0
            } else {
                Math.round(raw.toDouble()).toInt()
            }
        } catch (t: Throwable) {
            0
        }
    }
}
