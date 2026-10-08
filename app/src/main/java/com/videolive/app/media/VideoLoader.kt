package com.videolive.app.media

import android.content.Context
import android.net.Uri
import com.videolive.app.data.VideoRepository
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.model.VideoInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * Turns an Android content:// Uri (ACTION_OPEN_DOCUMENT) into a REAL, readable
 * file input for FFmpeg.
 *
 * History: a "SAF zero-copy" input (`saf:<name>`) was tried, but FFmpeg cannot
 * reliably read that pseudo path — it produced "moov atom not found" /
 * "Invalid data found when processing input". ContentResolver access is NOT
 * FFmpeg access, so that path was removed.
 *
 * Pipeline now (byte-level, proven on device):
 *  1. [inspect] — open the Uri via ContentResolver, read name/size/MIME, pull
 *     platform metadata with MediaMetadataRetriever (works directly on the Uri).
 *     Fast; powers the immediate UI (thumbnail, name, duration, ...).
 *  2. [prepareForFFmpeg] — buffered streaming copy of the ACTUAL BYTES from
 *     ContentResolver.openInputStream(uri) into an app-private cache file
 *     (1 MB buffer — never loads the video into RAM, works for files far
 *     larger than RAM). After the copy:
 *       - file exists, non-empty, byte count matches the source
 *       - a real FFprobe run against the cache file must succeed
 *     Only then is the cache path handed to FFmpeg as `-i <real file>`.
 *  The cache file stays in place for the whole FFmpeg session and is deleted
 *     after streaming stops (or on next app start via [purgeStaleCache]).
 */
object VideoLoader {

    private const val CACHE_DIR = "videos"
    private const val BUFFER_SIZE = 1024 * 1024 // 1 MB — spec: never copy into RAM whole

    /**
     * Single-flight guard for input preparation. No matter how many callers
     * (selection flow, START LIVE rebuild, service reload) ask at the same
     * time: exactly ONE cache copy ever runs; every other caller waits and
     * then reuses the finished result.
     */
    private val prepareMutex = Mutex()

    /**
     * Prepares the FFmpeg input once. If a preparation is already running,
     * waits for it and returns its result; if a video is already prepared,
     * returns it without copying again.
     */
    suspend fun prepareOnce(
        context: Context,
        uri: Uri,
        inspection: Inspection,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): LoadedVideo = prepareMutex.withLock {
        VideoRepository.current?.let { return@withLock it }
        val loaded = withContext(Dispatchers.IO) {
            prepareForFFmpeg(context.applicationContext, uri, inspection, onProgress)
        }
        VideoRepository.current = loaded
        VideoRepository.currentUri = uri
        loaded
    }

    /** One-shot single-flight variant: inspect + prepare exactly once. */
    suspend fun loadOnce(
        context: Context,
        uri: Uri,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): LoadedVideo = prepareMutex.withLock {
        VideoRepository.current?.let { return@withLock it }
        val loaded = withContext(Dispatchers.IO) {
            load(context.applicationContext, uri, onProgress)
        }
        VideoRepository.current = loaded
        VideoRepository.currentUri = uri
        loaded
    }

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

