package com.videolive.app.util

/**
 * The stream key must never appear in logs, crash reports, notifications or UI.
 * Every line that reaches the log store is passed through [mask].
 */
object Sanitize {

    private val URL_WITH_PATH = Regex("""(rtmps?|https?|tcp|tls)://([^/\s:"']+)(/[^\s"'`]*)""")

    fun mask(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return URL_WITH_PATH.replace(input) { m ->
            "${m.groupValues[1]}://${m.groupValues[2]}/<hidden-key>"
        }
    }
}
