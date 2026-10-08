package com.videolive.app.util

import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.os.Build

/**
 * Honest capability detection. We never advertise features the device cannot deliver.
 */
object DeviceCaps {

    val cpuCores: Int = Runtime.getRuntime().availableProcessors()

    fun hasHardwareH264Encoder(): Boolean = try {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        list.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * The FFmpeg build ships the libx264 software encoder, which is what actually runs.
     * To keep long sessions stable on weaker devices we cap very demanding combinations.
     * Returns a user visible note when something was adjusted, or null.
     */
    fun adjustFpsIfNeeded(qualityLabel: String, requestedFps: Int): Pair<Int, String?> {
        val demanding = qualityLabel == "1080p" && requestedFps > 30 && cpuCores < 8
        val mid = qualityLabel == "720p" && requestedFps > 30 && cpuCores < 4
        return if (demanding || mid) {
            30 to "Device has $cpuCores CPU cores — FPS capped to 30 for ${qualityLabel} stability."
        } else {
            requestedFps to null
        }
    }

    fun describe(): String =
        "FFmpeg engine (libx264 software H.264 + native AAC) • ${cpuCores} CPU cores • " +
            "hardware AVC encoder present: ${if (hasHardwareH264Encoder()) "yes" else "no"} • " +
            "Android ${Build.VERSION.RELEASE}"
}
