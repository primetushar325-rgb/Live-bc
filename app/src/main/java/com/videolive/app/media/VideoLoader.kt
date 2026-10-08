package com.videolive.app.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.model.VideoInfo
import java.io.File

data class LoadedVideo(
    val source: InputSource,
    val info: VideoInfo
)

/**
 * Resolves a SAF Uri into something FFmpeg can read and probes it.
 *
 * Primary path: FFmpegKit SAF protocol (zero-copy, the file is NOT duplicated).
 * Fallback: one-time copy into the app cache, only when the SAF descriptor
 * cannot be probed.
 */
object VideoLoader {

    fun load(context: Context, uri: Uri): LoadedVideo? {
        val name = displayName(context, uri) ?: "video"
        val size = statSize(context, uri)

        var source: InputSource? = try {
            val safParam = FFmpegKitConfig.getSafParameterForRead(context, uri)
            if (!safParam.isNullOrBlank()) {
                InputSource.SafSource(safParam, name, size)
            } else null
        } catch (t: Throwable) {
            LogStore.event("SAF registration failed: ${t.javaClass.simpleName}")
            null
        }

        if (source != null) {
            val info = MediaProbe.probe(source)
            if (info != null) {
                LogStore.event("Video loaded via SAF (no copy): $name")
                return LoadedVideo(source, info)
            }
            LogStore.event("SAF input unreadable, falling back to cache copy")
        }

        val copy = copyToCache(context, uri, name) ?: return null
        val fileSource = InputSource.FileSource(copy.absolutePath, name, copy.length())
        val info = MediaProbe.probe(fileSource) ?: return null
        LogStore.event("Video loaded from cache copy: $name")
        return LoadedVideo(fileSource, info)
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (t: Throwable) {
        uri.lastPathSegment
    }

    private fun statSize(context: Context, uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (t: Throwable) {
        -1L
    }

    private fun copyToCache(context: Context, uri: Uri, name: String): File? = try {
        val dir = File(context.cacheDir, "videos").apply { mkdirs() }
        // Clear previous cache copies so we never accumulate huge files.
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }
        val target = File(dir, name)
        if (!target.exists() || target.length() == 0L) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
        }
        target
    } catch (t: Throwable) {
        LogStore.event("Cache copy failed: ${t.javaClass.simpleName}")
        null
    }
}
