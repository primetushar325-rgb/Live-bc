package com.videolive.app.media

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.model.VideoInfo
import java.io.File

/**
 * Specific, user-facing failure reasons for the video input pipeline.
 */
class VideoInputException(
    val kind: Kind,
    message: String
) : Exception(message) {
    enum class Kind { PERMISSION, UNREADABLE, UNDECODABLE }
}

data class LoadedVideo(
    val source: InputSource,
    val info: VideoInfo
)

/**
 * Turns an Android content:// Uri (ACTION_OPEN_DOCUMENT) into a readable input
 * for FFmpeg. The Uri is NEVER treated as a filesystem path.
 *
 * Pipeline:
 *  1. [inspect] — open the Uri via ContentResolver, read name/size/MIME, pull
 *     platform metadata with MediaMetadataRetriever (works directly on the Uri).
 *     This is fast and powers the immediate UI (thumbnail, name, duration, ...).
 *  2. [prepareForFFmpeg] — build the FFmpeg input bridge:
 *       a) Zero-copy: FFmpegKit SAF protocol, but only accepted after FFprobe
 *          verified it can actually decode through it.
 *       b) Reliable fallback: buffered streaming copy from
 *          ContentResolver.openInputStream(uri) into cacheDir (large-file safe,
 *          never loads the file into RAM). The copy is temporary and is deleted
 *          after streaming stops or when a new video is chosen.
 */
object VideoLoader {

    data class Inspection(
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String?,
        val platformInfo: VideoInfo?
    )

    /**
     * Fast validation + platform metadata. Throws [VideoInputException] with a
     * precise message when the Uri cannot be used.
     */
    fun inspect(context: Context, uri: Uri): Inspection {
        val check = UriValidator.check(context, uri)
        LogStore.event("Selected URI: $uri")
        LogStore.event("MIME type: ${check.mimeType ?: "(provider returned none)"}")
        LogStore.event("Readable: ${check.openable}")

        if (check.permissionDenied) {
            throw VideoInputException(
                VideoInputException.Kind.PERMISSION,
                "Permission denied. Please select the video again."
            )
        }
        if (!check.openable) {
            throw VideoInputException(
                VideoInputException.Kind.UNREADABLE,
                "Unable to read this video."
            )
        }

        val displayName = check.displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "video"

        val platformInfo = MediaProbe.probeWithMmr(context, uri)
        if (platformInfo != null) {
            LogStore.event(
                "Metadata: ${platformInfo.width}x${platformInfo.height}, " +
                    "${platformInfo.durationMs} ms, fps=${platformInfo.fps}"
            )
        }

        val mimeType = check.mimeType
        if (platformInfo == null && mimeType != null && !looksLikeVideoMime(mimeType)) {
            throw VideoInputException(
                VideoInputException.Kind.UNDECODABLE,
                "This video cannot be read or decoded."
            )
        }
        if (platformInfo == null) {
            throw VideoInputException(
                VideoInputException.Kind.UNDECODABLE,
                "This video cannot be read or decoded."
            )
        }

        return Inspection(displayName, check.sizeBytes, mimeType, platformInfo)
    }

    /**
     * Builds and verifies the FFmpeg input for the Uri. Uses zero-copy SAF when
     * it survives FFprobe verification; otherwise the temporary cache bridge.
     */
    fun prepareForFFmpeg(
        context: Context,
        uri: Uri,
        inspection: Inspection
    ): LoadedVideo {
        // 1) Zero-copy attempt via FFmpegKit SAF protocol.
        val safSource = try {
            val param = FFmpegKitConfig.getSafParameterForRead(context, uri)
            if (param.isNullOrBlank()) null
            else InputSource.SafSource(param, inspection.displayName, inspection.sizeBytes)
        } catch (t: Throwable) {
            LogStore.event("SAF registration failed: ${t.javaClass.simpleName}")
            null
        }

        if (safSource != null) {
            val probed = MediaProbe.probe(safSource)
            if (probed != null) {
                LogStore.event("FFmpeg input: SAF zero-copy (${safSource.ffmpegInput}) — accessible")
                return LoadedVideo(safSource, probed.withSizeFallback(inspection.sizeBytes))
            }
            LogStore.event("SAF input failed FFprobe verification — falling back to cache bridge")
        }

        // 2) Cache-file bridge (buffered streaming copy, large-file safe).
        val copy = copyToCache(context, uri, inspection.displayName)
            ?: throw VideoInputException(
                VideoInputException.Kind.UNREADABLE,
                "Unable to read this video."
            )
        val fileSource = InputSource.FileSource(
            path = copy.absolutePath,
            displayName = inspection.displayName,
            sizeBytes = copy.length(),
            deletable = true
        )

        val probed = MediaProbe.probe(fileSource)
        if (probed != null) {
            LogStore.event("FFmpeg input: cache bridge ${copy.absolutePath} — accessible")
            return LoadedVideo(fileSource, probed)
        }

        // FFprobe could not read even the local copy, but the platform decoded
        // the Uri in inspect(): trust the platform metadata and stream from the
        // copy — FFmpeg may still handle containers FFprobe's quick scan rejects.
        LogStore.event("FFprobe failed on cache copy; using platform metadata")
        return LoadedVideo(fileSource, inspection.platformInfo!!.withSizeFallback(copy.length()))
    }

    /** One-shot helper: inspect + prepare. */
    fun load(context: Context, uri: Uri): LoadedVideo {
        val inspection = inspect(context, uri)
        return prepareForFFmpeg(context, uri, inspection)
    }

    /** Deletes any temporary bridge copy (called after streaming stops). */
    fun releaseTemporaryCopy(loaded: LoadedVideo?) {
        val source = loaded?.source
        if (source is InputSource.FileSource && source.deletable) {
            try {
                File(source.path).delete()
                LogStore.event("Temporary video copy deleted")
            } catch (t: Throwable) {
                LogStore.event("Could not delete temporary copy: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun copyToCache(context: Context, uri: Uri, displayName: String): File? {
        return try {
            val dir = File(context.cacheDir, "videos").apply { mkdirs() }
            // A new selection replaces any previous bridge copy.
            dir.listFiles()?.forEach { it.delete() }
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9.\\-_ ]"), "_")
            val target = File(dir, safeName)
            LogStore.event("Copying video to temporary bridge file (buffered stream)...")
            val input = context.contentResolver.openInputStream(uri)
                ?: return null
            input.use { rawIn ->
                java.io.BufferedInputStream(rawIn, 256 * 1024).use { buffered ->
                    java.io.FileOutputStream(target).use { out ->
                        buffered.copyTo(out, 256 * 1024)
                    }
                }
            }
            target
        } catch (t: Throwable) {
            LogStore.event("Cache bridge copy failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun looksLikeVideoMime(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("video/") ||
            m == "application/octet-stream" ||
            m.contains("matroska") ||
            m.contains("mp4") ||
            m.contains("mpeg") ||
            m.contains("avi")
    }
}

private fun VideoInfo.withSizeFallback(size: Long): VideoInfo =
    if (sizeBytes > 0) this else copy(sizeBytes = size)
