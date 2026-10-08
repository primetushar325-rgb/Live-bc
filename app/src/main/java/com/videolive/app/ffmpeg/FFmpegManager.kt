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
     * [onLog] receives every raw engine output line (already sanitized by the
     * caller pipeline) so the service can detect pipeline stages and RTMP
     * failures; [onStats] receives periodic encode statistics.
     */
    suspend fun run(
        args: List<String>,
        onLog: (String) -> Unit,
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
            val message = log.message ?: ""
            LogStore.append(message)
            try {
                onLog(message)
            } catch (_: Throwable) {
            }
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
