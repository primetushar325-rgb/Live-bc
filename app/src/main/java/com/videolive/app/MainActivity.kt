package com.videolive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.videolive.app.data.PlaylistRepository
import com.videolive.app.data.SecurePrefs
import com.videolive.app.data.SettingsRepository
import com.videolive.app.data.VideoRepository
import com.videolive.app.ffmpeg.FFmpegCommandBuilder
import com.videolive.app.ffmpeg.FFmpegRuntime
import com.videolive.app.media.LoadedVideo
import com.videolive.app.media.VideoInputException
import com.videolive.app.media.VideoLoader
import com.videolive.app.model.BitrateMode
import com.videolive.app.model.FrameMode
import com.videolive.app.model.LoopMode
import com.videolive.app.model.StopPolicy
import com.videolive.app.net.RtmpProbe
import com.videolive.app.model.Orientation
import com.videolive.app.model.Quality
import com.videolive.app.model.StreamConfig
import com.videolive.app.model.VideoInfo
import com.videolive.app.stream.StreamService
import com.videolive.app.util.DeviceCaps
import com.videolive.app.util.Net
import com.videolive.app.util.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var settingsRepo: SettingsRepository

    private var keyVisible = false
    private var suppressMicListener = false
    private var bitrateSelectionRestored = false

    /** Guards START LIVE: one preparation/stream start at a time. */
    @Volatile
    private var startInProgress = false

    private lateinit var batteryWarning: TextView
    private lateinit var liveBanner: TextView
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewVideo: VideoView
    private lateinit var previewThumb: ImageView
    private lateinit var emptyPreview: LinearLayout
    private lateinit var previewStatus: TextView
    private lateinit var videoInfoBlock: LinearLayout
    private lateinit var txtFileName: TextView
    private lateinit var txtMeta: TextView
    private lateinit var txtAudioPresent: TextView
    private lateinit var switchFullUrl: SwitchCompat
    private lateinit var serverKeyGroup: LinearLayout
    private lateinit var fullUrlGroup: LinearLayout
    private lateinit var etServerUrl: EditText
    private lateinit var etStreamKey: EditText
    private lateinit var etFullUrl: EditText
    private lateinit var btnKeyEye: ImageButton
    private lateinit var optVertical: LinearLayout
    private lateinit var optHorizontal: LinearLayout
    private lateinit var spinnerBitrate: Spinner
    private lateinit var etBitrate: EditText
    private lateinit var seekVolume: SeekBar
    private lateinit var txtVolume: TextView
    private lateinit var switchMic: SwitchCompat
    private lateinit var optLoopOne: LinearLayout
    private lateinit var optLoopAll: LinearLayout

    // Phase 3-5 UI.
    private lateinit var micVolumeRow: LinearLayout
    private lateinit var seekMicVolume: SeekBar
    private lateinit var txtMicVolume: TextView
    private lateinit var txtPlaylistSummary: TextView
    private lateinit var chipFit: TextView
    private lateinit var chipFill: TextView
    private lateinit var seekZoom: SeekBar
    private lateinit var txtZoom: TextView
    private lateinit var seekPanX: SeekBar
    private lateinit var txtPanX: TextView
    private lateinit var seekPanY: SeekBar
    private lateinit var txtPanY: TextView

    private val qualityChips = mutableMapOf<Quality, TextView>()
    private val fpsChips = mutableMapOf<Int, TextView>()

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onVideoPicked(uri)
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                suppressMicListener = true
                switchMic.isChecked = false
                suppressMicListener = false
                settingsRepo.micOn = false
                toast("Microphone permission denied")
            } else {
                settingsRepo.micOn = true
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsRepo = SettingsRepository(this)

        batteryWarning = findViewById(R.id.batteryWarning)
        liveBanner = findViewById(R.id.liveBanner)
        previewFrame = findViewById(R.id.previewFrame)
        previewVideo = findViewById(R.id.previewVideo)
        previewThumb = findViewById(R.id.previewThumb)
        emptyPreview = findViewById(R.id.emptyPreview)
        previewStatus = findViewById(R.id.previewStatus)
        videoInfoBlock = findViewById(R.id.videoInfoBlock)
        txtFileName = findViewById(R.id.txtFileName)
        txtMeta = findViewById(R.id.txtMeta)
        txtAudioPresent = findViewById(R.id.txtAudioPresent)
        switchFullUrl = findViewById(R.id.switchFullUrl)
        serverKeyGroup = findViewById(R.id.serverKeyGroup)
        fullUrlGroup = findViewById(R.id.fullUrlGroup)
        etServerUrl = findViewById(R.id.etServerUrl)
        etStreamKey = findViewById(R.id.etStreamKey)
        etFullUrl = findViewById(R.id.etFullUrl)
        btnKeyEye = findViewById(R.id.btnKeyEye)
        optVertical = findViewById(R.id.optVertical)
        optHorizontal = findViewById(R.id.optHorizontal)
        spinnerBitrate = findViewById(R.id.spinnerBitrate)
        etBitrate = findViewById(R.id.etBitrate)
        seekVolume = findViewById(R.id.seekVolume)
        txtVolume = findViewById(R.id.txtVolume)
        switchMic = findViewById(R.id.switchMic)
        optLoopOne = findViewById(R.id.optLoopOne)
        optLoopAll = findViewById(R.id.optLoopAll)
        micVolumeRow = findViewById(R.id.micVolumeRow)
        seekMicVolume = findViewById(R.id.seekMicVolume)
        txtMicVolume = findViewById(R.id.txtMicVolume)
        txtPlaylistSummary = findViewById(R.id.txtPlaylistSummary)
        chipFit = findViewById(R.id.chipFit)
        chipFill = findViewById(R.id.chipFill)
        seekZoom = findViewById(R.id.seekZoom)
        txtZoom = findViewById(R.id.txtZoom)
        seekPanX = findViewById(R.id.seekPanX)
        txtPanX = findViewById(R.id.txtPanX)
        seekPanY = findViewById(R.id.seekPanY)
        txtPanY = findViewById(R.id.txtPanY)

        qualityChips[Quality.Q360] = findViewById(R.id.q360)
        qualityChips[Quality.Q480] = findViewById(R.id.q480)
        qualityChips[Quality.Q720] = findViewById(R.id.q720)
        qualityChips[Quality.Q1080] = findViewById(R.id.q1080)
        fpsChips[24] = findViewById(R.id.fps24)
        fpsChips[25] = findViewById(R.id.fps25)
        fpsChips[30] = findViewById(R.id.fps30)
        fpsChips[50] = findViewById(R.id.fps50)
        fpsChips[60] = findViewById(R.id.fps60)

        wireEvents()
        restoreSettings()
        // Warm up the streaming engine in the background: loads the native
        // FFmpeg libraries and runs the -version execution test so the result
        // (and the real reason if it fails) is in Advanced Logs immediately.
        lifecycleScope.launch(Dispatchers.IO) {
            FFmpegRuntime.verify(this@MainActivity)
        }
        // Clean up leftover video cache files from crashed/killed sessions.
        // Never touches an in-progress stream.
        lifecycleScope.launch(Dispatchers.IO) {
            if (!StreamService.isStreaming) {
                com.videolive.app.media.VideoLoader.purgeStaleCache(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
        liveBanner.visibility = if (StreamService.isStreaming) View.VISIBLE else View.GONE
        refreshPlaylistSummary()
        askNotificationPermissionIfNeeded()
        if (VideoRepository.current == null) {
            settingsRepo.videoUri?.let { reloadSavedVideo(Uri.parse(it)) }
        } else {
            renderVideoCard(VideoRepository.current!!)
        }
    }

    override fun onPause() {
        super.onPause()
        if (previewVideo.visibility == View.VISIBLE) {
            stopPreview()
        }
    }

    private fun wireEvents() {
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnChoose).setOnClickListener {
            pickVideo.launch(
                arrayOf(
                    "video/*",
                    "application/octet-stream",
                    "video/x-matroska",
                    "application/x-matroska"
                )
            )
        }

        previewThumb.setOnClickListener { startPreview() }
        previewVideo.setOnCompletionListener { stopPreview() }

        switchFullUrl.setOnCheckedChangeListener { _, checked ->
            settingsRepo.fullUrlMode = checked
            serverKeyGroup.visibility = if (checked) View.GONE else View.VISIBLE
            fullUrlGroup.visibility = if (checked) View.VISIBLE else View.GONE
        }

        btnKeyEye.setOnClickListener {
            keyVisible = !keyVisible
            etStreamKey.inputType = if (keyVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etStreamKey.setSelection(etStreamKey.text.length)
            btnKeyEye.setImageResource(if (keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
        }

        optVertical.setOnClickListener { selectOrientation(Orientation.VERTICAL) }
        optHorizontal.setOnClickListener { selectOrientation(Orientation.HORIZONTAL) }

        qualityChips.forEach { (q, view) -> view.setOnClickListener { selectQuality(q) } }
        fpsChips.forEach { (fps, view) -> view.setOnClickListener { selectFps(fps) } }

        spinnerBitrate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Auto", "Manual")
        )
        spinnerBitrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val manual = pos == 1
                settingsRepo.bitrateMode = if (manual) BitrateMode.MANUAL else BitrateMode.AUTO
                etBitrate.visibility = if (manual) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                txtVolume.text = "$progress%"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                settingsRepo.videoVolumePct = sb?.progress ?: 100
            }
        })

        switchMic.setOnCheckedChangeListener { _, checked ->
            if (suppressMicListener) return@setOnCheckedChangeListener
            if (checked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    settingsRepo.micOn = true
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                settingsRepo.micOn = false
            }
            micVolumeRow.visibility = if (switchMic.isChecked) View.VISIBLE else View.GONE
        }

        optLoopOne.setOnClickListener { selectLoop(LoopMode.ONE) }
        optLoopAll.setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
        }

        // Phase 3/8 managers.
        findViewById<TextView>(R.id.btnPlaylist).setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
        }
        findViewById<TextView>(R.id.btnDestinations).setOnClickListener {
            startActivity(Intent(this, DestinationsActivity::class.java))
        }

        // Phase 5: independent mic gain.
        micVolumeRow.visibility = if (switchMic.isChecked) View.VISIBLE else View.GONE
        seekMicVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                txtMicVolume.text = "$progress%"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                settingsRepo.micVolumePct = sb?.progress ?: 100
            }
        })

        // Phase 4: framing controls (applied to the encoder at stream start).
        chipFit.setOnClickListener { selectFrameMode(FrameMode.FIT) }
        chipFill.setOnClickListener { selectFrameMode(FrameMode.FILL) }
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                txtZoom.text = "${progress + 100}%"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                settingsRepo.zoomPct = (sb?.progress ?: 0) + 100
            }
        })
        seekPanX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                txtPanX.text = "${progress - 100}"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                settingsRepo.panXPct = (sb?.progress ?: 100) - 100
            }
        })
        seekPanY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                txtPanY.text = "${progress - 100}"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                settingsRepo.panYPct = (sb?.progress ?: 100) - 100
            }
        })
        findViewById<TextView>(R.id.btnResetFraming).setOnClickListener {
            selectFrameMode(FrameMode.FIT)
            settingsRepo.zoomPct = 100
            settingsRepo.panXPct = 0
            settingsRepo.panYPct = 0
            renderFramingControls()
            toast("Framing reset")
        }

        findViewById<TextView>(R.id.btnStartLive).setOnClickListener { validateAndStart() }
        findViewById<TextView>(R.id.btnTestConnection).setOnClickListener {
            runDestinationTest()
        }
        liveBanner.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        batteryWarning.setOnClickListener { requestBatteryExemption() }
    }

    private fun restoreSettings() {
        etServerUrl.setText(settingsRepo.serverUrl)
        etFullUrl.setText(settingsRepo.fullUrl)
        etStreamKey.setText(SecurePrefs.getStreamKey(this).orEmpty())
        switchFullUrl.isChecked = settingsRepo.fullUrlMode
        serverKeyGroup.visibility = if (settingsRepo.fullUrlMode) View.GONE else View.VISIBLE
        fullUrlGroup.visibility = if (settingsRepo.fullUrlMode) View.VISIBLE else View.GONE

        selectOrientation(settingsRepo.orientation)
        selectQuality(settingsRepo.quality)
        selectFps(settingsRepo.fps)

        val savedVolume = settingsRepo.videoVolumePct
        seekVolume.progress = savedVolume
        txtVolume.text = "$savedVolume%"

        suppressMicListener = true
        switchMic.isChecked = settingsRepo.micOn
        suppressMicListener = false
        micVolumeRow.visibility = if (settingsRepo.micOn) View.VISIBLE else View.GONE
        val savedMicVol = settingsRepo.micVolumePct
        seekMicVolume.progress = savedMicVol
        txtMicVolume.text = "$savedMicVol%"

        renderFramingControls()

        selectLoop(LoopMode.ONE)

        etBitrate.setText(settingsRepo.manualBitrateKbps.toString())
        val manual = settingsRepo.bitrateMode == BitrateMode.MANUAL
        spinnerBitrate.setSelection(if (manual) 1 else 0)
        etBitrate.visibility = if (manual) View.VISIBLE else View.GONE
        bitrateSelectionRestored = true
    }

    private fun selectOrientation(o: Orientation) {
        settingsRepo.orientation = o
        optVertical.isSelected = o == Orientation.VERTICAL
        optHorizontal.isSelected = o == Orientation.HORIZONTAL
    }

    private fun selectQuality(q: Quality) {
        settingsRepo.quality = q
        qualityChips.forEach { (k, v) -> v.isSelected = k == q }
    }

    private fun selectFps(fps: Int) {
        settingsRepo.fps = fps
        fpsChips.forEach { (k, v) -> v.isSelected = k == fps }
    }

    private fun selectLoop(mode: LoopMode) {
        settingsRepo.loopMode = mode
        optLoopOne.isSelected = mode == LoopMode.ONE
        optLoopAll.isSelected = mode == LoopMode.ALL
    }

    private fun selectFrameMode(mode: FrameMode) {
        settingsRepo.frameMode = mode
        chipFit.isSelected = mode == FrameMode.FIT
        chipFill.isSelected = mode == FrameMode.FILL
    }

    private fun renderFramingControls() {
        selectFrameMode(settingsRepo.frameMode)
        val zoom = settingsRepo.zoomPct.coerceIn(100, 300)
        seekZoom.progress = zoom - 100
        txtZoom.text = "$zoom%"
        val px = settingsRepo.panXPct.coerceIn(-100, 100)
        seekPanX.progress = px + 100
        txtPanX.text = "$px"
        val py = settingsRepo.panYPct.coerceIn(-100, 100)
        seekPanY.progress = py + 100
        txtPanY.text = "$py"
    }

    /** Summary line under the Playlist Mode card. */
    private fun refreshPlaylistSummary() {
        val pl = PlaylistRepository.load(this)
        txtPlaylistSummary.text = if (pl != null && pl.items.size >= 2) {
            val mins = pl.items.sumOf { it.durationMs } / 60000
            val modeLabel = when (pl.loopMode) {
                LoopMode.ALL -> "Loop All"
                LoopMode.SEQUENTIAL -> "Sequential"
                LoopMode.SHUFFLE -> "Shuffle"
                else -> "Loop One"
            }
            "${pl.items.size} videos • $modeLabel • ~${mins} min per pass" +
                (if (pl.stopPolicy == StopPolicy.STOP_AFTER_DURATION)
                    " • stops after ${pl.sessionDurationHours}h" else "")
        } else {
            getString(R.string.loop_all_sub)
        }
    }

    private fun onVideoPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
        settingsRepo.videoUri = uri.toString()
        loadVideo(uri)
    }

    private fun reloadSavedVideo(uri: Uri) {
        loadVideo(uri)
    }

    private fun loadVideo(uri: Uri) {
        // Already prepared for exactly this Uri (screen rotation, returning from
        // the Live/Settings screens, app resume): reuse it — never re-copy.
        val alreadyLoaded = VideoRepository.current
        if (alreadyLoaded != null && VideoRepository.currentUri == uri) {
            renderVideoCard(alreadyLoaded)
            return
        }
        stopPreview()
        previewStatus.text = getString(R.string.analyzing_video)
        emptyPreview.visibility = View.VISIBLE
        previewThumb.visibility = View.GONE
        videoInfoBlock.visibility = View.GONE
        VideoRepository.current = null

        lifecycleScope.launch {
            // Phase 1 — validate the content:// Uri and read platform metadata.
            // Fast, and it makes the card appear immediately.
            val inspection = try {
                withContext(Dispatchers.IO) { VideoLoader.inspect(this@MainActivity, uri) }
            } catch (e: VideoInputException) {
                showPickError(e.message ?: "Unable to read this video.")
                return@launch
            } catch (t: Throwable) {
                showPickError("Unable to read this video.")
                return@launch
            }
            renderQuickCard(inspection)

            // Phase 2 — byte-level bridge: complete copy of the video into the
            // private FFmpeg cache + real FFprobe verification of that file.
            // Single-flight: if a preparation is already running (e.g. START
            // LIVE tapped during selection), it is reused, never duplicated.
            previewStatus.text = "Preparing video..."
            val loaded = try {
                VideoLoader.prepareOnce(this@MainActivity, uri, inspection) { copied, total ->
                    val text = if (total > 0) {
                        "Copying video ${((copied * 100) / total).toInt()}% " +
                            "(${Texts.formatSize(copied)} / ${Texts.formatSize(total)})"
                    } else {
                        "Copying video ${Texts.formatSize(copied)}"
                    }
                    runOnUiThread {
                        if (VideoRepository.current == null) previewStatus.text = text
                    }
                }
            } catch (e: VideoInputException) {
                showPickError(e.message ?: "Unable to read this video.")
                return@launch
            } catch (t: Throwable) {
                showPickError("Unable to read this video.")
                return@launch
            }
            VideoRepository.current = loaded
            renderVideoCard(loaded)
        }
    }

    private fun showPickError(message: String) {
        settingsRepo.videoUri = null
        VideoRepository.current = null
        toast(message)
        previewStatus.text = message
        emptyPreview.visibility = View.VISIBLE
        previewThumb.visibility = View.GONE
        videoInfoBlock.visibility = View.GONE
    }

    private fun renderQuickCard(inspection: VideoLoader.Inspection) {
        val info = inspection.platformInfo ?: return
        txtFileName.text = inspection.displayName
        txtMeta.text = buildMetaLine(info, inspection.sizeBytes)
        txtAudioPresent.text = "Audio: checking…"
        videoInfoBlock.visibility = View.VISIBLE
        emptyPreview.visibility = View.GONE
        previewThumb.visibility = View.GONE

        val uriString = settingsRepo.videoUri ?: return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadThumbnail(Uri.parse(uriString)) }
            if (bitmap != null && VideoRepository.current == null) {
                previewThumb.setImageBitmap(bitmap)
                previewThumb.visibility = View.VISIBLE
            }
        }
    }

    private fun buildMetaLine(info: VideoInfo, sizeBytes: Long): String {
        val fpsPart = if (info.fps > 0) "${info.fps} fps" else "fps —"
        val sizePart = Texts.formatSize(if (sizeBytes > 0) sizeBytes else info.sizeBytes)
        return listOf(
            Texts.formatDuration(info.durationMs),
            "${info.width}x${info.height}",
            fpsPart,
            sizePart
        ).joinToString("  •  ")
    }

    private fun renderVideoCard(loaded: LoadedVideo) {
        val info = loaded.info
        txtFileName.text = loaded.source.displayName
        txtMeta.text = buildMetaLine(info, loaded.source.sizeBytes)
        txtAudioPresent.text = if (info.hasAudio) {
            "Audio: available${if (info.audioCodec.isNotEmpty()) " (${info.audioCodec})" else ""}"
        } else {
            "Audio: none — a silent track will be added for YouTube"
        }
        videoInfoBlock.visibility = View.VISIBLE
        emptyPreview.visibility = View.GONE
        previewThumb.visibility = View.GONE

        val uriString = settingsRepo.videoUri ?: return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadThumbnail(Uri.parse(uriString)) }
            if (bitmap != null && VideoRepository.current === loaded) {
                previewThumb.setImageBitmap(bitmap)
                previewThumb.visibility = View.VISIBLE
            }
        }
    }

    private fun loadThumbnail(uri: Uri): Bitmap? = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(this, uri)
        val bmp = mmr.frameAtTime
        mmr.release()
        bmp
    } catch (t: Throwable) {
        null
    }

    private fun startPreview() {
        val uriString = settingsRepo.videoUri ?: return
        previewThumb.visibility = View.GONE
        emptyPreview.visibility = View.GONE
        previewVideo.visibility = View.VISIBLE
        try {
            previewVideo.setVideoURI(Uri.parse(uriString))
            val controller = MediaController(this)
            controller.setAnchorView(previewFrame)
            previewVideo.setMediaController(controller)
            previewVideo.start()
        } catch (t: Throwable) {
            toast("Preview not available for this video.")
            stopPreview()
        }
    }

    private fun stopPreview() {
        try {
            previewVideo.stopPlayback()
        } catch (_: Throwable) {
        }
        previewVideo.visibility = View.GONE
        if (VideoRepository.current != null && previewThumb.drawable != null) {
            previewThumb.visibility = View.VISIBLE
        } else {
            emptyPreview.visibility = View.VISIBLE
            previewStatus.text = getString(R.string.no_video_selected)
        }
    }

    /**
     * Idempotent START LIVE entry point. Rapid taps cannot create duplicate
     * sessions: while a start is being prepared or a stream is running, every
     * additional tap is rejected.
     */
    private fun validateAndStart() {
        if (StreamService.isStreaming) {
            toast("A stream is already running.")
            return
        }
        if (startInProgress) {
            toast("Please wait — the previous START is still being prepared.")
            return
        }
        // Phase 3: playlist sessions prepare their own items (with cache
        // reuse) inside the service — no pre-selected single video needed.
        val playlist = PlaylistRepository.load(this)
        if (settingsRepo.playlistEnabled && playlist != null && playlist.items.size >= 2) {
            startInProgress = true
            com.videolive.app.ffmpeg.LogStore.startSession()
            startStreamWith(null)
            return
        }
        val loaded = VideoRepository.current
        if (loaded == null) {
            // The temporary bridge copy may have been deleted after the last
            // stream stopped — rebuild the FFmpeg input before starting.
            val uriString = settingsRepo.videoUri
            if (uriString == null) {
                toast("Please select a video.")
                return
            }
            toast("Preparing video for streaming...")
            startInProgress = true
            com.videolive.app.ffmpeg.LogStore.startSession()
            val startButton = findViewById<TextView>(R.id.btnStartLive)
            startButton.isEnabled = false
            lifecycleScope.launch {
                val prepared = try {
                    VideoLoader.loadOnce(this@MainActivity, Uri.parse(uriString))
                } catch (e: VideoInputException) {
                    toast(e.message ?: "Unable to read this video.")
                    startButton.isEnabled = true
                    startInProgress = false
                    return@launch
                } catch (t: Throwable) {
                    toast("Unable to read this video.")
                    startButton.isEnabled = true
                    startInProgress = false
                    return@launch
                }
                startButton.isEnabled = true
                startStreamWith(prepared)
            }
            return
        }
        startInProgress = true
        com.videolive.app.ffmpeg.LogStore.startSession()
        startStreamWith(loaded)
    }

    private fun startStreamWith(loaded: LoadedVideo?) {
        // Releases the START guard and re-enables the button on any abort path.
        fun abort(message: String) {
            toast(message)
            startInProgress = false
        }

        if (!switchFullUrl.isChecked) {
            val server = etServerUrl.text.toString().trim()
            val key = etStreamKey.text.toString().trim()
            if (!FFmpegCommandBuilder.isValidRtmpUrl(server)) {
                abort("Please enter a valid RTMP/RTMPS server URL.")
                return
            }
            if (key.isEmpty()) {
                abort("Please enter your YouTube Stream Key.")
                return
            }
        } else {
            val full = etFullUrl.text.toString().trim()
            if (!FFmpegCommandBuilder.isValidRtmpUrl(full)) {
                abort("Please enter a valid full RTMP/RTMPS URL.")
                return
            }
        }
        if (!Net.isOnline(this)) {
            abort("Network connection unavailable.")
            return
        }
        // Real FFmpeg runtime verification: native library present for this
        // device's ABI AND a successful `ffmpeg -version` execution test.
        val engine = FFmpegRuntime.verify(this)
        if (!engine.ready) {
            abort(engine.userMessage + " Check Advanced Logs for the exact reason.")
            return
        }

        // Direct start — identical to the last known working pipeline.
        // (The optional TEST YOUTUBE CONNECTION button remains available for
        // diagnostics, but it never blocks or gates streaming.)
        beginStreaming(loaded)
    }

    private fun beginStreaming(loaded: LoadedVideo?) {
        if (StreamService.isStreaming) {
            toast("A stream is already running.")
            startInProgress = false
            return
        }
        // Persist everything the user just typed.
        settingsRepo.serverUrl = etServerUrl.text.toString().trim()
        settingsRepo.fullUrl = etFullUrl.text.toString().trim()
        val manualKbps = etBitrate.text.toString().toIntOrNull() ?: settingsRepo.manualBitrateKbps
        settingsRepo.manualBitrateKbps = manualKbps

        SecurePrefs.saveStreamKey(this, etStreamKey.text.toString().trim())

        var fps = settingsRepo.fps
        val (adjustedFps, note) = DeviceCaps.adjustFpsIfNeeded(settingsRepo.quality.label, fps)
        if (note != null) {
            fps = adjustedFps
            selectFps(fps)
            toast(note)
        }

        // Phase 3: if a saved playlist with 2+ items exists and the
        // experimental gate is on, stream it as ONE continuous session.
        val playlist = PlaylistRepository.load(this)
        val usePlaylist = settingsRepo.playlistEnabled &&
            playlist != null && playlist.items.size >= 2

        val config = if (usePlaylist && playlist != null) {
            StreamConfig(
                videoUri = playlist.items.first().uri,
                videoName = "Playlist (${playlist.items.size} videos)",
                hasAudio = playlist.items.first().hasAudio,
                orientation = settingsRepo.orientation,
                quality = settingsRepo.quality,
                fps = fps,
                bitrateMode = settingsRepo.bitrateMode,
                manualBitrateKbps = settingsRepo.manualBitrateKbps,
                videoVolumePct = settingsRepo.videoVolumePct,
                micOn = settingsRepo.micOn,
                loopMode = playlist.loopMode,
                fullUrlMode = settingsRepo.fullUrlMode,
                serverUrl = settingsRepo.serverUrl,
                fullUrl = settingsRepo.fullUrl,
                items = playlist.items,
                sessionDurationHours = playlist.sessionDurationHours,
                stopPolicy = playlist.stopPolicy,
                frameMode = settingsRepo.frameMode,
                zoomPct = settingsRepo.zoomPct,
                panXPct = settingsRepo.panXPct,
                panYPct = settingsRepo.panYPct,
                micVolumePct = settingsRepo.micVolumePct
            )
        } else {
            if (loaded == null) {
                toast("Please select a video.")
                startInProgress = false
                return
            }
            StreamConfig(
                videoUri = settingsRepo.videoUri.orEmpty(),
                videoName = loaded.source.displayName,
                hasAudio = loaded.info.hasAudio,
                orientation = settingsRepo.orientation,
                quality = settingsRepo.quality,
                fps = fps,
                bitrateMode = settingsRepo.bitrateMode,
                manualBitrateKbps = settingsRepo.manualBitrateKbps,
                videoVolumePct = settingsRepo.videoVolumePct,
                micOn = settingsRepo.micOn,
                loopMode = LoopMode.ONE,
                fullUrlMode = settingsRepo.fullUrlMode,
                serverUrl = settingsRepo.serverUrl,
                fullUrl = settingsRepo.fullUrl,
                frameMode = settingsRepo.frameMode,
                zoomPct = settingsRepo.zoomPct,
                panXPct = settingsRepo.panXPct,
                panYPct = settingsRepo.panYPct,
                micVolumePct = settingsRepo.micVolumePct
            )
        }

        com.videolive.app.ffmpeg.LogStore.event(
            if (usePlaylist) {
                "Playlist session: ${config.items.size} items, mode=${config.loopMode}, " +
                    "policy=${config.stopPolicy}"
            } else {
                "Source URI validated: ${loaded.source.displayName} " +
                    "(${if (loaded.source.isTemporaryCopy) "cache bridge" else "direct read"})"
            }
        )
        StreamService.start(this, config)
        // The service owns the session from here; its own isStreaming guard
        // rejects any duplicate START.
        startInProgress = false
        startActivity(Intent(this, LiveActivity::class.java))
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        batteryWarning.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("Please allow unrestricted battery usage for this app in system settings.")
            }
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Builds the destination URL from the current fields for testing only. */
    private fun currentDestinationForTest(): String? {
        return if (switchFullUrl.isChecked) {
            val url = etFullUrl.text.toString().trim()
            if (FFmpegCommandBuilder.isValidRtmpUrl(url) && url.none { it.isWhitespace() }) url
            else null
        } else {
            FFmpegCommandBuilder.buildDestinationUrl(
                fullUrlMode = false,
                serverUrl = etServerUrl.text.toString().trim(),
                fullUrl = "",
                streamKey = etStreamKey.text.toString().trim()
            )
        }
    }

    private fun runDestinationTest() {
        val url = currentDestinationForTest()
        if (url == null) {
            toast("Please enter a valid RTMP/RTMPS destination first.")
            return
        }
        val testButton = findViewById<TextView>(R.id.btnTestConnection)
        testButton.isEnabled = false
        val originalText = testButton.text
        testButton.text = "Testing..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RtmpProbe.probe(url) }
            testButton.isEnabled = true
            testButton.text = originalText
            val suffix = if (result.success) {
                "\n\nRTMP handshake succeeded — the server is reachable and speaks RTMP(S). " +
                    "The actual publish happens when you go live."
            } else {
                "\n\nFix the failing step before going live."
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (result.success) "Destination reachable ✓" else "Destination test failed")
                .setMessage(result.report() + suffix)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
