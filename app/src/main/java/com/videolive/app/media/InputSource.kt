package com.videolive.app.media

/**
 * Where FFmpeg reads the video from.
 *
 * [SafSource] uses FFmpegKit's built-in SAF protocol: the content:// Uri is
 * handed to FFmpeg through a registered file descriptor. No copy of the video
 * file is made — critical for multi-GB files.
 *
 * [FileSource] is used only when the SAF descriptor is unusable; the video is
 * copied once into the app cache.
 */
sealed class InputSource {
    abstract val ffmpegInput: String
    abstract val displayName: String
    abstract val sizeBytes: Long

    data class SafSource(
        override val ffmpegInput: String,
        override val displayName: String,
        override val sizeBytes: Long
    ) : InputSource()

    data class FileSource(
        val path: String,
        override val displayName: String,
        override val sizeBytes: Long
    ) : InputSource() {
        override val ffmpegInput: String get() = path
    }
}
