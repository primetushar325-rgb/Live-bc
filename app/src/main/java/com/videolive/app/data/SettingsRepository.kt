package com.videolive.app.data

import android.content.Context
import com.videolive.app.model.BitrateMode
import com.videolive.app.model.EncoderPref
import com.videolive.app.model.FrameMode
import com.videolive.app.model.LoopMode
import com.videolive.app.model.Orientation
import com.videolive.app.model.Quality

/**
 * Non-secret UI settings, restored every time the app opens.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vl_settings", Context.MODE_PRIVATE)

    var videoUri: String?
        get() = prefs.getString("video_uri", null)
        set(value) = prefs.edit().putString("video_uri", value).apply()

    var serverUrl: String
        get() = prefs.getString("server_url", "rtmps://a.rtmp.youtube.com/live2") ?: "rtmps://a.rtmp.youtube.com/live2"
        set(value) = prefs.edit().putString("server_url", value).apply()

    var fullUrlMode: Boolean
        get() = prefs.getBoolean("full_url_mode", false)
        set(value) = prefs.edit().putBoolean("full_url_mode", value).apply()

    var fullUrl: String
        get() = prefs.getString("full_url", "") ?: ""
        set(value) = prefs.edit().putString("full_url", value).apply()

    var orientation: Orientation
        get() = if (prefs.getString("orientation", "H") == "V") Orientation.VERTICAL else Orientation.HORIZONTAL
        set(value) = prefs.edit().putString("orientation", if (value == Orientation.VERTICAL) "V" else "H").apply()

    var quality: Quality
        get() = Quality.fromLabel(prefs.getString("quality", Quality.Q720.label) ?: Quality.Q720.label)
        set(value) = prefs.edit().putString("quality", value.label).apply()

    var fps: Int
        get() = prefs.getInt("fps", 30)
        set(value) = prefs.edit().putInt("fps", value).apply()

    var bitrateMode: BitrateMode
        get() = if (prefs.getString("bitrate_mode", "AUTO") == "MANUAL") BitrateMode.MANUAL else BitrateMode.AUTO
        set(value) = prefs.edit().putString("bitrate_mode", value.name).apply()

    var manualBitrateKbps: Int
        get() = prefs.getInt("manual_bitrate", 4500)
        set(value) = prefs.edit().putInt("manual_bitrate", value).apply()

    var videoVolumePct: Int
        get() = prefs.getInt("volume", 100)
        set(value) = prefs.edit().putInt("volume", value).apply()

    var micOn: Boolean
        get() = prefs.getBoolean("mic_on", false)
        set(value) = prefs.edit().putBoolean("mic_on", value).apply()

    var loopMode: LoopMode
        get() = LoopMode.ONE
        set(value) = prefs.edit().putString("loop_mode", value.name).apply()

    // ---- Phase 4: framing (applied to the encoder output at stream start) ----
    var frameMode: FrameMode
        get() = if (prefs.getString("frame_mode", "FIT") == "FILL") FrameMode.FILL else FrameMode.FIT
        set(value) = prefs.edit().putString("frame_mode", value.name).apply()

    var zoomPct: Int
        get() = prefs.getInt("zoom_pct", 100)
        set(value) = prefs.edit().putInt("zoom_pct", value).apply()

    var panXPct: Int
        get() = prefs.getInt("pan_x_pct", 0)
        set(value) = prefs.edit().putInt("pan_x_pct", value).apply()

    var panYPct: Int
        get() = prefs.getInt("pan_y_pct", 0)
        set(value) = prefs.edit().putInt("pan_y_pct", value).apply()

    // ---- Phase 5: independent microphone gain ----
    var micVolumePct: Int
        get() = prefs.getInt("mic_volume", 100)
        set(value) = prefs.edit().putInt("mic_volume", value).apply()

    // ---- Phase 3: playlist experimental gate ----
    var playlistEnabled: Boolean
        get() = prefs.getBoolean("playlist_enabled", true)
        set(value) = prefs.edit().putBoolean("playlist_enabled", value).apply()

    // ---- Phase 1 (final): encoder preference ----
    var encoderPref: EncoderPref
        get() = runCatching {
            EncoderPref.valueOf(prefs.getString("encoder_pref", "AUTO") ?: "AUTO")
        }.getOrDefault(EncoderPref.AUTO)
        set(value) = prefs.edit().putString("encoder_pref", value.name).apply()

    // ---- Phase 7: thermal guard thresholds (°C, battery sensor) ----
    var thermalWarnC: Int
        get() = prefs.getInt("thermal_warn_c", 41)
        set(value) = prefs.edit().putInt("thermal_warn_c", value).apply()

    var thermalCritC: Int
        get() = prefs.getInt("thermal_crit_c", 45)
        set(value) = prefs.edit().putInt("thermal_crit_c", value).apply()
}
