package com.videolive.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.arthenica.ffmpegkit.FFprobeKit
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.model.VideoInfo

/**
 * Media inspection.
 *
 * [probeWithMmr] uses Android's own MediaMetadataRetriever directly with the
 * content:// Uri — it works for anything the platform can play and needs no
 * FFmpeg, so the UI can show metadata/thumbnail immediately.
 *
 * [probe] uses real FFprobe for the exact stream layout FFmpeg will see
 * (video stream, audio stream, fps, codecs).
 */
object MediaProbe {

    /**
     * Fast platform metadata straight from the Uri.
     * When FFprobe has not confirmed the audio layout yet, [VideoInfo.hasAudio]
     * is reported as true and the streaming pipeline uses an OPTIONAL audio map
     * (`-map 0:a:0?`), which is safe whether or not the file has audio.
     */
    fun probeWithMmr(context: Context, uri: Uri): VideoInfo? {
        var mmr: MediaMetadataRetriever? = null
        return try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) {
                LogStore.event("Platform metadata: no decodable video in URI")
                return null
            }
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            var fps = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                fps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.let { Math.round(it).toInt() } ?: 0
            }
            VideoInfo(
                durationMs = durationMs,
                width = width,
                height = height,
                fps = fps,
                hasAudio = true,
                sizeBytes = -1L,
                videoCodec = "",
                audioCodec = ""
            )
        } catch (t: Throwable) {
            LogStore.event("Platform metadata failed: ${t.javaClass.simpleName}")
            null
        } finally {
            try {
                mmr?.release()
            } catch (_: Throwable) {
            }
        }
    }

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
