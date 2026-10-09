package com.videolive.app.data

import android.content.Context
import com.videolive.app.stream.StreamService
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent Stream Projects.
 *
 * A project is a named, durable snapshot of everything a live session needs:
 * the full settings state (video, destination, quality, fps, bitrate, volumes,
 * mic, framing, encoder), the playlist (if any) and a reference to an
 * encrypted per-project stream key (the key itself lives ONLY in SecurePrefs,
 * never in this file, never in logs).
 *
 * Storage: private JSON file `projects.json`. This matches the app's existing
 * file/prefs repository architecture — no new database dependency, nothing the
 * streaming engine doesn't already trust. Projects survive app restarts and
 * are independent of any Activity state.
 */
object ProjectStore {

    private const val FILE = "projects.json"
    private const val SETTINGS_PREF = "vl_settings"
    private const val PLAYLIST_PREF = "vl_playlist"

    data class Project(
        val id: String,
        val name: String,
        val createdAt: Long,
        val modifiedAt: Long,
        val lastStatus: String,
        val hasKey: Boolean,
        val videoName: String
    )

    // ---- file io -----------------------------------------------------------

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, FILE)

    private fun readRoot(ctx: Context): JSONObject = try {
        val raw = file(ctx).readText()
        JSONObject(raw)
    } catch (t: Throwable) {
        JSONObject().put("projects", JSONArray())
    }

    private fun writeRoot(ctx: Context, root: JSONObject) {
        runCatching { file(ctx).writeText(root.toString()) }
    }

    private fun findProject(root: JSONObject, id: String): JSONObject? {
        val arr = root.optJSONArray("projects") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o
        }
        return null
    }

    // ---- public api ----------------------------------------------------------

    fun list(ctx: Context): List<Project> {
        val root = readRoot(ctx)
        val arr = root.optJSONArray("projects") ?: JSONArray()
        val out = mutableListOf<Project>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            out += Project(
                id = id,
                name = o.optString("name", "Stream"),
                createdAt = o.optLong("created", 0L),
                modifiedAt = o.optLong("modified", 0L),
                lastStatus = o.optString("lastStatus", ""),
                hasKey = SecurePrefs.getSecret(ctx, keyId(id)) != null,
                videoName = o.optJSONObject("settings")?.optString("video_uri", "")
                    ?.let { videoLabel(ctx, o, it) } ?: "No video selected"
            )
        }
        return out
    }

    private fun videoLabel(ctx: Context, project: JSONObject, videoUri: String): String {
        if (videoUri.isEmpty()) {
            val playlistJson = project.optString("playlist", "")
            if (playlistJson.isNotEmpty()) {
                val count = try {
                    JSONObject(playlistJson).optJSONArray("items")?.length() ?: 0
                } catch (t: Throwable) { 0 }
                if (count >= 2) return "Playlist ($count videos)"
            }
            return "No video selected"
        }
        return try {
            val uri = android.net.Uri.parse(videoUri)
            uri.lastPathSegment?.substringAfterLast('/') ?: videoUri
        } catch (t: Throwable) { videoUri }
    }

    /** Creates a fresh project (defaults; the user configures it on open). */
    fun create(ctx: Context, name: String): Project {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val o = JSONObject()
            .put("id", id)
            .put("name", name.ifBlank { "New Stream" })
            .put("created", now)
            .put("modified", now)
            .put("lastStatus", "")
            .put("settings", JSONObject())
        val root = readRoot(ctx)
        root.optJSONArray("projects")?.put(o)
            ?: root.put("projects", JSONArray().put(o))
        writeRoot(ctx, root)
        return Project(id, o.getString("name"), now, now, "", false, "No video selected")
    }

    /** Snapshots the CURRENT global state (settings + playlist + key) into
     * the project. Called when a project session starts, so the exact working
     * configuration is what persists. Never touches the stream itself. */
    fun snapshotCurrentInto(ctx: Context, id: String, status: String, streamKey: String?) {
        val root = readRoot(ctx)
        val o = findProject(root, id) ?: return
        val settings = JSONObject()
        ctx.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (v != null) settings.put(k, v)
        }
        o.put("settings", settings)
        val playlist = ctx.getSharedPreferences(PLAYLIST_PREF, Context.MODE_PRIVATE)
            .getString("json", null)
        o.put("playlist", playlist ?: "")
        o.put("lastStatus", status)
        o.put("modified", System.currentTimeMillis())
        writeRoot(ctx, root)
        if (!streamKey.isNullOrBlank()) {
            SecurePrefs.saveSecret(ctx, keyId(id), streamKey)
        }
    }

    /** Loads a project's snapshot into the global app state so MainActivity
     * renders and streams exactly what the project saved. */
    fun applyToGlobal(ctx: Context, id: String): Project? {
        val root = readRoot(ctx)
        val o = findProject(root, id) ?: return null

        // Settings: full replace so nothing from another project leaks in.
        val edit = ctx.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE).edit()
        edit.clear()
        val settings = o.optJSONObject("settings") ?: JSONObject()
        settings.keys().forEach { k ->
            when (val v = settings.opt(k)) {
                is String -> edit.putString(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is Double -> edit.putFloat(k, v.toFloat())
                null -> {}
                else -> edit.putString(k, v.toString())
            }
        }
        edit.apply()

        // Playlist: project's own list (or none).
        ctx.getSharedPreferences(PLAYLIST_PREF, Context.MODE_PRIVATE)
            .edit().putString("json", o.optString("playlist", "").ifEmpty { "" }).apply()
        if (o.optString("playlist", "").isEmpty()) {
            ctx.getSharedPreferences(PLAYLIST_PREF, Context.MODE_PRIVATE)
                .edit().remove("json").apply()
        }

        // Stream key: per-project encrypted secret becomes the active key.
        // Projects without a saved key clear the field rather than showing a
        // previous project's secret.
        val key = SecurePrefs.getSecret(ctx, keyId(id)).orEmpty()
        SecurePrefs.saveStreamKey(ctx, key)

        return Project(
            id = id,
            name = o.optString("name", "Stream"),
            createdAt = o.optLong("created", 0L),
            modifiedAt = o.optLong("modified", 0L),
            lastStatus = o.optString("lastStatus", ""),
            hasKey = key.isNotEmpty(),
            videoName = videoLabel(ctx, o, settings.optString("video_uri", ""))
        )
    }

    fun rename(ctx: Context, id: String, newName: String) {
        val root = readRoot(ctx)
        val o = findProject(root, id) ?: return
        o.put("name", newName.trim().ifBlank { o.optString("name", "Stream") })
        o.put("modified", System.currentTimeMillis())
        writeRoot(ctx, root)
    }

    fun duplicate(ctx: Context, id: String): Project? {
        val root = readRoot(ctx)
        val src = findProject(root, id) ?: return null
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val copy = JSONObject(src.toString())
            .put("id", newId)
            .put("name", src.optString("name", "Stream") + " (copy)")
            .put("created", now)
            .put("modified", now)
            .put("lastStatus", "")
        root.optJSONArray("projects")?.put(copy)
        writeRoot(ctx, root)
        // Copy the encrypted key for the duplicate (encrypted at rest, as always).
        SecurePrefs.getSecret(ctx, keyId(id))?.let { SecurePrefs.saveSecret(ctx, keyId(newId), it) }
        return list(ctx).firstOrNull { it.id == newId }
    }

    fun delete(ctx: Context, id: String) {
        val root = readRoot(ctx)
        val arr = root.optJSONArray("projects") ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        root.put("projects", kept)
        writeRoot(ctx, root)
        runCatching {
            ctx.getSharedPreferences("vl_secure_prefs", Context.MODE_PRIVATE)
                .edit().remove(keyId(id)).apply()
        }
    }

    fun setStatus(ctx: Context, id: String, status: String) {
        val root = readRoot(ctx)
        val o = findProject(root, id) ?: return
        o.put("lastStatus", status)
        o.put("modified", System.currentTimeMillis())
        writeRoot(ctx, root)
    }

    /** Status string for a project, aware of the live session. */
    fun displayStatus(ctx: Context, p: Project, activeProjectId: String?): String = when {
        StreamService.isStreaming && p.id == activeProjectId -> "LIVE now"
        p.lastStatus.isEmpty() -> "Never streamed"
        else -> "Last session: ${p.lastStatus}"
    }

    private fun keyId(id: String) = "proj_key_$id"
}
