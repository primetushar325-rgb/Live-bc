package com.videolive.app.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.videolive.app.ffmpeg.LogStore
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures microphone audio and writes 16-bit PCM into an FFmpegKit named pipe
 * (a real FIFO on disk). FFmpeg consumes it as a second input and mixes it with
 * the video's own audio using the amix filter — the video audio is never muted
 * by microphone handling. Muting the mic simply writes silence at the same
 * real-time pace so the pipeline never stalls.
 */
object MicMixer {

    private const val SAMPLE_RATE = 44100

    @Volatile var muted: Boolean = false

    /** Approximate live input level 0-100 for the dashboard (UI only). */
    @Volatile var levelPct: Int = 0
        private set

    private val running = AtomicBoolean(false)
    @Volatile private var pipePath: String? = null
    @Volatile private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    /** Creates the recorder synchronously so the caller knows immediately if the mic is unusable. */
    @SuppressLint("MissingPermission")
    fun start(path: String): Boolean {
        if (running.get()) {
            pipePath = path
            return true
        }
        // Single-instance lifecycle: a previous worker must finish before a
        // new recorder/thread is created — otherwise a quick stop/start cycle
        // (e.g. retry after cleanup) can run two AudioRecord workers at once.
        thread?.let { prev ->
            if (prev.isAlive) {
                try {
                    prev.join(800)
                } catch (_: InterruptedException) {
                }
            }
        }
        thread = null
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (t: Throwable) {
            -1
        }
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (t: Throwable) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try {
                rec?.release()
            } catch (_: Throwable) {
            }
            return false
        }
        recorder = rec
        pipePath = path
        running.set(true)
        thread = Thread({ loop() }, "MicMixer").also { it.start() }
        LogStore.event("Microphone capture started (44.1 kHz PCM)")
        return true
    }

    /** Points the writer at a freshly registered pipe (used on controlled reconnects). */
    fun switchPipe(path: String) {
        pipePath = path
    }

    fun stop() {
        running.set(false)
        // The worker thread releases the recorder itself.
        thread = null
    }

    private fun loop() {
        val rec = recorder ?: return
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            LogStore.event("AudioRecord failed to start; mic will send silence")
        }
        var zeroMode = rec.recordingState != AudioRecord.RECORDSTATE_RECORDING
        var failStreak = 0
        val buf = ByteArray(4096)

        while (running.get()) {
            val path = pipePath
            if (path == null) {
                sleep(100)
                continue
            }
            var raf: RandomAccessFile? = null
            try {
                // O_RDWR on the FIFO: opens instantly and lets us pace the writes.
                raf = RandomAccessFile(path, "rw")
                while (running.get()) {
                    val n: Int
                    if (zeroMode) {
                        buf.fill(0)
                        n = buf.size
                        sleep(45) // ~4096 bytes at 44.1 kHz stereo16 ≈ 46 ms
                    } else {
                        n = rec.read(buf, 0, buf.size)
                        if (n <= 0) {
                            failStreak++
                            if (failStreak > 5) {
                                zeroMode = true
                                LogStore.event("Microphone capture lost; keeping stream alive with silence")
                            }
                            continue
                        }
                        failStreak = 0
                        if (muted) {
                            levelPct = 0
                            buf.fill(0, 0, n)
                        } else {
                            levelPct = rmsPercent(buf, n)
                        }
                    }
                    raf.write(buf, 0, n)
                }
            } catch (e: FileNotFoundException) {
                sleep(200) // pipe not registered yet, retry
            } catch (e: IOException) {
                sleep(150) // reader (FFmpeg) closed the pipe; wait for the next one
            } catch (t: Throwable) {
                sleep(200)
            } finally {
                try {
                    raf?.close()
                } catch (_: Throwable) {
                }
            }
        }

        try {
            rec.stop()
        } catch (_: Throwable) {
        }
        try {
            rec.release()
        } catch (_: Throwable) {
        }
        recorder = null
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    /** Rough RMS of 16-bit LE PCM mapped to 0-100 for display. */
    private fun rmsPercent(buf: ByteArray, n: Int): Int {
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sum += sample.toLong() * sample.toLong()
            count++
            i += 2
        }
        if (count == 0) return 0
        val rms = Math.sqrt(sum.toDouble() / count)
        return ((rms / 32768.0) * 250.0).toInt().coerceIn(0, 100)
    }
}
