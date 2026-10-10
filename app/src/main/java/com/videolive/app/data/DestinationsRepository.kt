package com.videolive.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Up to three named RTMP/RTMPS destinations. Stream keys are stored ONLY in
 * [SecurePrefs] (encrypted); this repository holds names/URLs/active flag.
 *
 * Simultaneous multi-destination publishing (simulcast) is NOT attempted on
 * the phone: one encoder session per stream keeps the proven engine intact.
 * The manager lets the user prepare several destinations, test each one, and
 * pick which single destination the next stream uses.
 */
object DestinationsRepository {

    private const val PREF = "vl_destinations"
    const val MAX = 3

    data class Destination(
        val id: String,
        val name: String,
        val serverUrl: String,
        val fullUrlMode: Boolean,
        val fullUrl: String,
        val enabled: Boolean
    )

    fun load(context: Context): List<Destination> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Destination(
                    id = o.getString("id"),
                    name = o.optString("name", "Destination ${i + 1}"),
                    serverUrl = o.optString("server", ""),
                    fullUrlMode = o.optBoolean("fullUrlMode", false),
                    fullUrl = o.optString("fullUrl", ""),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun save(context: Context, list: List<Destination>) {
        val arr = JSONArray()
        list.take(MAX).forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("server", d.serverUrl)
                    .put("fullUrlMode", d.fullUrlMode)
                    .put("fullUrl", d.fullUrl)
                    .put("enabled", d.enabled)
            )
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("json", arr.toString()).apply()
    }

    fun activeId(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("active", null)

    fun setActive(context: Context, id: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("active", id).apply()
    }

    fun keyFor(context: Context, id: String): String =
        SecurePrefs.getSecret(context, "dest_key_$id").orEmpty()

    fun saveKey(context: Context, id: String, key: String) {
        SecurePrefs.saveSecret(context, "dest_key_$id", key)
    }
}
