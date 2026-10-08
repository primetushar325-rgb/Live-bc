package com.videolive.app.ffmpeg

import android.content.Context
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
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
 *     APK (lib/<abi>/*.so),
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

        // 2) Engine initialization (loads the native libraries).
        val version = try {
            val v = FFmpegKitConfig.getFFmpegVersion() ?: "unknown"
            LogStore.event("FFmpeg engine loaded. Version: $v")
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

        // 3) Real execution test: run `ffmpeg -version` and check the result.
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
