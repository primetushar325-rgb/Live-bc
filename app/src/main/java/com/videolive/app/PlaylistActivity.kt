package com.videolive.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.videolive.app.data.PlaylistRepository
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.media.VideoLoader
import com.videolive.app.model.LoopMode
import com.videolive.app.model.StopPolicy
import com.videolive.app.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3 — playlist manager.
 *
 * The playlist is metadata only (URIs + probed facts). Real byte preparation
 * happens inside StreamService right before streaming, with cache reuse, so
 * this screen never copies video data.
 *
 * One RTMP session plays the whole list through the FFmpeg concat demuxer;
 * items must share codec/resolution/audio layout for seamless playback.
 */
class PlaylistActivity : AppCompatActivity() {

    private val items = mutableListOf<StreamItem>()
    private var adding = false

    private lateinit var listContainer: LinearLayout
    private lateinit var txtEmpty: TextView
    private lateinit var txtAdding: TextView
    private lateinit var txtTotal: TextView
    private lateinit var spinnerDuration: Spinner

    private val addVideos =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            ingestUris(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        listContainer = findViewById(R.id.listPlaylist)
        txtEmpty = findViewById(R.id.txtPlaylistEmpty)
        txtAdding = findViewById(R.id.txtAdding)
        txtTotal = findViewById(R.id.txtPlaylistTotal)
        spinnerDuration = findViewById(R.id.spinnerDuration)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddVideos).setOnClickListener {
            if (adding) {
                toast("Still adding videos, please wait...")
                return@setOnClickListener
            }
            addVideos.launch(arrayOf("video/*", "application/octet-stream"))
        }
        findViewById<TextView>(R.id.btnClearPlaylist).setOnClickListener {
            items.clear()
            render()
        }
        findViewById<TextView>(R.id.btnSaveUse).setOnClickListener { saveAndUse() }

