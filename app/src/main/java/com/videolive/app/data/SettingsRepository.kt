package com.videolive.app.data

import android.content.Context
import com.videolive.app.model.BitrateMode
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
}
