package com.videolive.app.stream

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
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
import com.videolive.app.ffmpeg.FFmpegRuntime
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.ffmpeg.RunResult
import com.videolive.app.data.SettingsRepository
import com.videolive.app.media.LoadedVideo
import com.videolive.app.media.VideoLoader
import com.videolive.app.model.LoopMode
import com.videolive.app.model.StopPolicy
import com.videolive.app.model.StreamConfig
import com.videolive.app.util.Net
import java.io.File
import java.util.Locale
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

        /** Skips the current reconnect backoff wait and retries immediately. */
        fun requestRetryNow() {
            LogStore.event("Retry Now requested by user")
            retryNowFlag = true
        }

        @Volatile
        private var retryNowFlag = false

        internal fun consumeRetryNow(): Boolean {
            val v = retryNowFlag
            retryNowFlag = false
            return v
        }

        internal fun retryNowPending(): Boolean = retryNowFlag
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ffmpeg = FFmpegManager()
    private var wakeLock: PowerManager.WakeLock? = null
    private var streamJob: Job? = null
    private var tickerJob: Job? = null

    @Volatile private var stopRequested = false
    @Volatile private var retryNowRequested = false
    private var startedAt = 0L
    @Volatile private var lastProgressAt = System.currentTimeMillis()

    // Per-FFmpeg-session evidence flags.
    @Volatile private var everConnected = false
    @Volatile private var connectTimedOut = false
    @Volatile private var liveNotified = false

    // Diagnostics: measured output cadence (frame delta over time delta).
    private var prevStatFrames = 0L
    private var prevStatTimeMs = 0L
    @Volatile private var measuredFps = 0f
    @Volatile private var softStallWarned = false
    private var overloadStreak = 0
    private var overloadWarned = false

    // Encoder selection (Stage 1): hardware when available+verified, with an
    // automatic software fallback. encoderInUse is parsed from real FFmpeg
    // output, never assumed.
    @Volatile private var encoderInUse = ""
    @Volatile private var forceSoftware = false
    private var attemptUsedHw = false

    // Transport health: output bytes must grow while frames are encoded.
    // Frames advancing with frozen byte counter = RTMPS writes blocked.
    private var prevBytes = -1L

    // Long-duration latency drift: wall-clock elapsed minus encoded out_time.
    // If this grows, encode/transport runs slightly under real time — the
    // classic cause of "fine at first, laggy after ~1 hour" streams.
    private var attemptWallStartMs = 0L
    private var driftWarned = false
    private var driftCritical = false
    private var prevBytesFrames = 0L
    private var transportStallStreak = 0

    /** Service-side mirror of the UI state (terminal notification text etc). */
    @Volatile private var state = StreamUiState()

    // Playlist session bookkeeping (Phase 3).
    private var playlistMode = false
    private var playlistDurations = LongArray(0)
    private var playlistNames = emptyArray<String>()
    private var totalPlaylistMs = 0L
    @Volatile private var durationReached = false

    // Thermal guard (Phase 7).
    private lateinit var settingsRepo: SettingsRepository
    @Volatile private var deviceTempC = 0.0
    private var thermalWarnShown = false
    @Volatile private var thermalCritFired = false
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        settingsRepo = SettingsRepository(this)
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
                playlistMode = false
                totalPlaylistMs = 0L
                durationReached = false
                thermalWarnShown = false
                thermalCritFired = false
                deviceTempC = 0.0
                encoderInUse = ""
                forceSoftware = false
                startedAt = System.currentTimeMillis()
                isStreaming = true
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(config.videoName),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                acquireWakeLock()
                startThermalMonitor()
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

            // One-time, background capability probe: is a hardware H.264
            // encoder even packaged? (evidence for the hw-encoder question)
            withContext(Dispatchers.IO) { FFmpegRuntime.logEncoderCapabilities() }
            val hwPresent = withContext(Dispatchers.IO) { FFmpegRuntime.hasHardwareH264() }
            LogStore.event(
                "Encoder preference: ${config.encoderPref} | hardware H.264 present: $hwPresent"
            )

            // loadOnce / prepareBatch are single-flight: even if something else
            // is preparing right now, this waits and reuses the result — a
            // second cache copy can never start.
            val loadedList: List<LoadedVideo> = if (config.isPlaylist) {
                LogStore.event(
                    "Preparing playlist: ${config.items.size} items " +
                        "(cache reuse enabled, mode=${config.loopMode})"
                )
                try {
                    VideoLoader.prepareBatch(this@StreamService, config.items)
                } catch (e: com.videolive.app.media.VideoInputException) {
                    LogStore.event("Startup aborted: playlist preparation failed")
                    failNow(e.message ?: "Unable to read one of the playlist videos.")
                    return
                } catch (t: Throwable) {
                    LogStore.event("Startup aborted: playlist preparation crashed (${t.javaClass.simpleName})")
                    failNow("Unable to read one of the playlist videos.")
                    return
                }
            } else {
                listOf(
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
                )
            }

            playlistMode = config.isPlaylist && loadedList.size > 1
            if (playlistMode) {
                // Seamless concat requires identical codec params across items.
                val first = loadedList.first().info
                val uniform = loadedList.all {
                    it.info.videoCodec == first.videoCodec &&
                        it.info.width == first.width &&
                        it.info.height == first.height &&
                        it.info.hasAudio == first.hasAudio &&
                        (!first.hasAudio || it.info.audioCodec == first.audioCodec)
                }
                if (!uniform) {
                    failNow(
                        "Playlist videos must share the same codec, resolution and audio " +
                            "layout for seamless playback. Re-encode mismatched items to match."
                    )
                    return
                }
                loadedList.forEachIndexed { i, lv ->
                    LogStore.event(
                        "Playlist item ${i + 1}: ${lv.info.width}x${lv.info.height}, " +
                            "${lv.info.fps} fps, ${lv.info.durationMs / 1000}s, " +
                            "audio=${if (lv.info.hasAudio) "yes" else "no"} " +
                            (if (lv.source.isTemporaryCopy) "(cache bridge copy)" else "(direct read)")
                    )
                }
            } else {
                VideoRepository.current = loadedList.first()
            }

            val loaded = loadedList.first()
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
                    "${config.targetBitrateKbps()} kbps, " +
                    (if (playlistMode) "playlist=${loadedList.size} items (${config.loopMode})"
                    else "loop=in-engine (-stream_loop -1)")
            )

            // Playlist playback order: shuffle once per session; reconnects
            // always resume the same order.
            val playOrder: List<Int> =
                if (playlistMode && config.loopMode == LoopMode.SHUFFLE) loadedList.indices.shuffled()
                else loadedList.indices.toList()
            if (playlistMode) {
                playlistDurations = LongArray(playOrder.size) { loadedList[playOrder[it]].info.durationMs }
                playlistNames = playOrder.map { loadedList[it].source.displayName }.toTypedArray()
                totalPlaylistMs = playlistDurations.sum()
                LogStore.event(
                    "Playlist total duration: ${totalPlaylistMs / 60000} min, " +
                        "policy=${config.stopPolicy}" +
                        (if (config.stopPolicy == StopPolicy.STOP_AFTER_DURATION)
                            " after ${config.sessionDurationHours}h" else "")
                )
            }

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
                // Retry Now is one-shot: clear it once a new attempt begins.
                consumeRetryNow()
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

                val useHw = when (config.encoderPref) {
                    com.videolive.app.model.EncoderPref.SOFTWARE -> false
                    com.videolive.app.model.EncoderPref.HARDWARE -> hwPresent
                    com.videolive.app.model.EncoderPref.AUTO -> hwPresent && !forceSoftware
                }
                attemptUsedHw = useHw
                LogStore.event(
                    "Encoder selected for this attempt: " +
                        if (useHw) "h264_mediacodec (hardware)" else "libx264 (software)"
                )

                val args: List<String> = if (playlistMode) {
                    // Concat demuxer list for this session's play order. The
                    // list file lives next to the cache copies; it is rebuilt
                    // on every attempt so a reconnect replays from the start
                    // of the ordered list.
                    val concatFile = File(cacheDir, "playlist_concat.txt")
                    withContext(Dispatchers.IO) {
                        concatFile.writeText(buildString {
                            playOrder.forEach { i ->
                                append("file '").append(loadedList[i].source.ffmpegInput).append("'\n")
                            }
                        })
                    }
                    FFmpegCommandBuilder.buildPlaylist(
                        config,
                        info.hasAudio,
                        concatFile,
                        destination,
                        if (micActive) pipePath else null,
                        useHw
                    )
                } else {
                    FFmpegCommandBuilder.build(
                        config,
                        info,
                        loaded.source.ffmpegInput,
                        destination,
                        if (micActive) pipePath else null,
                        useHw
                    )
                }
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
                prevStatFrames = 0
                prevStatTimeMs = 0
                measuredFps = 0f
                softStallWarned = false
                overloadStreak = 0
                overloadWarned = false
                prevBytes = -1L
                prevBytesFrames = 0L
                transportStallStreak = 0
                attemptWallStartMs = 0L
                driftWarned = false
                driftCritical = false
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
                    // Verified encoder: parsed from the REAL output mapping
                    // line ("Video: h264 (h264_mediacodec)" / "(libx264)").
                    if (encoderInUse.isEmpty() && l.contains("video:")) {
                        val name = when {
                            l.contains("h264_mediacodec") -> "h264_mediacodec (hardware)"
                            l.contains("libx264") -> "libx264 (software)"
                            else -> ""
                        }
                        if (name.isNotEmpty()) {
                            encoderInUse = name
                            LogStore.event("Encoder in use (verified from output): $name")
                            post { s -> s.copy(encoderName = name) }
                        }
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

                    // Measured output cadence (independent of FFmpeg's own fps
                    // field): frame delta over out_time delta.
                    val statFrames = st.videoFrameNumber.toLong()
                    val statTimeMs = st.time.toLong()
                    if (prevStatTimeMs > 0 && statTimeMs > prevStatTimeMs) {
                        val dt = statTimeMs - prevStatTimeMs
                        if (dt >= 500) {
                            measuredFps = (statFrames - prevStatFrames) * 1000f / dt
                            prevStatFrames = statFrames
                            prevStatTimeMs = statTimeMs
                        }
                    } else if (prevStatTimeMs == 0L) {
                        prevStatFrames = statFrames
                        prevStatTimeMs = statTimeMs
                    }

                    // Transport check on the same window: encoded frames must
                    // produce outgoing bytes (FLV over RTMPS). Frames advance
                    // with a frozen byte counter = writes blocked/disconnected.
                    val outBytes = st.size.toLong()
                    if (prevBytes >= 0) {
                        val framesAdvanced = statFrames > prevBytesFrames
                        val bytesAdvanced = outBytes > prevBytes
                        transportStallStreak =
                            if (framesAdvanced && !bytesAdvanced) transportStallStreak + 1 else 0
                        if (transportStallStreak == 2) {
                            LogStore.event(
                                "TRANSPORT STALL suspected: frames encoding but output bytes " +
                                    "frozen at $outBytes — RTMPS write blocked or disconnected"
                            )
                        }
                    }
                    prevBytes = outBytes
                    prevBytesFrames = statFrames

                    // Cumulative latency drift — the long-duration lag signal.
                    // A healthy real-time pipeline keeps out_time close to
                    // wall-clock elapsed; growing drift means encode or
                    // transport is under real time and stutter/latency will
                    // only get worse the longer the session runs.
                    val driftSec: Double = if (attemptWallStartMs == 0L) {
                        attemptWallStartMs = System.currentTimeMillis()
                        0.0
                    } else {
                        ((System.currentTimeMillis() - attemptWallStartMs) - statTimeMs) / 1000.0
                    }
                    if (driftSec >= 15.0 && !driftWarned) {
                        driftWarned = true
                        LogStore.event(
                            "LATENCY DRIFT growing: output ${String.format(Locale.US, "%.0f", driftSec)}s " +
                                "behind real time — encode/transport slightly under 1.0x; " +
                                "long sessions from here will progressively degrade"
                        )
                    }
                    if (driftSec >= 45.0 && !driftCritical) {
                        driftCritical = true
                        post { s ->
                            s.copy(
                                perfWarning = "Stream falling behind real time — stop and restart " +
                                    "at 480p or lower FPS for long sessions."
                            )
                        }
                        LogStore.event(
                            "LATENCY DRIFT severe: ${String.format(Locale.US, "%.0f", driftSec)}s behind " +
                                "real time. Continuing will keep increasing latency/stutter. " +
                                "Recommendation: stop now, lower quality (480p) or FPS, restart."
                        )
                    }

                    val healthLabel = when {
                        transportStallStreak >= 2 -> "Transport stalled"
                        driftCritical || overloadWarned ||
                            (measuredFps > 0f && measuredFps < config.fps * 0.8f) ->
                            "Performance warning"
                        else -> "Healthy"
                    }
                    val loops: Int
                    var itemName = config.videoName
                    if (playlistMode && totalPlaylistMs > 0) {
                        val loopAll = config.loopMode == LoopMode.ALL
                        val pos = if (loopAll) st.time % totalPlaylistMs else st.time
                        loops = if (loopAll) (st.time / totalPlaylistMs).toInt() else 0
                        var cum = 0L
                        var idx = playlistDurations.size - 1
                        for (k in playlistDurations.indices) {
                            cum += playlistDurations[k]
                            if (pos < cum) {
                                idx = k
                                break
                            }
                        }
                        itemName = playlistNames[idx]
                    } else {
                        loops = if (info.durationMs > 0) (st.time / info.durationMs).toInt() else 0
                    }

                    // Encode-overload detection (several signals, sustained):
                    // measured cadence well below target WHILE encode speed is
                    // under realtime means the device cannot keep up — report
                    // it, never silently "fix" by restarting a healthy stream.
                    if (measuredFps > 0f && st.speed > 0.0 &&
                        measuredFps < config.fps * 0.8f && st.speed < 1.0
                    ) {
                        overloadStreak++
                    } else {
                        overloadStreak = 0
                    }
                    if (overloadStreak >= 10 && !overloadWarned) {
                        overloadWarned = true
                        post { s ->
                            s.copy(
                                perfWarning = "Encoding below real time — use 480p or a " +
                                    "lower FPS for stable streaming on this device."
                            )
                        }
                        LogStore.event(
                            "ENCODE OVERLOAD suspected: measured " +
                                "${String.format(Locale.US, "%.1f", measuredFps)} fps vs target " +
                                "${config.fps}, speed ${String.format(Locale.US, "%.2f", st.speed)}x, " +
                                "temp ${String.format(Locale.US, "%.1f", deviceTempC)}C. " +
                                "This device cannot sustain ${config.quality.label}@${config.fps}fps — " +
                                "for long sessions use 480p or 30 fps, and close heavy " +
                                "background apps."
                        )
                    }

                    // Phase 3: session duration limit — safe stop, never a crash.
                    if (!durationReached && config.stopPolicy == StopPolicy.STOP_AFTER_DURATION &&
                        config.sessionDurationHours > 0 &&
                        st.time >= config.sessionDurationHours * 3600_000L
                    ) {
                        durationReached = true
                        LogStore.event(
                            "Session duration limit reached (${config.sessionDurationHours}h) — stopping safely"
                        )
                        stopRequested = true
                        MicMixer.stop()
                        ffmpeg.cancelCurrent()
                    }

                    // Phase 7: thermal guard on every stats tick.
                    checkThermal()

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
                                networkOk = netOk,
                                videoName = config.videoName,
                                playlistPosition = if (playlistMode) itemName else "",
                                micLevelPct = MicMixer.levelPct,
                                deviceTempC = deviceTempC,
                                health = healthLabel
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
                            // Periodic diagnostic report (Advanced Logs).
                            LogStore.event(
                                "DIAG: target ${config.fps} fps | measured " +
                                    "${String.format(Locale.US, "%.1f", measuredFps)} fps | " +
                                    "engine ${st.videoFps} fps | bitrate ${st.bitrate.toInt()} kbps " +
                                    "(target ${config.targetBitrateKbps()}) | speed " +
                                    "${String.format(Locale.US, "%.2f", st.speed)}x | " +
                                    "net=${if (netOk) "OK" else "POOR"} | reconnects=$attempt | " +
                                    "temp=${String.format(Locale.US, "%.1f", deviceTempC)}C | " +
                                    "drift=${String.format(Locale.US, "%.0f", driftSec)}s | " +
                                    "frame #${st.videoFrameNumber}, out_time ${st.time} ms, " +
                                    "total ${st.size} bytes, loop ${loops + 1}"
                            )
                        }
                        post { s ->
                            if (s.phase == Phase.STOPPING || s.phase == Phase.ERROR) s
                            else {
                                if (!netOk && s.networkOk) {
                                    LogStore.event("Network condition: POOR — monitoring connection")
                                }
                                if (netOk && !s.networkOk) {
                                    LogStore.event("Connection restored")
                                }
                                s.copy(
                                    phase = Phase.STREAMING,
                                    statusText = when {
                                        !netOk -> "Network poor — monitoring..."
                                        !s.networkOk -> "Connection restored"
                                        else -> "Sending video to YouTube..."
                                    },
                                    liveFps = st.videoFps,
                                    measuredFps = measuredFps,
                                    liveBitrateKbps = st.bitrate.toInt(),
                                    speed = st.speed,
                                    loopCount = loops,
                                    networkOk = netOk,
                                    playlistPosition = if (playlistMode) itemName else "",
                                    micLevelPct = MicMixer.levelPct,
                                    deviceTempC = deviceTempC,
                                    health = healthLabel
                                )
                            }
                        }
                        if (!liveNotified) {
                            liveNotified = true
                            refreshNotification(config.videoName, live = true)
                        }
                    }
                }
                watchdog.cancel()

                if (stopRequested) break

                if (thermalCritFired) {
                    // failNow already posted ERROR and the engine was cancelled.
                    break
                }

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
                        if (playlistMode && config.loopMode != LoopMode.ALL) {
                            // Sequential/Shuffle playlists finish the list and the
                            // concat demuxer exits cleanly — that is the intended
                            // end of the session, not a failure.
                            LogStore.event("Playlist finished after ${ranSeconds}s — ending session")
                            post {
                                it.copy(
                                    phase = Phase.STOPPED,
                                    statusText = "Playlist finished — stream ended",
                                    elapsedMs = System.currentTimeMillis() - startedAt
                                )
                            }
                            return
                        }
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
                        if (attemptUsedHw && ranSeconds < 20 && !forceSoftware) {
                            forceSoftware = true
                            LogStore.event(
                                "Hardware encoder stalled early (${ranSeconds}s) — " +
                                    "falling back to libx264 (software)."
                            )
                        }
                        // Overload is not a transient fault: retrying the same
                        // configuration would loop forever. Stop honestly and
                        // recommend a sustainable configuration.
                        if (overloadWarned && !attemptUsedHw) {
                            failNow(
                                "Encoding overload: this device cannot sustain " +
                                    "${config.quality.label} @ ${config.fps} fps in real time. " +
                                    "Restart with 480p or a lower FPS for a stable stream."
                            )
                            return
                        }
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
                        post { it.copy(lastError = err.userMessage) }
                        if (attemptUsedHw && ranSeconds < 20 && !forceSoftware) {
                            forceSoftware = true
                            LogStore.event(
                                "Hardware encoder attempt failed after ${ranSeconds}s — " +
                                    "falling back to libx264 (software). Reason: ${err.userMessage}"
                            )
                        }
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

            if (!thermalCritFired) {
                val stopText = when {
                    durationReached -> "Duration limit reached — stream stopped safely"
                    else -> "Live ended"
                }
                post { it.copy(phase = Phase.STOPPED, statusText = stopText) }
                LogStore.event("Stream stopped cleanly")
            }
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
                // Soft stall: warn with ALL available signals before taking
                // any action (avoids false positives from short fluctuations).
                if (idleMs > 10_000 && !softStallWarned) {
                    softStallWarned = true
                    LogStore.event(
                        "STALL WATCH: no progress ${idleMs / 1000}s | signals: " +
                            "measured ${String.format(Locale.US, "%.1f", measuredFps)} fps, " +
                            "temp ${String.format(Locale.US, "%.1f", deviceTempC)}C, " +
                            "net=${if (Net.isOnline(this@StreamService)) "OK" else "DOWN"}, " +
                            "mic=${if (MicMixer.levelPct > 0) "active" else "idle"} — monitoring"
                    )
                }
                if (idleMs > WATCHDOG_STALL_MS) {
                    LogStore.event(
                        "STALL CONFIRMED: no encode progress for ${idleMs / 1000}s " +
                            "(measured ${String.format(Locale.US, "%.1f", measuredFps)} fps, " +
                            "temp ${String.format(Locale.US, "%.1f", deviceTempC)}C, " +
                            "net=${if (Net.isOnline(this@StreamService)) "OK" else "DOWN"}) " +
                            "— restarting engine"
                    )
                    ffmpeg.cancelCurrent()
                    return
                }
            }
        }
    }

    private fun failNow(message: String) {
        post {
            it.copy(
                phase = Phase.ERROR,
                statusText = message,
                errorText = message,
                lastError = message
            )
        }
        LogStore.event("ERROR: $message")
    }

    // ---- Phase 7: thermal guard --------------------------------------------
    // Temperature comes from the battery sensor (ACTION_BATTERY_CHANGED), in
    // tenths of a degree. Thresholds are user-configurable in Settings; no
    // values are invented here.

    private fun startThermalMonitor() {
        if (batteryReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    if (tenths > 0) deviceTempC = tenths / 10.0
                }
            }
            registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = receiver
        } catch (t: Throwable) {
            LogStore.event("Thermal monitor unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun stopThermalMonitor() {
        val r = batteryReceiver
        if (r != null) {
            try {
                unregisterReceiver(r)
            } catch (_: Throwable) {
            }
            batteryReceiver = null
        }
    }

    private fun checkThermal() {
        val temp = deviceTempC
        if (temp <= 0.0) return
        val warnC = settingsRepo.thermalWarnC
        val critC = settingsRepo.thermalCritC.coerceAtLeast(warnC + 1)
        if (temp >= critC && !thermalCritFired) {
            thermalCritFired = true
            LogStore.event(
                "Device temperature critical (${String.format(Locale.US, "%.1f", temp)}°C ≥ ${critC}°C) — " +
                    "safe stop to protect the device"
            )
            failNow("Phone temperature critical — stream stopped to protect the device.")
            stopRequested = true
            MicMixer.stop()
            ffmpeg.cancelCurrent()
        } else if (temp >= warnC && !thermalWarnShown) {
            thermalWarnShown = true
            LogStore.event(
                "Device temperature high (${String.format(Locale.US, "%.1f", temp)}°C ≥ ${warnC}°C) — " +
                    "monitoring; safe stop if it reaches ${critC}°C"
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var renewals = 0
            while (isActive) {
                post { it.copy(elapsedMs = System.currentTimeMillis() - startedAt) }
                // Renew the partial wake lock well before its hard cap expires,
                // otherwise the CPU may sleep mid-encode on long sessions.
                val wl = wakeLock
                if (wl != null && !wl.isHeld && isStreaming) {
                    try {
                        wl.acquire(12 * 60 * 60 * 1000L)
                        renewals++
                        LogStore.event("WakeLock renewed (#$renewals) for long session")
                    } catch (t: Throwable) {
                    }
                }
                delay(1000)
            }
        }
    }

    private fun cleanup(pipePath: String?) {
        isStreaming = false
        tickerJob?.cancel()
        stopThermalMonitor()
        // Truthful terminal notification: detach the foreground service but
        // leave a dismissible, accurate end-state notification instead of a
        // stale "LIVE" badge.
        val terminal = when (state.phase) {
            Phase.ERROR -> "STREAM FAILED — ${state.errorText ?: "see Advanced Logs"}"
            Phase.STOPPING -> "STREAM STOPPED"
            else -> "LIVE ENDED — ${state.statusText.ifEmpty { "stream stopped" }}"
        }
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(terminal, live = false, terminal = true))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        } catch (t: Throwable) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
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
        stopSelf()
    }

    /** If the system swipes the app away while idle, make sure nothing stale remains. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isStreaming) {
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIFICATION_ID)
            } catch (_: Throwable) {
            }
            stopSelf()
        }
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

    /** Backoff delay that exits promptly when STOP LIVE or Retry Now is pressed. */
    private suspend fun stoppableDelay(ms: Long) {
        var left = ms
        while (left > 0 && !stopRequested && !Companion.retryNowPending()) {
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

    private fun post(transform: (StreamUiState) -> StreamUiState) {
        // Single source of truth: companion StateFlow (UI) + instance mirror
        // (service-side decisions such as the terminal notification).
        val next = transform(uiState.value)
        uiState.value = next
        state = next
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

    private fun buildNotification(
        videoName: String,
        live: Boolean = false,
        terminal: Boolean = false
    ): Notification {
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
        return if (terminal) {
            // End-state notification: dismissible, no chronometer, no Stop
            // action — never claims LIVE after the work is done.
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("LIVE VIP")
                .setContentText(videoName)
                .setStyle(NotificationCompat.BigTextStyle().bigText(videoName))
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openPi)
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
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
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
