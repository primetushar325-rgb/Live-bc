package com.videolive.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
        setContentView(R.layout.activity_logs)

        txtLogs = findViewById(R.id.txtLogs)
        scroll = findViewById(R.id.logsScroll)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            LogStore.clear()
            update()
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
