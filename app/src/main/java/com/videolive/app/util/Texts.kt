package com.videolive.app.util

import java.util.Locale

object Texts {

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun formatClock(ms: Long): String {
        val safe = if (ms < 0) 0 else ms
        val totalSeconds = safe / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / (1024f * 1024f)
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024f)
        else String.format(Locale.US, "%.1f MB", mb)
    }
}
