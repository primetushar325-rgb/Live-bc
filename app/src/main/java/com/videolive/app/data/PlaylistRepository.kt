package com.videolive.app.data

import android.content.Context
import com.videolive.app.model.LoopMode
import com.videolive.app.model.StopPolicy
import com.videolive.app.model.StreamItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the current playlist (items + playback policy) so a session can be
 * re-used across app restarts. Stored as plain JSON in private prefs — URIs
 * and names only; nothing secret.
 */
object PlaylistRepository {

    private const val PREF = "vl_playlist"

    data class Playlist(
        val items: List<StreamItem>,
        val loopMode: LoopMode,
        val sessionDurationHours: Int,
        val stopPolicy: StopPolicy
    )

    fun save(context: Context, playlist: Playlist) {
        val root = JSONObject()
        val arr = JSONArray()
        playlist.items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("name", item.displayName)
                    .put("size", item.sizeBytes)
                    .put("duration", item.durationMs)
                    .put("w", item.width)
                    .put("h", item.height)
                    .put("fps", item.fps)
                    .put("hasAudio", item.hasAudio)
                    .put("vCodec", item.videoCodec)
                    .put("aCodec", item.audioCodec)
            )
        }
        root.put("items", arr)
        root.put("mode", playlist.loopMode.name)
        root.put("hours", playlist.sessionDurationHours)
        root.put("policy", playlist.stopPolicy.name)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("json", root.toString()).apply()
    }

    fun load(context: Context): Playlist? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("json", null) ?: return null
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StreamItem(
                    uri = o.getString("uri"),
                    displayName = o.getString("name"),
                    sizeBytes = o.optLong("size", 0L),
                    durationMs = o.optLong("duration", 0L),
                    width = o.optInt("w", 0),
                    height = o.optInt("h", 0),
                    fps = o.optInt("fps", 0),
                    hasAudio = o.optBoolean("hasAudio", true),
                    videoCodec = o.optString("vCodec", ""),
                    audioCodec = o.optString("aCodec", "")
                )
            }
            Playlist(
                items = items,
                loopMode = runCatching {
                    LoopMode.valueOf(root.optString("mode", "ONE"))
                }.getOrDefault(LoopMode.ONE),
                sessionDurationHours = root.optInt("hours", 0),
                stopPolicy = runCatching {
                    StopPolicy.valueOf(root.optString("policy", "CONTINUE_UNTIL_STOPPED"))
                }.getOrDefault(StopPolicy.CONTINUE_UNTIL_STOPPED)
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("json").apply()
    }
}
