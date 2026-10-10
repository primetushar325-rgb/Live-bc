package com.videolive.app.ffmpeg

enum class ErrorKind { AUTH, NETWORK, INPUT, ENGINE }

data class ClassifiedError(val kind: ErrorKind, val userMessage: String)

/**
 * Turns raw FFmpeg failure output into human readable, actionable messages.
 * Raw logs are never shown to normal users (see the Advanced Logs screen).
 */
object ErrorClassifier {

    fun classify(logTail: String): ClassifiedError {
        val log = logTail.lowercase()

        if (containsAny(log,
                "authentication failed",
                "not authorized",
                "access denied",
                "403 forbidden",
                "invalid stream key",
                "handshake failed"
            )
        ) {
            return ClassifiedError(ErrorKind.AUTH, "YouTube rejected the stream. Check your Stream Key.")
        }

        if (containsAny(log,
                "invalid data found when processing input",
                "does not contain any stream",
                "no such file",
                "error opening input",
                "moov atom not found",
                "operation not permitted",
                "unrecognized option"
            )
        ) {
            return ClassifiedError(ErrorKind.INPUT, "Unable to read this video or invalid settings.")
        }

        if (containsAny(log,
                "connection reset",
                "connection refused",
                "connection timed out",
                "broken pipe",
                "network is unreachable",
                "host not found",
                "error writing trailer",
                "connection ended",
                "operation timed out",
                "input/output error",
                "resource temporarily unavailable",
                "tls",
                "ssl"
            )
        ) {
            return ClassifiedError(ErrorKind.NETWORK, "Internet connection lost or server closed the connection.")
        }

        return ClassifiedError(ErrorKind.ENGINE, "Streaming engine stopped unexpectedly.")
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
