package com.videolive.app.net

import com.videolive.app.ffmpeg.LogStore
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real, staged reachability test for an RTMP/RTMPS destination.
 *
 * Steps (each one fails fast with its exact reason):
 *   1. URL parse        — scheme rtmp(s)://, host, explicit port preserved
 *   2. DNS              — host actually resolves
 *   3. TCP              — real socket connect within the timeout
 *   4. TLS              — only for rtmps://; full TLS handshake
 *   5. RTMP handshake   — C0+C1 sent, S0+S1+S2 read, C2 echo sent.
 *
 * The stream KEY is never part of this test (the handshake does not need it),
 * so it cannot leak here. The test never starts the encoder.
 *
 * Note: the RTMP connect/publish round-trip happens later inside FFmpeg when
 * the stream starts; a successful handshake proves the ingest server is
 * reachable and speaks RTMP(S).
 */
object RtmpProbe {

    data class StepResult(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val ms: Long
    )

    data class Result(val steps: List<StepResult>, val failedAt: String?) {
        val success: Boolean get() = failedAt == null
        fun report(): String = steps.joinToString("\n") { s ->
            (if (s.ok) "✓ " else "✗ ") + s.name +
                (if (s.detail.isNotEmpty()) " — " + s.detail else "") +
                " (${s.ms} ms)"
        }
    }

    private const val DEFAULT_TIMEOUT_MS = 10_000

    fun probe(destinationUrl: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result {
        val steps = mutableListOf<StepResult>()
        var failedAt: String? = null

        fun step(name: String, ok: Boolean, detail: String, startedAt: Long): Boolean {
            val ms = System.currentTimeMillis() - startedAt
            steps.add(StepResult(name, ok, detail, ms))
            LogStore.event(
                "Destination test ${if (ok) "OK" else "FAILED"}: $name" +
                    if (detail.isNotEmpty()) " — $detail" else ""
            )
            if (!ok && failedAt == null) failedAt = name
            return ok
        }

        // 1) URL parse.
        var t = System.currentTimeMillis()
        val secure = destinationUrl.startsWith("rtmps://")
        val validScheme = secure || destinationUrl.startsWith("rtmp://")
        val rest = destinationUrl.substringAfter("://", "")
        val hostPort = rest.substringBefore('/')
        val host = hostPort.substringBefore(':').trim()
        val explicitPort = hostPort.substringAfter(':', "").toIntOrNull()
        val port = explicitPort ?: if (secure) 443 else 1935
        if (!validScheme || host.isEmpty()) {
            step("URL parse", false, "Not a valid rtmp:// or rtmps:// URL", t)
            return Result(steps, failedAt)
        }
        step(
            "URL parse", true,
            "${if (secure) "rtmps" else "rtmp"}://$host:$port" +
                (if (explicitPort != null) " (explicit port preserved)" else ""),
            t
        )

        var socket: Socket? = null
        try {
            // 2) DNS.
            t = System.currentTimeMillis()
            val address = try {
                InetAddress.getByName(host)
            } catch (ex: Throwable) {
                step("DNS lookup", false, "Cannot resolve $host: ${ex.javaClass.simpleName}", t)
                return Result(steps, failedAt)
            }
            step("DNS lookup", true, "$host -> ${address.hostAddress}", t)

            // 3) TCP.
            t = System.currentTimeMillis()
            socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), timeoutMs)
            } catch (ex: Throwable) {
                step("TCP connection", false, "$host:$port — ${ex.javaClass.simpleName}: ${ex.message}", t)
                return Result(steps, failedAt)
            }
            step("TCP connection", true, "connected to $host:$port", t)
            socket.soTimeout = timeoutMs

            var ioSocket: Socket = socket

            // 4) TLS (rtmps only).
            if (secure) {
                t = System.currentTimeMillis()
                try {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val tls = factory.createSocket(socket, host, port, true) as SSLSocket
                    tls.useClientMode = true
                    tls.startHandshake()
                    val session = tls.session
                    step("TLS handshake", true, "${session.protocol} / ${session.cipherSuite}", t)
                    ioSocket = tls
                } catch (ex: Throwable) {
                    step("TLS handshake", false, "${ex.javaClass.simpleName}: ${ex.message}", t)
                    return Result(steps, failedAt)
                }
            }

            // 5) RTMP handshake: C0+C1 -> S0+S1+S2 -> C2.
            t = System.currentTimeMillis()
            try {
                val out = ioSocket.getOutputStream()
                val ins = ioSocket.getInputStream()

                out.write(0x03) // C0: RTMP version 3
                val c1 = ByteArray(1536)
                SecureRandom().nextBytes(c1)
                // C1 header: timestamp=0, zero field=0 (simple handshake).
                for (i in 0..7) c1[i] = 0
                out.write(c1)
                out.flush()

                val s0 = ins.read()
                if (s0 < 0) {
                    step("RTMP handshake", false, "server closed the connection", t)
                    return Result(steps, failedAt)
                }
                if (s0 != 3) {
                    step("RTMP handshake", false, "unexpected server version byte $s0", t)
                    return Result(steps, failedAt)
                }
                val s1 = ByteArray(1536)
                readFully(ins, s1)
                val s2 = ByteArray(1536)
                readFully(ins, s2)
                // C2 = echo of S1 completes the handshake.
                out.write(s1)
                out.flush()
                step("RTMP handshake", true, "C0C1 sent, S0S1S2 received, C2 echoed", t)
            } catch (ex: Throwable) {
                step("RTMP handshake", false, "${ex.javaClass.simpleName}: ${ex.message}", t)
                return Result(steps, failedAt)
            }

            return Result(steps, null)
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readFully(ins: InputStream, buffer: ByteArray) {
        var off = 0
        while (off < buffer.size) {
            val n = ins.read(buffer, off, buffer.size - off)
            if (n < 0) throw java.io.EOFException("server closed the connection mid-handshake")
            off += n
        }
    }
}
