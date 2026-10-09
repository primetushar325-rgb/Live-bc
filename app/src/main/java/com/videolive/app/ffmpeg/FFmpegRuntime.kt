package com.videolive.app.ffmpeg

import android.content.Context
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.videolive.app.util.Sanitize
import java.io.File
import java.util.zip.ZipFile

sealed class EngineStatus {
    object Ready : EngineStatus()
    data class UnsupportedAbi(val deviceAbis: List<String>, val apkAbis: Set<String>) : EngineStatus()
    data class InitFailed(val reason: String) : EngineStatus()
    data class TestFailed(val reason: String) : EngineStatus()
}

/**
 * Real FFmpeg runtime verification.
 *
 * Availability is NOT decided by "the class exists". This:
 *  1. detects the device ABI(s),
 *  2. inventories the native libraries actually packaged inside the installed
 *     APK (the lib/<abi>/ .so entries),
 *  3. loads the FFmpegKit native engine,
 *  4. runs a harmless real execution test (`ffmpeg -version`) and checks the
 *     return code.
 * Every step is written to the sanitized developer log (Advanced Logs).
 */
object FFmpegRuntime {

    data class Report(
        val status: EngineStatus,
        val ffmpegVersion: String?,
        val deviceAbis: List<String>,
        val apkAbis: Set<String>,
        val matchedAbi: String?,
        val userMessage: String
    ) {
        val ready: Boolean get() = status is EngineStatus.Ready
    }

    @Volatile private var cached: Report? = null
    @Volatile private var encodersLogged = false

    /**
     * P1.10 evidence: enumerate which H.264 encoders this FFmpeg build
     * actually packages. Runs once per process, on a background thread.
     * A hardware option is only ever offered if a hw encoder is proven to
     * exist here — never assumed.
     */
    fun logEncoderCapabilities() {
        if (encodersLogged) return
        encodersLogged = true
        try {
            val session = FFmpegKit.execute("-hide_banner -encoders")
            val out = session.output.orEmpty()
            val h264 = out.lineSequence()
                .map { it.trim() }
                .filter { it.contains("H.264") || it.contains("h264") }
                .toList()
            val hw = h264.filter {
                it.contains("mediacodec") || it.contains("v4l2m2m") ||
                    it.contains("nvenc") || it.contains("qsv") || it.contains("videotoolbox")
            }
            LogStore.event(
                "H.264 encoders packaged: " + (h264.joinToString(" | ").ifEmpty { "none found" })
            )
            LogStore.event(
                if (hw.isEmpty()) {
                    "No hardware H.264 encoder exists in this FFmpeg build — software " +
                        "libx264 (veryfast/zerolatency) is the only tested encode path; " +
                        "a hw option is intentionally NOT offered."
                } else {
                    "Hardware H.264 encoders present: ${hw.joinToString(" | ")}"
                }
            )
        } catch (t: Throwable) {
            LogStore.event("Encoder capability probe failed: ${t.javaClass.simpleName}")
        }
    }

    fun verify(context: Context, force: Boolean = false): Report {
        cached?.let { if (!force) return it }
        val report = runVerification(context.applicationContext)
        cached = report
        return report
    }