        LogStore.event("Android content URI accessible")
        return Inspection(displayName, check.sizeBytes, mimeType, platformInfo)
    }

    /**
     * Builds the FFmpeg input: a complete byte-level cache copy of the video
     * plus a real FFprobe verification of that file. Streaming MUST NOT start
     * before this returns — the copy is finished and verified at that point.
     *
     * [onProgress] (called on the copy thread) receives (copiedBytes, totalBytes);
     * totalBytes is -1 when the provider did not report a size.
     */
    fun prepareForFFmpeg(
        context: Context,
        uri: Uri,
        inspection: Inspection,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): LoadedVideo {
        val copy = copyToCache(context, uri, inspection.displayName, inspection.sizeBytes, onProgress)
            ?: throw VideoInputException(
                VideoInputException.Kind.UNREADABLE,
                "Unable to read this video."
            )
        LogStore.event("FFmpeg cache ready: ${copy.length()} bytes (${copy.length() / (1024 * 1024)} MB)")

        val fileSource = InputSource.FileSource(
            path = copy.absolutePath,
            displayName = inspection.displayName,
            sizeBytes = copy.length(),
            deletable = true
        )

        // Real probe test against the ACTUAL cached file — never stream without it.
        LogStore.event("FFmpeg probe started (cache file)")
        val probed = MediaProbe.probe(fileSource)
        if (probed == null) {
            try {
                copy.delete()
            } catch (_: Throwable) {
            }
            LogStore.event("Video preparation failed: FFprobe could not decode the cached file")
            throw VideoInputException(
                VideoInputException.Kind.UNDECODABLE,
                "Video preparation failed."
            )
        }
        LogStore.event(
            "FFmpeg probe successful: ${probed.width}x${probed.height} @ ${probed.fps} fps, " +
                "${probed.durationMs} ms, video=${probed.videoCodec.ifEmpty { "?" }}, " +
                "audio=${if (probed.hasAudio) probed.audioCodec.ifEmpty { "present" } else "none"}"
        )
        LogStore.event("FFmpeg input prepared: local cache file")
        return LoadedVideo(fileSource, probed.withSizeFallback(copy.length()))
    }

    /** One-shot helper: inspect + prepare. */
    fun load(
        context: Context,
        uri: Uri,
        onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): LoadedVideo {
        val inspection = inspect(context, uri)
        return prepareForFFmpeg(context, uri, inspection, onProgress)
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

    /**
     * Removes leftover cache files from crashed/killed sessions. Call at app
     * startup — but never while a stream is running.
     */
    fun purgeStaleCache(context: Context) {
        try {
            val dir = File(context.cacheDir, CACHE_DIR)
            val files = dir.listFiles()
            if (files != null && files.isNotEmpty()) {
                files.forEach { it.delete() }
                LogStore.event("Stale cache files removed: ${files.size}")
            }
        } catch (_: Throwable) {
        }
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }

    /**
     * Buffered byte-for-byte copy from the content Uri into the private cache.
     * Returns the cache file only when the copy is COMPLETE and verified.
     */
    private fun copyToCache(
        context: Context,
        uri: Uri,
        displayName: String,
        expectedBytes: Long,
        onProgress: ((Long, Long) -> Unit)?
    ): File? {
        return try {
            val dir = cacheDir(context)
            // A new selection replaces any previous bridge copy.
            dir.listFiles()?.forEach { it.delete() }

            val safeName = displayName.replace(Regex("[^a-zA-Z0-9.\\-_ ]"), "_").take(60)
            val target = File(dir, "stream_input_${System.currentTimeMillis()}_$safeName")
            LogStore.event("Copying video to FFmpeg cache...")
            LogStore.event("Cache file: ${target.absolutePath}")

            var copied = 0L
            var lastProgressAt = 0L
            var lastLoggedPct = 0
            val input = context.contentResolver.openInputStream(uri) ?: run {
                LogStore.event("ContentResolver refused to open the URI")
                return null
            }
            input.use { rawIn ->
                BufferedInputStream(rawIn, BUFFER_SIZE).use { src ->
                    FileOutputStream(target).use { dest ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = src.read(buffer)
                            if (read <= 0) break
                            dest.write(buffer, 0, read)
                            copied += read
                            if (onProgress != null && copied - lastProgressAt >= 2_000_000L) {
                                lastProgressAt = copied
                                try {
                                    onProgress(copied, expectedBytes)
                                } catch (_: Throwable) {
                                }
                            }
                            if (expectedBytes > 0) {
                                val pct = ((copied * 100) / expectedBytes).toInt()
                                if (pct >= lastLoggedPct + 25 && pct <= 100) {
                                    lastLoggedPct = pct
                                    LogStore.event("Copying video to FFmpeg cache... $pct%")
                                }
                            }
                        }
                        dest.flush()
                        dest.fd.sync()
                    }
                }
            }

            // Completeness verification before FFmpeg ever sees this file.
            if (!target.exists() || target.length() <= 0) {
                LogStore.event("Cache copy failed: destination file missing or empty")
                target.delete()
                return null
            }
            if (expectedBytes > 0 && copied != expectedBytes) {
                LogStore.event(
                    "Cache copy INCOMPLETE: expected $expectedBytes bytes, got $copied — aborting"
                )
                target.delete()
                return null
            }
            LogStore.event("Cache copy completed successfully: $copied bytes")
            target
        } catch (t: Throwable) {
            LogStore.event("Cache bridge copy failed: ${t.javaClass.simpleName}: ${t.message}")
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
