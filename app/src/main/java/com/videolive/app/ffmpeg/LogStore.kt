package com.videolive.app.ffmpeg

import com.videolive.app.util.Sanitize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory sanitized log ring buffer, shown on the Advanced Logs screen.
 * Every line is masked by [Sanitize] so the stream key can never appear here.
 */
object LogStore {

    private const val CAPACITY = 600
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Tags every pipeline event of the current START LIVE session. */
    @Volatile
    private var sessionTag: String = ""

    /** Begins (or replaces) the session tag; returns it, e.g. "session=384". */
    fun startSession(): String {
        val tag = "session=" + (100 + kotlin.random.Random.nextInt(900))
        sessionTag = tag
        return tag
    }

    @Synchronized
    fun append(rawLine: String) {
        val line = Sanitize.mask(rawLine).trimEnd()
        if (line.isEmpty()) return
        val stamp = timeFormat.format(Date())
        for (l in line.split('\n')) {
            val clean = l.trimEnd()
            if (clean.isEmpty()) continue
            lines.addLast("[$stamp] $clean")
            if (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    @Synchronized
    fun event(message: String) {
        val stamp = timeFormat.format(Date())
        val tag = sessionTag
        val prefix = if (tag.isNotEmpty()) "[$stamp $tag] " else "[$stamp] "
        lines.addLast(prefix + Sanitize.mask(message))
        if (lines.size > CAPACITY) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = if (lines.isEmpty()) "(no logs yet)" else lines.joinToString("\n")

    @Synchronized
    fun recentTail(count: Int = 60): String =
        lines.takeLast(count).joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
