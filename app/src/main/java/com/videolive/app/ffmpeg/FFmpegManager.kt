package com.videolive.app.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class RunResult {
    object Cancelled : RunResult()
    object Success : RunResult()
    data class Failed(val error: ClassifiedError) : RunResult()
}

/**
 * Owns the single live FFmpeg session. Execution is in-process (FFmpegKit links
 * FFmpeg natively) — there is no external process that can die unnoticed.
 */
class FFmpegManager {

    @Volatile private var session: FFmpegSession? = null

    val ffmpegVersion: String
        get() = try {
            FFmpegKitConfig.getFFmpegVersion() ?: "unknown"
        } catch (t: Throwable) {
            "unavailable"
        }

    fun cancelCurrent() {
        try {
            session?.cancel()
        } catch (t: Throwable) {
            LogStore.event("Cancel failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Runs one FFmpeg session and suspends until it ends.
     * [onStats] receives periodic encode statistics (fps, bitrate, speed, time).
     */
    suspend fun run(
        args: List<String>,
        onStats: (Statistics) -> Unit
    ): RunResult = suspendCancellableCoroutine { cont ->
        val complete = FFmpegSessionCompleteCallback { s ->
            val result = when {
                s.returnCode == null -> RunResult.Failed(
                    ClassifiedError(
                        com.videolive.app.ffmpeg.ErrorKind.ENGINE,
                        "Streaming engine stopped unexpectedly."
                    )
                )
                s.returnCode.isValueCancel -> RunResult.Cancelled
                s.returnCode.isValueSuccess -> RunResult.Success
                else -> RunResult.Failed(ErrorClassifier.classify(LogStore.recentTail()))
            }
            if (cont.isActive) cont.resume(result)
        }
        val logCallback = LogCallback { log ->
            LogStore.append(log.message ?: "")
        }
        val statsCallback = StatisticsCallback { st ->
            onStats(st)
        }

        val s = FFmpegSession.create(
            args.toTypedArray(),
            complete,
            logCallback,
            statsCallback
        )
        session = s
        FFmpegKitConfig.asyncFFmpegExecute(s)

        cont.invokeOnCancellation {
            try {
                s.cancel()
            } catch (_: Throwable) {
            }
        }
    }
}
