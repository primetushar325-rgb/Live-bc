package com.videolive.app.media

import com.videolive.app.ffmpeg.LogStore
import java.io.File
import org.json.JSONObject

/**
 * Bounded cache index for prepared video copies: avoids copying the same
 * source file again when an identical, complete cache copy already exists.
 *
 * Keyed by (content URI + declared size). Entries are validated on lookup
 * (file exists and length matches). At most [MAX_ENTRIES] copies are kept;
 * eviction never touches files the running stream is using (callers only
 * evict when the service is idle).
 */
object CacheIndex {

    private const val MAX_ENTRIES = 3

    private fun indexFile(dir: File) = File(dir, "cache_index.json")

    private fun read(dir: File): MutableMap<String, JSONObject> {
        val map = LinkedHashMap<String, JSONObject>()
        try {
            val f = indexFile(dir)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                root.keys().forEach { k -> map[k] = root.getJSONObject(k) }
            }
        } catch (_: Throwable) {
        }
        return map
    }

    private fun write(dir: File, map: Map<String, JSONObject>) {
        try {
            val root = JSONObject()
            map.forEach { (k, v) -> root.put(k, v) }
            indexFile(dir).writeText(root.toString())
        } catch (_: Throwable) {
        }
    }

    fun keyFor(uri: String, size: Long): String = "$uri|$size"

    /** Returns a verified existing cache file, or null. */
    fun lookup(dir: File, uri: String, size: Long): File? {
        val entry = read(dir)[keyFor(uri, size)] ?: return null
        return try {
            val path = entry.getString("path")
            val expected = entry.getLong("size")
            val f = File(path)
            if (f.exists() && f.length() == expected && expected > 0) f else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Records a new copy and evicts the oldest beyond [MAX_ENTRIES]. */
    fun put(dir: File, uri: String, size: Long, file: File, evictable: List<File>) {
        val map = read(dir)
        map[keyFor(uri, size)] = JSONObject()
            .put("path", file.absolutePath)
            .put("size", file.length())
        // Evict beyond the bound (never the file we just stored).
        while (map.size > MAX_ENTRIES) {
            val oldestKey = map.keys.first()
            val oldest = map.remove(oldestKey)
            val path = oldest?.optString("path")
            if (path != null) {
                val f = File(path)
                if (f.absolutePath != file.absolutePath && f !in evictable) {
                    f.delete()
                    LogStore.event("Cache eviction: removed old copy (${f.length()} bytes)")
                }
            }
        }
        write(dir, map)
    }
}
