package com.videolive.app.media

/**
 * Where FFmpeg reads the video from.
 *
 * [SafSource] uses FFmpegKit's built-in SAF protocol: the content:// Uri is
 * handed to FFmpeg through a registered file descriptor. No copy of the video
 * file is made — preferred for multi-GB files, but only used after it has
 * been verified with FFprobe.
 *
 * [FileSource] is the reliable cache-file bridge: bytes are streamed from
 * ContentResolver.openInputStream(uri) into cacheDir (buffered, never loaded
 * fully into RAM). Temporary copies are deleted after streaming stops and
 * whenever a new video is selected.
 */
sealed class InputSource {
    abstract val ffmpegInput: String
    abstract val displayName: String
    abstract val sizeBytes: Long
    abstract val isTemporaryCopy: Boolean

    data class SafSource(
        override val ffmpegInput: String,
        override val displayName: String,
        override val sizeBytes: Long
    ) : InputSource() {
        override val isTemporaryCopy: Boolean get() = false
    }

    data class FileSource(
        val path: String,
        override val displayName: String,
        override val sizeBytes: Long,
        val deletable: Boolean
    ) : InputSource() {
        override val ffmpegInput: String get() = path
        override val isTemporaryCopy: Boolean get() = deletable
    }
}
