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
    private const val FILE_CAP_BYTES = 1_500_000L
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Optional persistent sink (session diagnostics survive Activity
     * restarts; capped and rotated so it cannot grow unbounded). */
    @Volatile
    private var fileSink: java.io.File? = null

    @Synchronized
    fun bindFile(file: java.io.File) {
        fileSink = file
        runCatching {
            if (!file.exists() || file.length() > FILE_CAP_BYTES) {
                file.writeText("[LIVE VIP session diagnostics]\n")
            }
        }
    }

    private fun writeFile(line: String) {
        val f = fileSink ?: return
        runCatching {
            if (f.length() > FILE_CAP_BYTES) f.writeText("[LIVE VIP session diagnostics — rotated]\n")
            f.appendText(line + "\n")
        }
    }

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
            val stored = "[$stamp] $clean"
            lines.addLast(stored)
            if (lines.size > CAPACITY) lines.removeFirst()
            writeFile(stored)
        }
    }

    @Synchronized
    fun event(message: String) {
        val stamp = timeFormat.format(Date())
        val tag = sessionTag
        val prefix = if (tag.isNotEmpty()) "[$stamp $tag] " else "[$stamp] "
        val stored = prefix + Sanitize.mask(message)
        lines.addLast(stored)
        if (lines.size > CAPACITY) lines.removeFirst()
        writeFile(stored)
    }

    fun hasSession(): Boolean = sessionTag.isNotEmpty()

    @Synchronized
    fun snapshot(): String = if (lines.isEmpty()) "(no logs yet)" else lines.joinToString("\n")

    @Synchronized
    fun recentTail(count: Int = 60): String =
        lines.takeLast(count).joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
