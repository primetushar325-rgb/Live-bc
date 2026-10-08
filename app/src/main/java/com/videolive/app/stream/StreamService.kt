package com.videolive.app.stream

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.videolive.app.MainActivity
import com.videolive.app.R
import com.videolive.app.data.SecurePrefs
import com.videolive.app.data.VideoRepository
import com.videolive.app.ffmpeg.ErrorKind
import com.videolive.app.ffmpeg.FFmpegCommandBuilder
import com.videolive.app.ffmpeg.FFmpegManager
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.ffmpeg.RunResult
import com.videolive.app.media.LoadedVideo
import com.videolive.app.media.VideoLoader
import com.videolive.app.model.StreamConfig
import com.videolive.app.util.Net
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the entire streaming pipeline.
 *
 * Stability design:
 *  - ONE FFmpeg session loops the video with -stream_loop -1; the RTMP output is
 *    opened once and is never closed at a loop boundary.
 *  - Reconnects happen ONLY on real network/RTMP failures or watchdog stalls,
 *    with exponential backoff and a retry budget.
 *  - A partial WakeLock keeps the CPU encode running while the screen is locked.
 */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.videolive.app.action.START_STREAM"
        const val ACTION_STOP = "com.videolive.app.action.STOP_STREAM"
        const val EXTRA_CONFIG = "stream_config"
        const val CHANNEL_ID = "vl_live_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ATTEMPTS = 5
        private const val WATCHDOG_STALL_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // Markers in FFmpeg output that mean the RTMP/network layer failed.
        private val RTMP_FAILURE_MARKERS = listOf(
            "failed to connect", "connection refused", "connection reset",
            "connection timed out", "operation timed out", "broken pipe",
            "network is unreachable", "host not found", "could not resolve",
            "handshake failed", "i/o error", "input/output error",
            "server error", "not authorized", "access denied", "403",
            "tls error", "ssl error", "connection ended", "error writing"
        )

        val uiState = MutableStateFlow(StreamUiState())

        @Volatile
        var isStreaming = false
            private set

        /** Last config used, so the Live screen can offer a Retry on ERROR. */
        @Volatile
        private var lastConfig: StreamConfig? = null

        fun start(context: Context, config: StreamConfig) {
            val intent = Intent(context, StreamService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Restarts the last attempted stream (used by the Retry action). */
        fun retry(context: Context): Boolean {
            val config = lastConfig ?: return false
            // A retry is a NEW session — give it its own log session id.
            LogStore.startSession()
            start(context, config)
            return true
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ffmpeg = FFmpegManager()
    private var wakeLock: PowerManager.WakeLock? = null
    private var streamJob: Job? = null
    private var tickerJob: Job? = null

    @Volatile private var stopRequested = false
    private var startedAt = 0L
    @Volatile private var lastProgressAt = System.currentTimeMillis()

    // Per-FFmpeg-session evidence flags.
    @Volatile private var everConnected = false
    @Volatile private var connectTimedOut = false
    @Volatile private var liveNotified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isStreaming) {
                    return START_NOT_STICKY
                }
                val config = extractConfig(intent)
                if (config == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                lastConfig = config
                stopRequested = false
                liveNotified = false
                startedAt = System.currentTimeMillis()
                isStreaming = true
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(config.videoName),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                acquireWakeLock()
                streamJob?.cancel()
                streamJob = scope.launch { runStream(config) }
            }
            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    private fun extractConfig(intent: Intent): StreamConfig? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_CONFIG, StreamConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig
        }
    } catch (t: Throwable) {
        null
    }

    private fun requestStop() {
        if (!isStreaming && streamJob?.isActive != true) {
            stopSelf()
            return
        }
        stopRequested = true
        post { it.copy(phase = Phase.STOPPING, statusText = "Stopping...") }
        LogStore.event("Stop requested by user")
        MicMixer.stop()
        ffmpeg.cancelCurrent()
    }

    private suspend fun runStream(config: StreamConfig) {
        var pipePath: String? = null
        // Defensive: a session id must exist so every event of this run is
        // traceable under one tag (normally started by the START LIVE tap).
        if (!LogStore.hasSession()) LogStore.startSession()
        LogStore.event("START LIVE requested")
        post {
            StreamUiState(
                phase = Phase.PREPARING,
                statusText = "Preparing...",
                videoName = config.videoName,
                outWidth = config.outputSize().first,
                outHeight = config.outputSize().second,
                fps = config.fps,
                configuredBitrateKbps = config.targetBitrateKbps(),
                hasVideoAudio = config.hasAudio,
                volumePct = config.videoVolumePct
            )
        }
        startTicker()
        try {
            val key = SecurePrefs.getStreamKey(this).orEmpty()
            if (key.isBlank() && !config.fullUrlMode) {
                LogStore.event("Startup aborted: stream key is empty")
                failNow("Please enter your YouTube Stream Key.")
                return
            }
            val destination = FFmpegCommandBuilder.buildDestinationUrl(
                config.fullUrlMode, config.serverUrl, config.fullUrl, key
            )
            if (destination == null) {
                LogStore.event("Startup aborted: RTMP destination URL is malformed")
                failNow("Please enter a valid RTMP/RTMPS server URL and Stream Key (no spaces).")
                return
            }
            // Sanitize masks the path (stream key) of rtmp(s) URLs.
            LogStore.event("RTMP destination validated: $destination")

            // loadOnce is single-flight: even if something else is preparing
            // the same video right now, this waits and reuses that result —
            // it can never start a second cache copy.
            val loaded: LoadedVideo =
                VideoRepository.current?.takeIf { it.source.displayName == config.videoName }
                    ?: try {
                        VideoLoader.loadOnce(this@StreamService, Uri.parse(config.videoUri))
                    } catch (e: com.videolive.app.media.VideoInputException) {
                        LogStore.event("Startup aborted: input preparation failed")
                        failNow(e.message ?: "Unable to read this video.")
                        return
                    } catch (t: Throwable) {
                        LogStore.event("Startup aborted: input preparation crashed (${t.javaClass.simpleName})")
                        failNow("Unable to read this video.")
                        return
                    }
            VideoRepository.current = loaded
            val info = loaded.info
            LogStore.event(
                "Input prepared: " +
                    (if (loaded.source.isTemporaryCopy) "cache bridge copy" else "direct read") +
                    " | ${info.width}x${info.height}, ${info.fps} fps, " +
                    "duration ${info.durationMs / 1000}s, audio=${if (info.hasAudio) "yes" else "no"}"
            )
            LogStore.event(
                "Output plan: H.264 + AAC -> FLV -> RTMP, " +
                    "${config.outputSize().first}x${config.outputSize().second} @ ${config.fps} fps, " +
                    "${config.targetBitrateKbps()} kbps, loop=in-engine (-stream_loop -1)"
            )

            // Microphone setup (only when requested).
            var micActive = config.micOn
            if (micActive) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    LogStore.event("Microphone permission missing — continuing without mic")
                    micActive = false
                } else {
                    val initialPipe = withContext(Dispatchers.IO) {
                        FFmpegKitConfig.registerNewFFmpegPipe(this@StreamService)
                    }
                    micActive = MicMixer.start(initialPipe)
                    if (micActive) {
                        pipePath = initialPipe
                    } else {
                        safeClosePipe(initialPipe)
                        pipePath = null
                        LogStore.event("Microphone unavailable — continuing with video audio only")
                    }
                }
            }
            post { it.copy(micActive = micActive) }

            var attempt = 0
            while (!stopRequested) {
                // Network gate: don't burn retries while the radio is simply down.
                if (!Net.isOnline(this)) {
                    attempt++
                    if (attempt > MAX_ATTEMPTS) {
                        failNow("Internet connection lost. Could not recover the stream.")
                        return
                    }
                    post {
                        it.copy(
                            phase = Phase.RECONNECTING,
                            statusText = "Network problem...",
                            attempt = attempt
                        )
                    }
                    LogStore.event("Network unavailable — waiting ($attempt/$MAX_ATTEMPTS)")
                    stoppableDelay(backoffMs(attempt))
                    continue
                }

                post {
                    it.copy(
                        phase = Phase.CONNECTING,
                        statusText = if (attempt == 0) "Connecting to YouTube..."
                        else "Reconnecting $attempt/$MAX_ATTEMPTS...",
                        attempt = attempt,
                        liveFps = 0f,
                        liveBitrateKbps = 0,
                        speed = 0.0
                    )
                }

                // Fresh mic pipe for every new RTMP session.
                if (attempt > 0 && micActive) {
                    pipePath?.let { safeClosePipe(it) }
                    val freshPipe = withContext(Dispatchers.IO) {
                        FFmpegKitConfig.registerNewFFmpegPipe(this@StreamService)
                    }
                    pipePath = freshPipe
                    MicMixer.switchPipe(freshPipe)
                }

                val args = FFmpegCommandBuilder.build(
                    config,
                    info,
                    loaded.source.ffmpegInput,
                    destination,
                    if (micActive) pipePath else null
                )
                // Full command for diagnostics — destination (contains the stream
                // key) is replaced before logging; Sanitize masks it anyway.
                val maskedCommand = args.joinToString(" ") {
                    if (it == destination) "${destination.substringBefore("://")}://<hidden-key>" else it
                }
                LogStore.event("FFmpeg command: $maskedCommand")
                LogStore.event("FFmpeg session starting (attempt ${attempt + 1})")
                LogStore.event("Connecting to RTMP server...")

                // Reset per-session evidence flags.
                everConnected = false
                connectTimedOut = false
                var statsSeen = 0
                var inputLogged = false
                var encoderLogged = false
                var muxerLogged = false
                var rtmpFailureLogged = false
                val stageOnLog: (String) -> Unit = { line ->
                    val l = line.lowercase()
                    if (!inputLogged && l.contains("input #")) {
                        inputLogged = true
                        LogStore.event("FFmpeg opened the input successfully")
                    }
                    if (!encoderLogged && l.contains("stream #") && l.contains("->")) {
                        encoderLogged = true
                        LogStore.event("Encoder initialized (H.264/AAC mapping active)")
                    }
                    if (!muxerLogged && l.contains("output #")) {
                        muxerLogged = true
                        LogStore.event("FLV muxer initialized")
                        post {
                            if (it.phase == Phase.CONNECTING || it.phase == Phase.PREPARING) {
                                it.copy(phase = Phase.ENCODING, statusText = "Encoder started...")
                            } else it
                        }
                    }
                    if (!rtmpFailureLogged && RTMP_FAILURE_MARKERS.any { l.contains(it) }) {
                        rtmpFailureLogged = true
                        LogStore.event("Engine reported RTMP/network failure: ${line.trim().take(180)}")
                    }
                }

                lastProgressAt = System.currentTimeMillis()
                val sessionStartedAt = System.currentTimeMillis()
                val watchdog = scope.launch { watchdogLoop(sessionStartedAt) }
                val result = ffmpeg.run(args, stageOnLog) { st ->
                    statsSeen++
                    lastProgressAt = System.currentTimeMillis()
                    val loops =
                        if (info.durationMs > 0) (st.time / info.durationMs).toInt() else 0
                    val netOk = Net.isOnline(this@StreamService)
                    if (!everConnected) {
                        everConnected = true
                        // Everything below completes INSIDE the output open,
                        // before the first frame is ever encoded — the first
                        // statistic is the proof they all succeeded.
                        LogStore.event("RTMP(S) transport established (TCP + TLS + RTMP handshake)")
                        LogStore.event("RTMP connect command successful")
                        LogStore.event("RTMP publish accepted by server")
                        LogStore.event(
                            "First video/audio packets transmitted to ingest " +
                                "(frame #${st.videoFrameNumber}, ${st.bitrate.toInt()} kbps, " +
                                "out_time ${st.time} ms, total ${st.size} bytes)"
                        )
                        post { s ->
                            if (s.phase == Phase.STOPPING || s.phase == Phase.ERROR) s
                            else s.copy(
                                phase = Phase.PUBLISHING,
                                statusText = "Publishing...",
                                liveFps = st.videoFps,
                                liveBitrateKbps = st.bitrate.toInt(),
                                speed = st.speed,
                                loopCount = loops,
                                networkOk = netOk
                            )
                        }
                    } else {
                        if (statsSeen == 2) {
                            LogStore.event(
                                "ENCODER STREAMING — packets flowing continuously. " +
                                    "YouTube broadcast confirmation pending: check the " +
                                    "YouTube Live Control Room preview (press GO LIVE if needed)."
                            )
                        }
                        if (statsSeen % 30 == 0) {
                            LogStore.event(
                                "Stream health: frame #${st.videoFrameNumber}, ${st.videoFps} fps, " +
                                    "${st.bitrate.toInt()} kbps, out_time ${st.time} ms, " +
                                    "total ${st.size} bytes, loop ${loops + 1}"
                            )
                        }
                        post { s ->
                            if (s.phase == Phase.STOPPING || s.phase == Phase.ERROR) s
                            else s.copy(
                                phase = Phase.STREAMING,
                                statusText = "Sending video to YouTube...",
                                liveFps = st.videoFps,
                                liveBitrateKbps = st.bitrate.toInt(),
                                speed = st.speed,
                                loopCount = loops,
                                networkOk = netOk
                            )
                        }
                        if (!liveNotified) {
                            liveNotified = true
                            refreshNotification(config.videoName, live = true)
                        }
                    }
                }
                watchdog.cancel()

                if (stopRequested) break

                if (connectTimedOut) {
                    LogStore.event("ERROR: RTMP connection timed out after ${CONNECT_TIMEOUT_MS / 1000}s")
                    failNow(
                        "RTMP connection timed out. The server did not accept the " +
                            "connection within ${CONNECT_TIMEOUT_MS / 1000} seconds. " +
                            "Check your internet/firewall and try again."
                    )
                    return
                }

                val ranSeconds = (System.currentTimeMillis() - sessionStartedAt) / 1000
                // A long healthy run resets the retry budget.
                if (ranSeconds >= 60) attempt = 0

                when (result) {
                    is RunResult.Success -> {
                        LogStore.event("FFmpeg exited cleanly after ${ranSeconds}s (unexpected)")
                        attempt++
                        if (attempt > MAX_ATTEMPTS) {
                            failNow("Streaming engine exited repeatedly.")
                            return
                        }
                        post {
                            it.copy(
                                phase = Phase.RECONNECTING,
                                statusText = "Stream interrupted — restarting $attempt/$MAX_ATTEMPTS...",
                                attempt = attempt
                            )
                        }
                        stoppableDelay(backoffMs(attempt))
                    }
                    is RunResult.Cancelled -> {
                        // A cancel here means the watchdog killed a stalled session.
                        attempt++
                        LogStore.event("Stream stalled — forcing reconnect ($attempt/$MAX_ATTEMPTS)")
                        if (attempt > MAX_ATTEMPTS) {
                            failNow("The stream kept stalling. Please try again.")
                            return
                        }
                        post {
                            it.copy(
                                phase = Phase.RECONNECTING,
                                statusText = "Reconnecting $attempt/$MAX_ATTEMPTS...",
                                attempt = attempt
                            )
                        }
                        stoppableDelay(backoffMs(attempt))
                    }
                    is RunResult.Failed -> {
                        val err = result.error
                        LogStore.event("FFmpeg failed: ${err.userMessage}")
                        if (err.kind == ErrorKind.AUTH || err.kind == ErrorKind.INPUT) {
                            failNow(err.userMessage)
                            return
                        }
                        attempt++
                        if (attempt > MAX_ATTEMPTS) {
                            failNow("${err.userMessage} (retry limit reached)")
                            return
                        }
                        post {
                            it.copy(
                                phase = Phase.RECONNECTING,
                                statusText = "Reconnecting $attempt/$MAX_ATTEMPTS — ${err.userMessage}",
                                attempt = attempt
                            )
                        }
                        stoppableDelay(backoffMs(attempt))
                    }
                }
            }

            post { it.copy(phase = Phase.STOPPED, statusText = "Live ended") }
            LogStore.event("Stream stopped cleanly")
        } catch (t: Throwable) {
            LogStore.event("Unexpected error: ${t.javaClass.simpleName}")
            failNow("Unexpected error: ${t.javaClass.simpleName}")
        } finally {
            cleanup(pipePath)
        }
    }

    private suspend fun watchdogLoop(sessionStartedAt: Long) {
        while (coroutineContext.isActive) {
            delay(2000)
            if (stopRequested) continue
            if (!everConnected) {
                // Connection timeout: the RTMP output was not accepted within
                // the budget. Stop the attempt cleanly instead of leaving the
                // UI stuck on "Connecting..." forever.
                val waited = System.currentTimeMillis() - sessionStartedAt
                if (waited > CONNECT_TIMEOUT_MS) {
                    connectTimedOut = true
                    LogStore.event(
                        "No connection established in ${CONNECT_TIMEOUT_MS / 1000}s — aborting attempt"
                    )
                    ffmpeg.cancelCurrent()
                    return
                }
            } else {
                val idleMs = System.currentTimeMillis() - lastProgressAt
                if (idleMs > WATCHDOG_STALL_MS) {
                    LogStore.event("No encode progress for ${idleMs / 1000}s — restarting engine")
                    ffmpeg.cancelCurrent()
                    return
                }
            }
        }
    }

    private fun failNow(message: String) {
        post { it.copy(phase = Phase.ERROR, statusText = message, errorText = message) }
        LogStore.event("ERROR: $message")
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                post { it.copy(elapsedMs = System.currentTimeMillis() - startedAt) }
                delay(1000)
            }
        }
    }

    private fun cleanup(pipePath: String?) {
        isStreaming = false
        tickerJob?.cancel()
        try {
            MicMixer.stop()
        } catch (_: Throwable) {
        }
        pipePath?.let { safeClosePipe(it) }
        // The temporary cache bridge copy (if one was made) is deleted after
        // streaming stops. It will be re-created on the next start if needed.
        try {
            VideoLoader.releaseTemporaryCopy(VideoRepository.current)
        } catch (_: Throwable) {
        }
        VideoRepository.current = null
        wakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Throwable) {
            }
        }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun safeClosePipe(path: String) {
        try {
            FFmpegKitConfig.closeFFmpegPipe(path)
        } catch (_: Throwable) {
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val step = (attempt - 1).coerceIn(0, 6)
        return min((1L shl step) * 2000L, 30_000L)
    }

    /** Backoff delay that exits promptly when STOP LIVE is pressed. */
    private suspend fun stoppableDelay(ms: Long) {
        var left = ms
        while (left > 0 && !stopRequested) {
            val step = minOf(500L, left)
            delay(step)
            left -= step
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videolive:stream").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // hard cap: 12 hours
            }
        } catch (t: Throwable) {
            LogStore.event("WakeLock unavailable: ${t.javaClass.simpleName}")
        }
    }

    private inline fun post(crossinline transform: (StreamUiState) -> StreamUiState) {
        uiState.update { transform(it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Live streaming",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "Keeps the live stream running in the background"
                channel.setShowBadge(false)
                nm.createNotificationChannel(channel)
            }
        }
    }

    /** Re-publishes the foreground notification (e.g. once we are really LIVE). */
    private fun refreshNotification(videoName: String, live: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(videoName, live))
        } catch (t: Throwable) {
            LogStore.event("Notification update failed: ${t.javaClass.simpleName}")
        }
    }

    private fun buildNotification(videoName: String, live: Boolean = false): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, StreamService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (live) "🔴 LIVE: $videoName" else "Preparing live stream")
            .setContentText(if (live) "Streaming to YouTube" else "Video: $videoName")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_stop, "Stop", stopPi)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
