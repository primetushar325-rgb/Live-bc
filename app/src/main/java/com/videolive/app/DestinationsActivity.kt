package com.videolive.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.videolive.app.data.DestinationsRepository
import com.videolive.app.data.SecurePrefs
import com.videolive.app.data.SettingsRepository
import com.videolive.app.ffmpeg.FFmpegCommandBuilder
import com.videolive.app.ffmpeg.LogStore
import com.videolive.app.net.RtmpProbe
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 8 — destination manager.
 *
 * Up to 3 saved RTMP/RTMPS destinations. Stream keys live ONLY in
 * [SecurePrefs] (EncryptedSharedPreferences) and are never written to logs
 * or displayed once saved. On-device simulcast (publishing to several
 * servers at once) is intentionally NOT done: one encoder session keeps the
 * proven engine intact; the ACTIVE destination is the one used to stream.
 */
class DestinationsActivity : AppCompatActivity() {

    private class Row(
        val id: String,
        val etName: EditText,
        val etServer: EditText,
        val etKey: EditText,
        val txtStatus: TextView,
        val swEnabled: SwitchCompat,
        val btnActive: TextView
    ) {
        var keyVisible = false
        var hasSavedKey = false
    }

    private val rows = mutableListOf<Row>()
    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_destinations)
        settingsRepo = SettingsRepository(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSaveDestinations).setOnClickListener { saveAll() }

        val saved = DestinationsRepository.load(this)
        val activeId = DestinationsRepository.activeId(this)
        val container = findViewById<LinearLayout>(R.id.listDestinations)

        // Always show up to MAX slots (existing + empty ones).
        val slots = saved.toMutableList()
        while (slots.size < DestinationsRepository.MAX) {
            slots += DestinationsRepository.Destination(
                id = "dest_" + UUID.randomUUID().toString().take(8),
                name = "",
                serverUrl = "",
                fullUrlMode = false,
                fullUrl = "",
                enabled = false
            )
        }

        slots.forEach { d ->
            container.addView(buildRow(d, d.id == activeId))
        }
    }

    private fun buildRow(
        d: DestinationsRepository.Destination,
        isActive: Boolean
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        // Title row: slot label + enable switch.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = "DESTINATION ${rows.size + 1}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@DestinationsActivity, R.color.text_secondary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val swEnabled = SwitchCompat(this)
        swEnabled.isChecked = d.enabled || d.name.isNotBlank() || d.serverUrl.isNotBlank()
        titleRow.addView(label)
        titleRow.addView(swEnabled)
        card.addView(titleRow)

        val etName = editText(getString(R.string.destination_name), d.name)
        val etServer = editText(getString(R.string.destination_server), d.serverUrl)
        etServer.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        // Key row: password field + eye.
        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val etKey = editText(getString(R.string.destination_key), "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hasSavedKey = DestinationsRepository.keyFor(this, d.id).isNotEmpty()
        if (hasSavedKey) etKey.hint = "Key saved • type to replace"
        val btnEye = ImageButton(this).apply {
            setBackgroundResource(R.drawable.bg_button_secondary)
            setImageResource(R.drawable.ic_eye)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        keyRow.addView(etKey)
        keyRow.addView(btnEye)

        val txtStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@DestinationsActivity, R.color.text_faint))
        }

        // Action row: TEST / SET ACTIVE.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnTest = TextView(this).apply {
            text = getString(R.string.destination_test)
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@DestinationsActivity, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_button_secondary)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val btnActive = TextView(this).apply {
            text = if (isActive) "● ACTIVE" else getString(R.string.destination_active)
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    this@DestinationsActivity,
                    if (isActive) R.color.green else R.color.text_secondary
                )
            )
            setBackgroundResource(
                if (isActive) R.drawable.bg_chip_selector else R.drawable.bg_button_secondary
            )
            isSelected = isActive
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        actionRow.addView(btnTest)
        actionRow.addView(btnActive)

        card.addView(etName)
        card.addView(etServer)
        card.addView(keyRow)
        card.addView(txtStatus)
        card.addView(actionRow)

        val row = Row(
            id = d.id,
            etName = etName,
            etServer = etServer,
            etKey = etKey,
            txtStatus = txtStatus,
            swEnabled = swEnabled,
            btnActive = btnActive
        )
        row.hasSavedKey = hasSavedKey

        btnEye.setOnClickListener {
            row.keyVisible = !row.keyVisible
            etKey.inputType = if (row.keyVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etKey.setSelection(etKey.text.length)
            btnEye.setImageResource(if (row.keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
        }

        btnTest.setOnClickListener { testDestination(row) }
        btnActive.setOnClickListener { setActive(row) }

        rows += row
        return card
    }

    private fun editText(hint: String, value: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(this@DestinationsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@DestinationsActivity, R.color.text_faint))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(4)
            }
        }
    }

    /** TEST runs the real staged reachability probe (DNS/TCP/TLS/handshake). */
    private fun testDestination(row: Row) {
        val server = row.etServer.text.toString().trim()
        if (!FFmpegCommandBuilder.isValidRtmpUrl(server)) {
            row.txtStatus.text = "Enter a valid rtmp:// or rtmps:// server URL first."
            return
        }
        row.txtStatus.text = "Testing server..."
        row.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RtmpProbe.probe(server) }
            row.txtStatus.text = if (result.success) {
                "Server reachable ✓ (RTMP handshake OK)"
            } else {
                "Failed at: ${result.failedAt}"
            }
            row.txtStatus.setTextColor(
                ContextCompat.getColor(
                    this@DestinationsActivity,
                    if (result.success) R.color.green else R.color.red
                )
            )
        }
    }

    /** SET ACTIVE: save everything, then point the main streaming config here. */
    private fun setActive(row: Row) {
        if (!saveAll(silent = true)) return
        val server = row.etServer.text.toString().trim()
        if (!FFmpegCommandBuilder.isValidRtmpUrl(server)) {
            toast("This destination has no valid server URL.")
            return
        }
        val key = row.etKey.text.toString().trim()
        if (key.isEmpty() && !row.hasSavedKey) {
            toast("This destination has no stream key yet.")
            return
        }

        DestinationsRepository.setActive(this, row.id)
        // Sync into the main settings so the existing (proven) engine path
        // streams to this destination without any engine changes.
        settingsRepo.fullUrlMode = false
        settingsRepo.serverUrl = server
        if (key.isNotEmpty()) {
            SecurePrefs.saveStreamKey(this, key)
        } else {
            SecurePrefs.saveStreamKey(this, DestinationsRepository.keyFor(this, row.id))
        }
        LogStore.event("Active destination: ${row.etName.text.ifBlank { row.id }}")

        rows.forEach { r ->
            val active = r.id == row.id
            r.btnActive.text = if (active) "● ACTIVE" else getString(R.string.destination_active)
            r.btnActive.isSelected = active
            r.btnActive.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.green else R.color.text_secondary
                )
            )
        }
        toast("Active destination set — streaming will use it.")
    }

    /** Persists names/URLs/enabled flags and any freshly typed keys. */
    private fun saveAll(silent: Boolean = false): Boolean {
        val list = rows.mapIndexed { index, r ->
            DestinationsRepository.Destination(
                id = r.id,
                name = r.etName.text.toString().trim().ifEmpty { "Destination ${index + 1}" },
                serverUrl = r.etServer.text.toString().trim(),
                fullUrlMode = false,
                fullUrl = "",
                enabled = r.swEnabled.isChecked
            )
        }.filter { it.name.isNotBlank() || it.serverUrl.isNotBlank() }
        DestinationsRepository.save(this, list)

        // Keys are stored encrypted, never in the plain prefs above.
        rows.forEach { r ->
            val key = r.etKey.text.toString().trim()
            if (key.isNotEmpty()) {
                DestinationsRepository.saveKey(this, r.id, key)
                r.etKey.setText("")
                r.etKey.hint = "Key saved • type to replace"
                r.hasSavedKey = true
                r.keyVisible = false
            }
        }
        if (!silent) toast("Destinations saved")
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
