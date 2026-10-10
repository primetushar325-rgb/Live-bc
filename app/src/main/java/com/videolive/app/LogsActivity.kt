package com.videolive.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.videolive.app.util.ThemeEngine
import com.videolive.app.ffmpeg.LogStore

class LogsActivity : AppCompatActivity() {

    private lateinit var txtLogs: TextView
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshot = ""

    private val refresh = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                update()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeEngine.apply(this)
        setContentView(R.layout.activity_logs)

        txtLogs = findViewById(R.id.txtLogs)
        scroll = findViewById(R.id.logsScroll)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            LogStore.clear()
            update()
        }
        findViewById<TextView>(R.id.btnCopyLogs).setOnClickListener {
            copyLogsToClipboard()
        }
        findViewById<TextView>(R.id.btnShareLogs).setOnClickListener {
            shareLogs()
        }
    }

    /** Copies the full sanitized log to the system clipboard. */
    private fun copyLogsToClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("VideoLive Advanced Logs", LogStore.snapshot()))
            Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Copy failed: ${t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the Android share sheet so the log can be sent via any app. */
    private fun shareLogs() {
        try {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "VideoLive Advanced Logs")
                .putExtra(Intent.EXTRA_TEXT, LogStore.snapshot())
            startActivity(Intent.createChooser(send, "Send logs via"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Share failed: ${t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun update() {
        val snapshot = LogStore.snapshot()
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot

        val isNearBottom = scroll.height > 0 &&
            scroll.scrollY + scroll.height >= txtLogs.height - 80
        txtLogs.text = snapshot
        if (isNearBottom) {
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