    private fun runVerification(context: Context): Report {
        val deviceAbis = Build.SUPPORTED_ABIS.toList()
        LogStore.event("FFmpeg initialization started")
        LogStore.event("Device ABIs: ${deviceAbis.joinToString(", ")}")

        // 1) Native libraries actually packaged inside this APK.
        val apkAbis = listApkAbis(context)
        LogStore.event(
            "Native libraries packaged in APK for: " +
                if (apkAbis.isEmpty()) "NONE" else apkAbis.joinToString(", ")
        )

        val matched = deviceAbis.firstOrNull { it in apkAbis }
        if (matched == null) {
            LogStore.event("ABI check FAILED: no packaged library for this device")
            return Report(
                EngineStatus.UnsupportedAbi(deviceAbis, apkAbis),
                null, deviceAbis, apkAbis, null,
                "FFmpeg is not available for this device architecture."
            )
        }
        LogStore.event("ABI check OK: FFmpeg binary/library found for $matched")

        // 2) Java-side dependencies of FFmpegKit. The engine AAR is bundled
        //    locally, so its transitive deps (smart-exception) must be added
        //    explicitly. FFmpegKit references com.arthenica.smartexception.java.Exceptions
        //    during init; if it is missing the device dies with NoClassDefFoundError.
        try {
            Class.forName("com.arthenica.smartexception.java.Exceptions")
            Class.forName("com.arthenica.smartexception.AbstractExceptions")
            LogStore.event("SmartException dependency loaded")
        } catch (t: Throwable) {
            val reason = "SmartException classes missing from APK: " +
                "${t.javaClass.simpleName}: ${t.message}"
            LogStore.event(reason)
            return Report(
                EngineStatus.InitFailed(reason),
                null, deviceAbis, apkAbis, matched,
                "Streaming engine initialization failed."
            )
        }

        // 3) Engine initialization (loads the native libraries).
        val version = try {
            val v = FFmpegKitConfig.getFFmpegVersion() ?: "unknown"
            LogStore.event("FFmpegKit initialized successfully. Version: $v")
            v
        } catch (t: Throwable) {
            val reason = "${t.javaClass.simpleName}: ${t.message}"
            LogStore.event("FFmpeg initialization FAILED: $reason")
            return Report(
                EngineStatus.InitFailed(reason),
                null, deviceAbis, apkAbis, matched,
                "Streaming engine initialization failed."
            )
        }

        // 4) Real execution test: run `ffmpeg -version` and check the result.
        val testOk = try {
            val session = FFmpegKit.execute("-version")
            val ok = ReturnCode.isSuccess(session.returnCode)
            val firstLine = session.output?.lineSequence()?.firstOrNull().orEmpty()
            LogStore.event(
                "FFmpeg execution test (-version): " +
                    if (ok) "SUCCESS" else "FAILED (rc=${session.returnCode})"
            )
            if (firstLine.isNotEmpty()) LogStore.event("Test output: ${Sanitize.mask(firstLine)}")
            if (!ok) {
                LogStore.event("Test logs: ${Sanitize.mask(session.logsAsString ?: "")}")
            }
            ok
        } catch (t: Throwable) {
            LogStore.event("FFmpeg execution test crashed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }

        if (!testOk) {
            return Report(
                EngineStatus.TestFailed("ffmpeg -version did not execute successfully"),
                version, deviceAbis, apkAbis, matched,
                "Streaming engine initialization failed."
            )
        }

        // 5) FFprobe execution test (used to verify SAF/cache video inputs).
        val probeOk = try {
            val session = FFprobeKit.execute("-version")
            val ok = ReturnCode.isSuccess(session.returnCode)
            LogStore.event(
                "FFprobe execution test (-version): " +
                    if (ok) "SUCCESS" else "FAILED (rc=${session.returnCode})"
            )
            ok
        } catch (t: Throwable) {
            LogStore.event("FFprobe execution test crashed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!probeOk) {
            return Report(
                EngineStatus.TestFailed("ffprobe -version did not execute successfully"),
                version, deviceAbis, apkAbis, matched,
                "Streaming engine initialization failed."
            )
        }

        LogStore.event("FFmpeg runtime verification: READY")
        return Report(EngineStatus.Ready, version, deviceAbis, apkAbis, matched, "")
    }

    /** Lists the ABIs that have native libraries inside the installed APK. */
    private fun listApkAbis(context: Context): Set<String> {
        return try {
            val apkPath = context.applicationInfo.sourceDir
            val abis = mutableSetOf<String>()
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        val abi = name.removePrefix("lib/").substringBefore('/')
                        if (abi.isNotEmpty()) abis.add(abi)
                    }
                }
            }
            abis
        } catch (t: Throwable) {
            LogStore.event("APK native library inventory failed: ${t.javaClass.simpleName}")
            emptySet()
        }
    }
}
