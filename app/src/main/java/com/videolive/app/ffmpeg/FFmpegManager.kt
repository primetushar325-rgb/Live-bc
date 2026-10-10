package com.videolive.app.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class RunResult {
    object Cancelled : RunResult()
    object Success : RunResult()
    data class Failed(val error: ClassifiedError) : RunResult()
}

/** Marks executor tasks that must NEVER be dropped: the session-completion
 * signal and the statistics heartbeat. Losing completion means the coroutine
 * suspends forever while the UI still claims LIVE; losing stats starves the
 * stall watchdog. Ordinary log lines remain droppable under pressure. */
private class Critical(private val body: () -> Unit) : Runnable {
    override fun run() = body()
}

/**
 * Owns the single live FFmpeg session. Execution is in-process (FFmpegKit links
 * FFmpeg natively) — there is no external process that can die unnoticed.
 *
 * CRITICAL THREADING (stutter fix, measured rationale):
 * ffmpeg-kit invokes the log/statistics callbacks ON THE FFmpeg EXECUTION
 * THREAD. Any work done there (regex masking, date formatting, synchronized
 * buffers, state posts) directly stalls decode/encode/mux for that moment.
 * Progress lines arrive several times per second — exactly the cadence of the
 * reported freezes. Therefore every callback here only ENQUEUES a tiny task;
 * one dedicated dispatcher thread drains the queue and does the real work.
 * The queue is bounded and drops diagnostic lines under pressure instead of
 * ever letting diagnostics block the media pipeline — but [Critical] tasks
 * (completion, statistics) run inline rather than being dropped, so the
 * completion signal can never be lost to queue saturation.
 */
class FFmpegManager {

    @Volatile private var session: FFmpegSession? = null

    private val callbackExecutor = ThreadPoolExecutor(
        1, 1,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(4000),
        { r -> Thread(r, "vl-ffmpeg-callbacks") },
        RejectedExecutionHandler { r, _ ->
            // Log lines may be dropped; completion and statistics never are.
            if (r is Critical) r.run()
        }
    )

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
            // Critical: if the queue is saturated this runs inline instead of
            // being dropped — the completion signal can never be lost.
            callbackExecutor.execute(Critical {
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
            })
        }
        val logCallback = LogCallback { log ->
            val message = log.message ?: ""
            // Droppable diagnostics — never allowed to block the engine.
            callbackExecutor.execute {
                LogStore.append(message)
                try {
                    onLog(message)
                } catch (_: Throwable) {
                }
            }
        }
        val statsCallback = StatisticsCallback { st ->
            // Critical: the stats heartbeat feeds lastProgressAt; dropping it
            // under load would fake a stall and trigger needless reconnects.
            callbackExecutor.execute(Critical { onStats(st) })
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