        spinnerDuration.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf(
                getString(R.string.duration_unlimited),
                "2 hours",
                "6 hours",
                "12 hours"
            )
        )

        PlaylistRepository.load(this)?.let { saved ->
            items.addAll(saved.items)
            when (saved.loopMode) {
                LoopMode.ALL -> findViewById<RadioButton>(R.id.rbLoopAll).isChecked = true
                LoopMode.SEQUENTIAL -> findViewById<RadioButton>(R.id.rbSequential).isChecked = true
                LoopMode.SHUFFLE -> findViewById<RadioButton>(R.id.rbShuffle).isChecked = true
                else -> findViewById<RadioButton>(R.id.rbLoopAll).isChecked = true
            }
            spinnerDuration.setSelection(hoursToIndex(saved.sessionDurationHours))
        } ?: run {
            findViewById<RadioButton>(R.id.rbLoopAll).isChecked = true
        }

        render()
    }

    private fun hoursToIndex(hours: Int): Int = when (hours) {
        2 -> 1
        6 -> 2
        12 -> 3
        else -> 0
    }

    private fun indexToHours(index: Int): Int = when (index) {
        1 -> 2
        2 -> 6
        3 -> 12
        else -> 0
    }

    private fun ingestUris(uris: List<Uri>) {
        adding = true
        txtAdding.visibility = android.view.View.VISIBLE
        txtAdding.text = "Reading video details..."
        lifecycleScope.launch {
            var added = 0
            var failed = 0
            uris.forEachIndexed { i, uri ->
                txtAdding.text = "Reading video ${i + 1} of ${uris.size}..."
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                }
                val inspection = try {
                    withContext(Dispatchers.IO) {
                        VideoLoader.inspect(this@PlaylistActivity, uri)
                    }
                } catch (t: Throwable) {
                    failed++
                    null
                }
                if (inspection != null) {
                    val info = inspection.platformInfo
                    items += StreamItem(
                        uri = uri.toString(),
                        displayName = inspection.displayName,
                        sizeBytes = inspection.sizeBytes,
                        durationMs = info?.durationMs ?: 0L,
                        width = info?.width ?: 0,
                        height = info?.height ?: 0,
                        fps = info?.fps ?: 0,
                        hasAudio = info?.hasAudio ?: true
                    )
                    added++
                }
                render()
            }
            adding = false
            txtAdding.visibility = android.view.View.GONE
            LogStore.event("Playlist: added $added video(s), $failed failed")
            if (failed > 0) toast("$failed file(s) could not be read and were skipped")
            render()
        }
    }

    private fun selectedLoopMode(): LoopMode = when {
        findViewById<RadioButton>(R.id.rbSequential).isChecked -> LoopMode.SEQUENTIAL
        findViewById<RadioButton>(R.id.rbShuffle).isChecked -> LoopMode.SHUFFLE
        else -> LoopMode.ALL
    }

    private fun saveAndUse() {
        if (items.size < 2) {
            toast("Add at least 2 videos to use Playlist mode.")
            return
        }
        // Warn early when formats obviously differ (width/height known).
        val sizes = items.filter { it.width > 0 && it.height > 0 }
            .map { it.width to it.height }
            .distinct()
        if (sizes.size > 1) {
            toast("Warning: resolutions differ — the stream will refuse mismatched items.")
        }
        val loopMode = selectedLoopMode()
        val hours = indexToHours(spinnerDuration.selectedItemPosition)
        PlaylistRepository.save(
            this,
            PlaylistRepository.Playlist(
                items = items.toList(),
                loopMode = loopMode,
                sessionDurationHours = hours,
                stopPolicy = if (hours > 0) StopPolicy.STOP_AFTER_DURATION
                else StopPolicy.CONTINUE_UNTIL_STOPPED
            )
        )
        LogStore.event(
            "Playlist saved: ${items.size} items, mode=$loopMode, " +
                (if (hours > 0) "stop after ${hours}h" else "unlimited")
        )
        toast("Playlist saved — START LIVE now streams the whole list.")
        finish()
    }

    private fun render() {
        listContainer.removeAllViews()
        txtEmpty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 14, 0, 14)
            }

            val number = TextView(this).apply {
                text = "${index + 1}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PlaylistActivity, R.color.cyan))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val textBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(10); marginEnd = dp(8) }
            }
            val name = TextView(this).apply {
                text = item.displayName
                textSize = 13.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(ContextCompat.getColor(this@PlaylistActivity, R.color.text_primary))
            }
            val meta = TextView(this).apply {
                val mins = if (item.durationMs > 0) " • ${item.durationMs / 60000} min" else ""
                val res = if (item.width > 0) " • ${item.width}x${item.height}" else ""
                text = "${formatSize(item.sizeBytes)}$res$mins"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@PlaylistActivity, R.color.text_faint))
            }
            textBlock.addView(name)
            textBlock.addView(meta)

            val up = smallButton("▲") { move(index, -1) }
            val down = smallButton("▼") { move(index, +1) }
            val remove = smallButton("✕") {
                items.removeAt(index)
                render()
            }

            row.addView(number)
            row.addView(textBlock)
            row.addView(up)
            row.addView(down)
            row.addView(remove)
            listContainer.addView(row)

            if (index < items.size - 1) {
                val divider = android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(ContextCompat.getColor(this@PlaylistActivity, R.color.card_stroke))
                }
                listContainer.addView(divider)
            }
        }

        val totalMs = items.sumOf { it.durationMs }
        txtTotal.text = if (items.isEmpty()) "" else
            "${items.size} videos • total ${totalMs / 3600000}h ${(totalMs % 3600000) / 60000} min"
    }

    private fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target < 0 || target >= items.size) return
        val tmp = items[index]
        items[index] = items[target]
        items[target] = tmp
        render()
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@PlaylistActivity, R.color.text_secondary))
            setBackgroundResource(R.drawable.bg_button_secondary)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(6)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.0f MB", bytes / (1024.0 * 1024))
        bytes > 0 -> String.format("%.0f KB", bytes / 1024.0)
        else -> ""
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
